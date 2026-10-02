package com.multisuperplayer.core.data.library

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.SafTreeInfo
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private const val TAG = "MediaLibraryRepo"

/** 媒体库变动的合并窗口。插一整张专辑会连续触发几十次 onChange，合并成一次重扫。 */
private const val DEBOUNCE_MS = 800L

/**
 * 媒体库仓库。
 *
 * 负责四件事：
 * 1. 把 [MediaStoreScanner] 与 [SafTreeScanner] 的同步扫描**合并**成一个 [MediaLibraryState]；
 * 2. 监听 MediaStore 变化并自动刷新（用户用别的应用下了首歌，这里应该自己更新）；
 * 3. 保证任何时刻只有一个扫描在跑；
 * 4. 管住 SAF 授权清单（增/删/查），并在清单变化时让缓存失效。
 *
 * ## 为什么不做分页
 *
 * 一次扫全部并把结果放在内存里。手机上几万条音频的元数据大约是几 MB，
 * 而分页会让「按专辑分组」「随机播放全部」这类操作变得很难写。
 * 等到真出现十万级的库再换成 Paging，那时也知道确切的瓶颈在哪。
 *
 * ## SAF 结果为什么要缓存，而 MediaStore 的结果不用
 *
 * 本仓库 `refresh()` 会被 MediaStore 的 `ContentObserver` 自动触发（复制一首歌进
 * 手机，或手机后台自己的索引任务，都会触发），而 [DEBOUNCE_MS] 只是把一串
 * 密集回调合成一次。
 *
 * 「重扫 MediaStore」是一次数据库查询，几毫秒到几十毫秒；而「重扫 SAF 目录树」
 * 是**每个文件一次跨进程 document 查询**，一个几千条的目录要几秒。如果每次
 * 媒体库变动都连 SAF 一起重扫，症状是「往手机里拷一首歌，整个媒体库卡住几秒」。
 * 所以 SAF 结果按 `force` 参数缓存，只有用户主动点「重新扫描」或增删授权目录时
 * 才真的重扫。
 */
