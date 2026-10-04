package com.multisuperplayer.feature.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.data.export.PlaybackExport
import com.multisuperplayer.core.data.export.PlaybackExportFormat
import com.multisuperplayer.core.data.export.PlaybackImport
import com.multisuperplayer.core.data.export.PlaybackImportException
import com.multisuperplayer.core.data.export.ImportedPlaylist
import com.multisuperplayer.core.data.export.TextExportWriter
import com.multisuperplayer.core.data.export.TextImportReader
import com.multisuperplayer.core.data.export.TextImportResult
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.data.playlist.PlaylistImportMergeChoice
import com.multisuperplayer.core.data.playlist.PlaylistImportOptions
import com.multisuperplayer.core.data.playlist.PlaylistImportPlan
import com.multisuperplayer.core.data.playlist.PlaylistImportState
import com.multisuperplayer.core.data.playlist.PlaylistImporter
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.text.MspText
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 打开中的播放列表。 */
data class PlaylistDetail(
    val id: String,
    val name: String,
    val rows: List<PlaylistRow>,
    val queue: List<MediaEntry>,
) {
    val missingCount: Int get() = rows.count { it.missing }
}

/**
 * 播放列表页的界面状态。
 *
 * [playlists] 是**可空**的，null 表示 DataStore 还没吐出第一份数据。空列表也可以
 * 合法地表示「一个播放列表都没有」——这两件事必须分开，否则每次进入这一页都会
 * 先闪一下「还没有播放列表」。
 */
data class PlaylistsUiState(
    val playlists: List<Playlist>? = null,
    val open: PlaylistDetail? = null,
) {
    val loading: Boolean get() = playlists == null
}

/**
 * 媒体库里「按 id 查条目」的那张表。
 *
 * 抽出来是因为有两个调用方（列表/详情的拼装、导出），而「怎么查」必须完全一样：
 * 两边各写一次 `.associateBy { it.id }` 看似无隦，但只要其中一边換成按 `uri` 索引，
 * 导出的文件就会和屏幕上的对不上——而且两边都不会报错。
 */
internal fun MediaLibraryState.entriesById(): Map<String, MediaEntry> =
    (this as? MediaLibraryState.Ready)?.entries?.associateBy { it.id }.orEmpty()

/**
 * 把「播放列表 + 媒体库 + 当前打开的是哪个」拼成界面状态。
 *
 * 打开的那个列表**查不到就当作没打开**（而不是保留上一次的详情）：用户可能
 * 在别处把它删了，这时应该退回列表页。
 *
 * @param fileExists 浏览页条目的「路径还在不在」。库查不到不等于文件没了，
 *   详见 [resolvePlaylistItem]。默认值一律当作不在，理由见 `PlaylistRows.build`。
 */
internal fun buildPlaylistsUiState(
    playlists: List<Playlist>,
    library: MediaLibraryState,
    openId: String?,
    fileExists: (String) -> Boolean = { false },
): PlaylistsUiState {
    val byId = library.entriesById()
    val playlist = openId?.let { id -> playlists.firstOrNull { it.id == id } }
    return PlaylistsUiState(
        playlists = playlists,
        open = playlist?.let {
            PlaylistDetail(
                id = it.id,
                name = it.name,
                rows = PlaylistRows.build(it.items, byId, fileExists),
                queue = PlaylistRows.queue(it.items, byId),
            )
        },
    )
}

/**
 * 播放列表页的 ViewModel。
 *
 * 这里**不**调用 `MediaLibraryRepository.startObserving()`/`refresh()`：媒体库是
 * 起始标签，它的 ViewModel 绑在导航栈底部那个条目上，活到应用结束，所以库数据
 * 一定已经在被观察。新页面只需要读它。反过来说，如果哪天改了导航结构让媒体库
 * 页可以被销毁，这里就得自己补上一句——所以把这条依赖写在这里。
 */
