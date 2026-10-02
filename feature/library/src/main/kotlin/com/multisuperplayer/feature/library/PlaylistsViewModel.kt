package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 */
internal fun buildPlaylistsUiState(
    playlists: List<Playlist>,
    library: MediaLibraryState,
    openId: String?,
): PlaylistsUiState {
    val byId = (library as? MediaLibraryState.Ready)?.entries?.associateBy { it.id }.orEmpty()
    val playlist = openId?.let { id -> playlists.firstOrNull { it.id == id } }
    return PlaylistsUiState(
        playlists = playlists,
        open = playlist?.let {
            PlaylistDetail(
                id = it.id,
                name = it.name,
                rows = PlaylistRows.build(it.items, byId),
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
) : ViewModel() {

    private val openId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PlaylistsUiState> = combine(
        store.playlists,
        library.state,
        openId,
    ) { playlists, libraryState, open ->
        buildPlaylistsUiState(playlists, libraryState, open)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = PlaylistsUiState(),
    )

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

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