class MediaLibraryRepository(
    private val context: Context,
    private val scanner: MediaStoreScanner,
    private val safScanner: SafTreeScanner,
    private val safTreeStore: SafTreeStore,
    private val dispatchers: DispatcherProvider,
) {

    private val _state = MutableStateFlow<MediaLibraryState>(MediaLibraryState.Loading)
    val state: StateFlow<MediaLibraryState> = _state.asStateFlow()

    private val scope = CoroutineScope(
        dispatchers.default + SupervisorJob() + CoroutineName("MspMediaLibrary"),
    )

    /** 合并短时间内的多次外部变更。 */
    private val changeSignals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var scanJob: Job? = null
    private var observer: ContentObserver? = null

    /**
     * 上一次 SAF 扫描的结果；`null` 表示「还没有扫过，必须扫」。
     *
     * 只被本类读写，且只在 [scan] 里换值——授权清单一变就置空（见 [addTree]/[removeTree]）。
     */
    private var safCache: SafScanOutcome? = null

    /** 已授权的 SAF 目录清单，给「浏览」页用。 */
    val safTrees: Flow<List<SafTreeInfo>> get() = safTreeStore.trees

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            changeSignals.debounce(DEBOUNCE_MS).collect {
                MspLog.d(TAG) { "媒体库发生变化，重新扫描" }
                refresh()
            }
        }
    }

    /**
     * 需要向系统申请的权限列表（按当前系统版本决定）。
     *
     * 由仓库转发而不是让 UI 直接拿 scanner：UI 不需要知道「Android 14 的
     * READ_MEDIA_VISUAL_USER_SELECTED」这类版本差异，那是数据层的事。
     */
    fun requiredPermissions(): Array<String> = scanner.requiredPermissions()

    /**
     * 重新扫描。
     *
     * 取消上一次未完成的扫描：不取消的话，慢的那次可能后写回 StateFlow，
     * 用户就会看到「先显示新结果、又被旧结果覆盖」。
     *
     * **不**强制重扫 SAF 目录：见类注释。用户按「重新扫描」走 [rescan]。
     */
    fun refresh() {
        scan(forceSaf = false)
    }

    /**
     * 用户主动要求重扫。
     *
     * 与 [refresh] 唯一的区别是连带重扫 SAF 目录——用户的预期是「我要看到最新的
     * 文件列表」，而 SAF 缓存意味着不然他拔掉 SD 卡再插回来也看不到新文件。
     */
    fun rescan() {
        scan(forceSaf = true)
    }

    /** 授权一个新的目录树，然后强制重扫。 */
    fun addTree(treeUri: String) {
        scope.launch {
            safTreeStore.add(treeUri, labelOfTree(treeUri))
            safCache = null
            scan(forceSaf = true)
        }
    }

    /** 撤销一个已授权的目录树，然后强制重扫。 */
    fun removeTree(treeUri: String) {
        scope.launch {
            safTreeStore.remove(treeUri)
            safCache = null
            scan(forceSaf = true)
        }
    }

    /**
     * 这条授权现在还能用吗。
     *
     * 授权是**可以被系统单方面收回**的（用户在设置里撤销、SD 卡拔出、provider 被
     * 卸载），而我们这边存的清单不会跟着变。所以界面在展示清单时必须能问出这个
     * 状态，才能把「已授权但现在打不开」和「正常」区分开——否则用户看到的是
     * 「我明明授权了，为什么里面是空的」，而重新授权是他唯一该做的事。
     */
    fun isTreeAccessible(treeUri: String): Boolean = safScanner.hasReadPermission(treeUri)

    private fun labelOfTree(treeUri: String): String? = runCatching {
        DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
    }.getOrNull()?.let(SafTreeLabelRules::labelOf)

    private fun scan(forceSaf: Boolean) {
        scanJob?.cancel()
        scanJob = scope.launch {
            val hasMediaStorePermission = scanner.hasAnyPermission()
            val treeUris = safTreeStore.currentTreeUris()
            val hasSafTrees = !treeUris.isNullOrEmpty()

            // 「需要授权」只在**两条路都走不通**时才是终态。
            // 只判断 MediaStore 的权限是错的：用户可以拒绝存储权限、只授权一个
            // SAF 目录——那种情况下媒体库是能用的，把他挡在「请授权」页面上
            // 等于告诉他「你刚授权的东西没用」。
            if (!hasMediaStorePermission && !hasSafTrees) {
                // 这里不写 Loading：权限问题是一个**终态**，来回闪 Loading 只会让用户困惑。
                _state.value = MediaLibraryState.NeedsPermission
                return@launch
            }

            // 只有第一次（或上次失败）才显示加载态。已有内容时再刷成 Loading 会让列表
            // 整个消失一下——刷新应当是原地替换，不是清空重画。
            if (_state.value !is MediaLibraryState.Ready) {
                _state.value = MediaLibraryState.Loading
            }

            runCatching {
                val mediaStore = if (hasMediaStorePermission) {
                    scanner.scanAll()
                } else {
                    MediaStoreScanner.ScanOutcome(entries = emptyList(), partial = false)
                }
                mediaStore to scanSaf(treeUris, forceSaf)
            }
                .onSuccess { (mediaStore, saf) ->
                    _state.value = MediaLibraryState.Ready(
                        entries = LibraryMergeRules.merge(mediaStore.entries, saf.entries),
                        partial = mediaStore.partial,
                        truncated = saf.truncated,
                    )
                }
                .onFailure { error ->
                    MspLog.e(TAG, error) { "扫描媒体库失败" }
                    _state.value = MediaLibraryState.Error(
                        error.message?.takeIf { it.isNotBlank() }?.let(MspText::Plain)
                            ?: MspText.Res(
                                R.string.msp_library_scan_failed,
                                error::class.java.simpleName,
                            ),
                    )
                }
        }
    }

    /**
     * SAF 那半边。
     *
     * [treeUris] 为 null 表示清单读不出来（文件被外部清掉），此时**沿用上一次的
     * 结果**而不是当成「没有授权任何目录」：清单读不出来和用户取消了授权是两件事，
     * 把前者当成后者会让媒体库里所有 SAF 条目突然消失一次——而它们其实还在。
     */
    private suspend fun scanSaf(treeUris: List<String>?, force: Boolean): SafScanOutcome {
        val cached = safCache
        if (!force && cached != null) return cached
        if (treeUris == null) {
            MspLog.w(TAG) { "SAF 授权清单读不出来，沿用上次扫描结果" }
            return cached ?: SafScanOutcome(entries = emptyList(), truncated = false)
        }
        val outcome = if (treeUris.isEmpty()) {
            SafScanOutcome(entries = emptyList(), truncated = false)
        } else {
            safScanner.scan(treeUris)
        }
        safCache = outcome
        return outcome
    }

    /** 开始监听媒体库变化。幂等，重复调用只注册一次。 */
    fun startObserving() {
        if (observer != null) return
        val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                changeSignals.tryEmit(Unit)
            }
        }
        val resolver = context.contentResolver
        runCatching {
            resolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, contentObserver)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, contentObserver)
        }.onFailure { error ->
            // 注不上不该让功能不可用，只是不再自动刷新，手动刷新仍然有效。
            MspLog.w(TAG, error) { "注册媒体库监听失败，将只在手动刷新时更新" }
        }
        observer = contentObserver
    }

    fun stopObserving() {
        val contentObserver = observer ?: return
        runCatching { context.contentResolver.unregisterContentObserver(contentObserver) }
        observer = null
    }

    fun release() {
        stopObserving()
        scanJob?.cancel()
        scope.cancel()
    }
}
