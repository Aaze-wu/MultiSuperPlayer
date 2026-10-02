package com.multisuperplayer.core.data.library

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.R
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * 负责三件事：
 * 1. 把 [MediaStoreScanner] 的同步扫描包成 [MediaLibraryState]；
 * 2. 监听 MediaStore 变化并自动刷新（用户用别的应用下了首歌，这里应该自己更新）；
 * 3. 保证任何时刻只有一个扫描在跑。
 *
 * ## 为什么不做分页
 *
 * 一次扫全部并把结果放在内存里。手机上几万条音频的元数据大约是几 MB，
 * 而分页会让「按专辑分组」「随机播放全部」这类操作变得很难写。
 * 等到真出现十万级的库再换成 Paging，那时也知道确切的瓶颈在哪。
 */
class MediaLibraryRepository(
    private val context: Context,
    private val scanner: MediaStoreScanner,
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
     */
    fun refresh() {
        scanJob?.cancel()
        scanJob = scope.launch {
            if (!scanner.hasAnyPermission()) {
                // 这里不写 Loading：权限问题是一个**终态**，来回闪 Loading 只会让用户困惑。
                _state.value = MediaLibraryState.NeedsPermission
                return@launch
            }

            // 只有第一次（或上次失败）才显示加载态。已有内容时再刷成 Loading 会让列表
            // 整个消失一下——刷新应当是原地替换，不是清空重画。
            if (_state.value !is MediaLibraryState.Ready) {
                _state.value = MediaLibraryState.Loading
            }

            runCatching { scanner.scanAll() }
                .onSuccess { outcome ->
                    _state.value = MediaLibraryState.Ready(outcome.entries, outcome.partial)
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
