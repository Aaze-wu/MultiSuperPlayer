package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 媒体库的界面状态。
 *
 * [entries] 是**筛选后**的结果，[library] 是原始状态。两者都在同一个状态对象里：
 * 界面既要按筛选结果渲染列表，又要在「筛选后为空但库里其实有东西」时
 * 显示与「一条都没有」**不同**的提示——只有拿得到两个来源才能区分这两种情况。
 */
data class LibraryUiState(
    val library: MediaLibraryState = MediaLibraryState.Loading,
    val filter: LibraryFilter = LibraryFilter.ALL,
    val query: String = "",
    /** 已应用筛选与搜索的结果。**尚未排序**：排序/分组是展示方式，由 [LibraryArrangement] 在界面层算。 */
    val entries: List<MediaEntry> = emptyList(),
    /** 各筛选维度下**搜索命中**的条目数，用于在标签上显示「音乐 128」。 */
    val counts: Map<LibraryFilter, Int> = emptyMap(),
    val sort: LibrarySort = LibrarySort.TITLE_ASC,
    val groupMode: LibraryGroupMode = LibraryGroupMode.NONE,
    val viewMode: LibraryViewMode = LibraryViewMode.LIST,
) {
    /** 当前搜索命中了多少条（尚未应用类型筛选）。**不是**库里一共有多少条。 */
    val allCount: Int get() = counts[LibraryFilter.ALL] ?: 0

    /**
     * 原始库（未经搜索、未经筛选）里有多少条。
     *
     * 必须和 [allCount] 分开。之前这里只有 [allCount]，界面就拿它当「库里有东西吗」用，
     * 于是出过一个把用户关死的 bug：输入一个匹配不到的词 → [allCount] 归零 →
     * 工具栏（含搜索框）被判为「没有内容」而整个隐藏，可查询词还留在状态里。
     * 用户既看不见自己输的字，也没有任何入口清掉它，只能杀进程。
     */
    val libraryCount: Int get() = (library as? MediaLibraryState.Ready)?.entries?.size ?: 0

    /** 库里一条都没有。与搜索无关，所以「还没有扫描到媒体」只由它触发。 */
    val libraryEmpty: Boolean get() = libraryCount == 0

    /**
     * 库里有内容，只是被当前搜索/筛选挡光了。
     *
     * 判据用 [libraryEmpty] 而不是 `allCount > 0`：搜到一个谁都匹配不上的词时
     * [allCount] 同样是 0，界面会退回「还没有扫描到媒体，把文件放进手机存储后重新扫描」，
     * 把「没搜到」说成「本机没有文件」——而这两件事用户要做的事完全相反。
     */
    val filteredOut: Boolean get() = !libraryEmpty && entries.isEmpty()

    /**
     * 只拿到部分媒体权限。
     *
     * 和 [truncated] 分开：这两个提示要叫用户做的事完全不同（一个是去授权，
     * 一个是「东西太多，分批看」），共用一个横幅就会出现「提示你去授权，
     * 但权限早就给全了」。
     */
    val partial: Boolean get() = (library as? MediaLibraryState.Ready)?.partial == true

    /** SAF 扫描被深度/条目上限截断了：列表是「少了」，不是「没有」。 */
    val truncated: Boolean get() = (library as? MediaLibraryState.Ready)?.truncated == true
}

/**
 * 把「数据状态 + 筛选 + 搜索」折算成界面状态。
 *
 * 抽成顶层纯函数是为了能单测。它原本是 ViewModel 里 `combine {}` 的 lambda，
 * 想测它就得连 repository、协程调度器一起伪造；而这里恰好是出过两个真实缺陷的地方
 * （搜索时搜索框消失、空状态与列表同时显示），必须有测试钉住。
 */
internal fun buildLibraryUiState(
    library: MediaLibraryState,
    filter: LibraryFilter,
    query: String,
    sort: LibrarySort = LibrarySort.TITLE_ASC,
    groupMode: LibraryGroupMode = LibraryGroupMode.NONE,
    viewMode: LibraryViewMode = LibraryViewMode.LIST,
): LibraryUiState {
    val all = (library as? MediaLibraryState.Ready)?.entries.orEmpty()
    val searched = all.filterByQuery(query)
    return LibraryUiState(
        library = library,
        filter = filter,
        query = query,
        // 这里**不**排序：entries 同时被当「筛选结果」和「计数来源」用，
        // 而排序只影响怎么画。两者混在一起时，将来加一种排序就要顺手确认
        // 它不会把标签上的数字也改掉。
        entries = searched.filter(filter::accepts),
        // 计数按**搜索后**的集合算，而不是全库：搜索时标签上的数字跟着搜索走，
        // 否则会出现「显示『音乐 0』但点进去有 12 条」的诡异状态。
        //
        // 但这份计数**不**负责回答「库里有东西吗」——那是 libraryCount 的事。
        // 两者被混用过，代价是搜索一个匹配不到的词就把搜索框藏了起来。
        counts = LibraryFilter.entries.associateWith { f -> searched.count(f::accepts) },
        sort = sort,
        groupMode = groupMode,
        viewMode = viewMode,
    )
}

/**
 * 排列相关的三个维度。单独打成一个包是为了配合 `combine` 的重载个数（最多 5 个）。
 */
private data class LibraryViewOptions(
    val sort: LibrarySort,
    val groupMode: LibraryGroupMode,
    val viewMode: LibraryViewMode,
)