class PlaylistsViewModel(
    private val library: MediaLibraryRepository,
    private val store: PlaylistStore,
    private val dispatchers: DispatcherProvider,
    context: Context,
    private val exportWriter: TextExportWriter,
    private val importReader: TextImportReader,
    private val importer: PlaylistImporter,
) : ViewModel() {

    /**
     * 导出时用的 `Resources`。只留 applicationContext：ViewModel 活得比
     * Activity 久，握住 Activity 的 context 就是泄漏它。
     */
    private val appContext = context.applicationContext

    private val openId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PlaylistsUiState> = combine(
        store.playlists,
        library.state,
        openId,
    ) { playlists, libraryState, open ->
        buildPlaylistsUiState(playlists, libraryState, open, fileExists = ::fileExists)
    }.flowOn(dispatchers.io).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlaylistsUiState(),
    )

    /**
     * 浏览页条目的「路径还在不在」。
     *
     * 这是这一页唯一会碰文件系统的地方（整个 flow 在 `flowOn(dispatchers.io)` 上），
     * 代价是一个 `stat`，换来的是「文件确实删了」这条信息；不查的话从浏览器加进来的
     * 条目就只能一律按「不确定」处理。
     *
     * 失败一律算**不在**：判断不了的路径当「还在」就会让用户点进去才撞上错误。
     */
    private fun fileExists(path: String): Boolean =
        path.isNotEmpty() && runCatching { File(path).exists() }.getOrDefault(false)

    fun open(id: String) {
        openId.value = id
    }

    fun close() {
        openId.value = null
    }

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { store.create(name) }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { store.rename(id, name) }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            store.remove(id)
            // 删掉的正是打开着的那个：状态要跟着退回列表页，否则详情页
            // 会因为「查不到」而自己变空，看起来像卡在了一个不存在的列表里。
            if (openId.value == id) openId.value = null
        }
    }

    fun removeItems(id: String, mediaIds: Collection<String>) {
        if (mediaIds.isEmpty()) return
        viewModelScope.launch { store.removeItems(id, mediaIds) }
    }

    /**
     * 拖动排序。
     *
     * [from] / [to] 是**当前列表里的下标**，越界时 [PlaylistStore.moveItem] 什么都不做
     * （而不是夹到最近的位置）：拖动期间列表可能被别处改短，夹取会把条目放到用户
     * 没指过的地方，而「什么都没发生」最多让人再拖一次。
     *
     * 只在手指松开时调用一次。拖动过程中每一帧都落盘一次没有意义，而且会让
     * DataStore 一直重写整条列表。
     */
    fun moveItem(id: String, from: Int, to: Int) {
        viewModelScope.launch { store.moveItem(id, from, to) }
    }

    /**
     * 拖动播放列表**本身**排序。
     *
     * 和 [moveItem] 的区别只在落点：这一条改的是「列表页里各个播放列表的先后」，
     * [moveItem] 改的是「某个播放列表内部条目的先后」。两者的下标是两套坐标系，
     * 混起来（把列表页的下标传给 `store.moveItem`）不会报错，
     * 只会默默改掉另一个列表的内容——所以两个方法各叫各的名字。
     */
    fun move(from: Int, to: Int) {
        viewModelScope.launch { store.move(from, to) }
    }

    // ------------------------------------------------------------------ 导出

    private val mutableExport = MutableStateFlow<PlaybackExportState>(PlaybackExportState.Idle)

    /** 导出进度。界面订阅它，在 [PlaybackExportState.message] 非空时弹一句提示。 */
    val export: StateFlow<PlaybackExportState> = mutableExport.asStateFlow()

    /** 列表页「导出全部」的文件名（不带具体列表名，因为里面是好几份）。 */
    fun suggestedExportName(format: PlaybackExportFormat): String =
        PlaybackExport.suggestedFileName(resolve(PlaybackExport.allPlaylistsName()), format)

    /**
     * 详情页「导出这个列表」的文件名。
     *
     * 名字从 [PlaylistsUiState.playlists] 里查（而不是从 `open`）：系统选择器可能
     * 开着好一会儿，期间用户会退回列表页，而那份列表本身还在——文件名不该因为
     * 「他看了一眼别的」而丢掉（丢掉的话会退成只有时间戳的名字，很难认）。
     * 查不到就交空串，由 `PlaybackExport.suggestedFileName` 退回纯时间戳。
     */
    fun suggestedExportName(id: String, format: PlaybackExportFormat): String =
        PlaybackExport.suggestedFileName(
            uiState.value.playlists?.firstOrNull { it.id == id }?.name.orEmpty(),
            format,
        )

    /**
     * 导出**全部**播放列表（列表页那个入口）。
     *
     * 这里是真的重新去读一遍 `store` / `library`（而不是像最近播放那样用屏幕上
     * 的缓存）：导出的是**所有**列表，而屏幕上那份只包含列表页需要的东西。
     * 代价是要等两个 flow 各给一个值，好处是「在某份列表的详情页里点导出全部」
     * 也能拿到完整数据。
     */
    fun exportAll(uri: Uri, format: PlaybackExportFormat) {
        if (mutableExport.value == PlaybackExportState.Running) return
        mutableExport.value = PlaybackExportState.Running
        val fileName = suggestedExportName(format)
        viewModelScope.launch {
            // `stat` 与顺次编码都放到 IO：浏览页条目的「文件还在不在」是真去碰磁盘，
            // 和 [buildPlaylistsUiState] 走 `flowOn(dispatchers.io)` 是同一个理由。
            val collections = withContext(dispatchers.io) {
                playlistExportCollections(
                    playlists = store.playlists.first(),
                    library = library.state.first(),
                    fileExists = ::fileExists,
                )
            }
            mutableExport.value = exportPlaybackTo(
                writer = exportWriter,
                uri = uri,
                format = format,
                collections = collections,
                fileName = fileName,
                resolve = ::resolve,
            )
        }
    }

    /**
     * 导出**当前打开的那个**播放列表（详情页那个入口）。
     *
     * 用的是 [PlaylistsUiState.open] 里已经算好的行——也就是屏幕上那几行
     * （包括标了「文件已不在」的）。重算一遍反而会跟屏幕不一致。
     *
     * [id] 必须对上现在打开的那一份：系统选择器开着的时候用户可以退回列表页、
     * 或者打开的已经是另一份了。那时目标已经不存在，直接给
     * [PlaybackExportState.Empty]，不要随便挑一份列表塞给他。
     */
    fun exportOpen(id: String, uri: Uri, format: PlaybackExportFormat) {
        if (mutableExport.value == PlaybackExportState.Running) return
        val detail = uiState.value.open?.takeIf { it.id == id }
        if (detail == null) {
            mutableExport.value = PlaybackExportState.Empty
            return
        }
        mutableExport.value = PlaybackExportState.Running
        val fileName = suggestedExportName(id, format)
        viewModelScope.launch {
            mutableExport.value = exportPlaybackTo(
                writer = exportWriter,
                uri = uri,
                format = format,
                collections = listOf(detail.toExportCollection()),
                fileName = fileName,
                resolve = ::resolve,
            )
        }
    }

    /** 用户看完了那句提示。 */
    fun dismissExport() {
        mutableExport.value = PlaybackExportState.Idle
    }

    // ------------------------------------------------------------------ 导入

    private val mutableImport = MutableStateFlow<PlaylistImportState>(PlaylistImportState.Idle)

    /**
     * 导入进度。界面在 [PlaylistImportState.Ask] 时弹重名对话框，其余时候念 [PlaylistImportState.message]。
     *
     * 不叫 `import`：那是 Kotlin 的硬关键字，属性名只能用反引号包住，
     * 而每一处读取都得跟着加反引号（漏一处就是语法错误，而不是警告）。
     */
    val importState: StateFlow<PlaylistImportState> = mutableImport.asStateFlow()

    /** 用户到目前为止的答案。每答一个就重算一次方案（见 [PlaylistImporter.plan]）。 */
    private var importOptions = PlaylistImportOptions()

    /** 正在进行的这一次导入。取消它就等于「什么都没发生」（还没写盘）。 */
    private var importJob: Job? = null

    /**
     * 等用户回答那一个重名列表的信号。
     *
     * 用 `CompletableDeferred` 而不是「把答案存进一个字段、让协程循环去看」：
     * 对话框是**异步的一次性事件**（它可能被取消，也可能连点两下），
     * 而 `Deferred` 只允许完成一次——重复回答不会让导入跑两遍。
     */
    private var importChoice: CompletableDeferred<Pair<PlaylistImportMergeChoice, Boolean>>? = null

    /**
     * 读一个文件并导入里面的播放列表。
     *
     * 文件名不从调用方传：它在 [TextImportReader] 里顺手就查出来了（同一个
     * `contentResolver.query`），而调用方要想拿到它就得在本就异步的回调里
     * 再查一次，或者提前把「将要选的文件名」记下来——后者在用户改了名、
     * 或者干脆按了返回时就变成了错的数据。
     */
    fun importFrom(uri: Uri) {
        // 重入保护：连点两下会各自读一遍文件、各算一遍、各写一遍
        //（[PlaylistStore.apply] 是原子的，但两次调用就是两批）。
        if (mutableImport.value is PlaylistImportState.Reading) return
        importOptions = PlaylistImportOptions()
        importChoice = null
        mutableImport.value = PlaylistImportState.Reading
        importJob = viewModelScope.launch {
            val read = importReader.read(uri)
            val text = when (read) {
                is TextImportResult.Read -> read.text
                is TextImportResult.Failed -> {
                    mutableImport.value = PlaylistImportState.Failed(importFailureMessage(read))
                    return@launch
                }
            }
            // 兜底列表名：文件里没有列表名时用它（见 [PlaybackImport.fallbackNameOf]）。
            val fallback = PlaybackImport.fallbackNameOf(
                read.displayName,
                resolve(MspText.Res(R.string.msp_import_default_name)),
            )
            val imported = try {
                PlaybackImport.decode(
                    raw = text,
                    fallbackName = fallback,
                    aliases = importReader.csvAliases(appContext.resources),
                )
            } catch (error: PlaybackImportException) {
                // 文件认不出/缺列/行错位：把这些情况**分开**说，
                // 因为它们要用户做的事不一样（换文件 / 修文件 / 什么都不用做）。
                mutableImport.value = PlaylistImportState.Failed(error.text)
                return@launch
            }
            runImport(imported)
        }
    }

    /**
     * 用户对重名列表做的选择。
     *
     * 不是在写盘时记录，而是**存起来重算方案**：重名是逐名问的，
     * 而在最后一个名字被回答之前库里一个字节都不会动（见 [PlaylistImporter]）。
     *
     * @param applyToAll 用户勾了「剩下的都这样处理」。它必须从这里传进来：
     *   「还要不要问」是**界面**才知道的事（那个勾选框在对话框里），
     *   而 ViewModel 自己无法区分「同一个答案」和「以后都这样」。
     */
    fun chooseImportMerge(choice: PlaylistImportMergeChoice, applyToAll: Boolean) {
        val signal = importChoice ?: return
        importChoice = null
        // 两个数据（答案本身、「还要不要问」）一起送过去，而不是另存一个字段：
        // `viewModelScope` 跑在 `Dispatchers.Main.immediate` 上，`complete()`
        // 又是在主线程调的，所以等待方会**就地**被唤醒并立刻读那个字段。
        // 分两次写就成了一个「只在不报错的时候才错」的时序问题——“勾了‘全部套用’
        // 还是一个个弹框”而不抛任何异常。
        //
        // 只允许完成一次：连点两下时第二个调用拿到的是 null，什么也不会发生。
        signal.complete(choice to applyToAll)
    }

    /** 用户关掉了提示/对话框。进行中的导入就此取消（还没写盘，所以什么也没留下）。 */
    fun dismissImport() {
        importJob?.cancel()
        importJob = null
        importChoice = null
        mutableImport.value = PlaylistImportState.Idle
    }

    /**
     * 「问完再写」的主循环。
     *
     * 为什么是个循环而不是一次算完：重名要一个一个问（一次弹 20 个框更糟），
     * 而每答一个都要拿到目前为止的**全部**答案重算一遍方案——重算而不是往后追加，
     * 是因为新名字必须避开这一批里已经定下来的名字，而那件事只有重算才知道。
     */
    private suspend fun runImport(imported: List<ImportedPlaylist>) {
        while (true) {
            val plan = importer.plan(imported, importOptions)
            val asking = plan.pendingNames.firstOrNull()
            if (asking != null) {
                val signal = CompletableDeferred<Pair<PlaylistImportMergeChoice, Boolean>>()
                importChoice = signal
                mutableImport.value = PlaylistImportState.Ask(
                    name = asking,
                    remaining = plan.pendingNames.size,
                )
                val (choice, applyToAll) = signal.await()
                mutableImport.value = PlaylistImportState.Reading
                // 先把这一次的答案记进去（无论勾没勾「全部套用」都要记：重算是
                // 从零开始的，没记下来的名字下次还会被当成没答过，于是又弹一次框）。
                val decisions = importOptions.decisions + (asking to choice)
                importOptions = if (applyToAll) {
                    PlaylistImportOptions(
                        decisions = decisions,
                        applyToAll = true,
                        applyToAllAs = choice,
                    )
                } else {
                    importOptions.copy(decisions = decisions)
                }
                continue
            }
            if (plan.isEmpty) {
                mutableImport.value = PlaylistImportState.Empty
                return
            }
            val outcome = importer.apply(plan)
            mutableImport.value = if (outcome == null) {
                PlaylistImportState.Failed(MspText.Res(R.string.msp_import_error_save))
            } else {
                PlaylistImportState.Finished(outcome)
            }
            return
        }
    }

    /**
     * 读文件失败时的三种说法。
     *
     * 不合并成一句「读取失败」：三种情况用户要做的事完全不同——
     * 重新选一次（权限就跟着新 Uri 走了）、换一个文件、或者只是重试一次。
     */
    private fun importFailureMessage(result: TextImportResult.Failed): MspText = when (result) {
        is TextImportResult.Failed.NoPermission -> MspText.Res(R.string.msp_import_error_permission)
        is TextImportResult.Failed.NotFound -> MspText.Res(R.string.msp_import_error_not_found)
        is TextImportResult.Failed.Other ->
            MspText.Res(R.string.msp_import_error_read, MspText.Plain(result.reason))
    }

    /** 导出文件里会出现中文表头，所以要能解析字符串（见 [appContext]）。 */
    private fun resolve(text: MspText): String = text.resolve(appContext.resources)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
