package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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
    /** 已应用筛选与搜索的结果。 */
    val entries: List<MediaEntry> = emptyList(),
    /** 各筛选维度下**搜索命中**的条目数，用于在标签上显示「音乐 128」。 */
    val counts: Map<LibraryFilter, Int> = emptyMap(),
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
): LibraryUiState {
    val all = (library as? MediaLibraryState.Ready)?.entries.orEmpty()
    val searched = all.filterByQuery(query)
    return LibraryUiState(
        library = library,
        filter = filter,
        query = query,
        entries = searched.filter(filter::accepts),
        // 计数按**搜索后**的集合算，而不是全库：搜索时标签上的数字跟着搜索走，
        // 否则会出现「显示『音乐 0』但点进去有 12 条」的诡异状态。
        //
        // 但这份计数**不**负责回答「库里有东西吗」——那是 libraryCount 的事。
        // 两者被混用过，代价是搜索一个匹配不到的词就把搜索框藏了起来。
        counts = LibraryFilter.entries.associateWith { f -> searched.count(f::accepts) },
    )
}

/**
 * 媒体库 ViewModel。
 *
 * 这一层只做三件事：合并「数据状态 + 筛选 + 搜索」、转发用户动作、管理观察的生命周期。
 * 它**不**判断权限该不该申请（那是界面的事，因为只有界面知道该不该弹系统对话框）。
 */
class LibraryViewModel(
    private val repository: MediaLibraryRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(LibraryFilter.ALL)
    private val query = MutableStateFlow("")

    val uiState: StateFlow<LibraryUiState> =
        combine(repository.state, filter, query) { library, currentFilter, currentQuery ->
            buildLibraryUiState(library, currentFilter, currentQuery)
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

    fun refresh() {
        repository.refresh()
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
    }
}
