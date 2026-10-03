package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val byId = (library as? MediaLibraryState.Ready)?.entries?.associateBy { it.id }.orEmpty()
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
    library: MediaLibraryRepository,
    private val store: PlaylistStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