/**
 * 回到前台时该不该重扫媒体库。
 *
 * 抽成顶层纯函数是为了能**钉住这条策略本身**：它曾经不存在，症状是「启动时统一弹框
 * 授权之后，媒体库还停在『需要媒体授权』，要点一下按钮才刷新」——一个看起来像
 * 授权失败、其实只是没人去重扫的状态。
 */
internal fun shouldRescanOnResume(state: MediaLibraryState): Boolean =
    state is MediaLibraryState.NeedsPermission

/**
 * 媒体库 ViewModel。
 *
 * 这一层只做三件事：合并「数据状态 + 筛选 + 搜索」、转发用户动作、管理观察的生命周期。
 * 它**不**判断权限该不该申请（那是界面的事，因为只有界面知道该不该弹系统对话框）。
 */
class LibraryViewModel(
    private val repository: MediaLibraryRepository,
    playlistStore: PlaylistStore,
) : ViewModel() {

    private val playlistEditing = PlaylistEditing(playlistStore)

    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(LibrarySort.TITLE_ASC)
    private val groupMode = MutableStateFlow(LibraryGroupMode.NONE)
    private val viewMode = MutableStateFlow(LibraryViewMode.LIST)

    private val options = combine(sort, groupMode, viewMode) { s, g, v ->
        LibraryViewOptions(s, g, v)
    }

    private val _message = MutableStateFlow<UiMessage?>(null)

    /** 一次性提示。界面用 [UiMessage.nonce] 当 key 显示，不需要「已消费」回调。 */
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val messageNonce = AtomicLong()

    /**
     * 可供「加入播放列表」选择的播放列表。
     *
     * `WhileSubscribed` + 5 秒超时：退出媒体库之后再回来不用重新读一遍 DataStore，
     * 但真的离开之后订阅会被取消，不会为了一个看不见的对话框常驻一个 DataStore 观察者。
     */
    val playlists: StateFlow<List<Playlist>> = playlistEditing.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    val uiState: StateFlow<LibraryUiState> =
        combine(repository.state, filter, query, options) { library, currentFilter, currentQuery, current ->
            buildLibraryUiState(
                library = library,
                filter = currentFilter,
                query = currentQuery,
                sort = current.sort,
                groupMode = current.groupMode,
                viewMode = current.viewMode,
            )
        }.stateIn(
            scope = viewModelScope,
            // 屏幕旋转/短暂失焦时不取消上游订阅：重建 ViewModel 会重新触发扫描，
            // 而扫描是同步 IO，旋转一次卡一次。
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LibraryUiState(),
        )

    init {
        repository.startObserving()
        repository.refresh()
    }

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: LibrarySort) {
        sort.value = value
    }

    fun setGroupMode(value: LibraryGroupMode) {
        groupMode.value = value
    }

    fun setViewMode(value: LibraryViewMode) {
        viewMode.value = value
    }

    /** 把 [entries] 追加到某个已有播放列表。 */
    fun addToPlaylist(playlistId: String, entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val added = playlistEditing.addTo(playlistId, entries)
            // 去重发生在存储层（PlaylistRules.withAdded），所以「几项被跳过」只有它能告诉我们。
            // 这里如实报告，而不是一律说「已加入」——否则用户会以为点了两次就真的加了两遍。
            publish(addedToPlaylistText(added, entries.size))
        }
    }

    /** 新建播放列表并把 [entries] 放进去。 */
    fun createPlaylistWith(name: String, entries: List<MediaEntry>) {
        viewModelScope.launch {
            val added = playlistEditing.createWith(name, entries)
            if (added == null) {
                publish(MspText.Res(R.string.msp_library_playlist_create_failed))
                return@launch
            }
            publish(addedToPlaylistText(added, entries.size))
        }
    }

    private fun publish(text: MspText) {
        _message.value = UiMessage(text = text, nonce = messageNonce.incrementAndGet())
    }

    fun refresh() {
        repository.refresh()
    }

    /**
     * 回到前台时调用：只在「上次的结论是一个权限都没有」时重扫。
     *
     * 这一格是给**别人**申请的权限补的路：应用首次启动时统一弹的系统框由应用根上的
     * launcher 发起，媒体库自己的 launcher 拿不到那次回调，于是用户刚点完「允许」，
     * 媒体库还停在「需要媒体授权」。切回前台时重扫一次就自愈了。
     *
     * 别的结论都不重扫：`Ready` 重扫是白花钱（每次回前台都要查一遍 MediaStore），
     * `Loading` 本来就在扫。
     */
    fun onResumed() {
        if (shouldRescanOnResume(repository.state.value)) repository.refresh()
    }

    /**
     * 权限对话框返回后调用。
     *
     * 允许也好、拒绝也好，都重扫一次：仓库自己会按**实际**权限决定给出列表还是
     * 「需要授权」。在这里再判断一次「用户到底选了什么」等于把系统版本差异写两遍，
     * 而两处一旦不一致，症状就是「授权了但还是显示要授权」。
     */
    fun onPermissionsResult() {
        repository.refresh()
    }

    fun requiredPermissions(): Array<String> = repository.requiredPermissions()

    override fun onCleared() {
        // 只停监听，不 release 仓库：仓库是 Koin 单例，退出媒体库不影响播放。
        repository.stopObserving()
        super.onCleared()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TAG = "LibraryViewModel"
    }
}
