package com.multisuperplayer.feature.library

import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import kotlinx.coroutines.flow.Flow

/**
 * 「把一批选中的条目写进播放列表」。
 *
 * 媒体库页和浏览页都要做这件事，而且两处**必须答得一样**的三个问题都在这里：
 *
 * 1. **有几项已经在列表里**——去重发生在存储层（`PlaylistRules.withAdded`），
 *    所以只有写完才知道，调用方照着 `added < requested` 报「跳过几项」。
 * 2. **新建的列表真的落盘了吗**——数量到达上限时 `PlaylistStore.create()`
 *    什么也不写，但**照样返回一个 id**；直接拿它去 `addItems` 会静默地加进一个
 *    不存在的列表，用户看到的是「已加入 0 项」。所以要回读一次。
 * 3. **同名列表 + 空列表名**这类校验在界面层做（对话框的确定按钮），
 *    这里不重复一遍——两份校验迟早会不一致。
 *
 * 抄第二份的代价不是多几行，而是这三条以后只会在一处被修。
 *
 * 这个类是 `internal` 的：它只服务于本模块的 ViewModel，
 * 而两个 ViewModel 的构造参数仍然是各自的依赖（`PlaylistStore`），
 * 不是「一个内部助手类」——Koin 的 `viewModelOf` 解析的是那些公开依赖。
 */
internal class PlaylistEditing(private val store: PlaylistStore) {

    /** 可供「加入播放列表」选择的列表清单。 */
    val playlists: Flow<List<Playlist>> get() = store.playlists

    /** 追加到已有播放列表，返回实际加入了几条（已去重）。 */
    suspend fun addTo(playlistId: String, entries: List<MediaEntry>): Int =
        store.addItems(playlistId, entries)

    /**
     * 新建一个播放列表并把 [entries] 放进去，返回实际加入了几条。
     *
     * 新建没落盘时返回 `null`——调用方必须把这种情况**说出来**（「新建失败」），
     * 不能当成「加入了 0 项」：那两句话对用户的手势完全不同。
     */
    suspend fun createWith(name: String, entries: List<MediaEntry>): Int? {
        val id = store.create(name)
        if (store.playlist(id) == null) {
            MspLog.w(TAG) { "新建播放列表没有落盘（可能已达上限）" }
            return null
        }
        return store.addItems(id, entries)
    }

    private companion object {
        const val TAG = "PlaylistEditing"
    }
}

/**
 * 「已加入 N 项」/「已加入 N 项，另有 M 项已经在列表里」。
 *
 * 两句话都必须有：只说「已加入 3 项」的话，用户对同一个列表点两次会以为真的加了 6 项。
 */
internal fun addedToPlaylistText(added: Int, requested: Int): MspText {
    val skipped = requested - added
    return if (skipped > 0) {
        MspText.Res(R.string.msp_library_added_to_playlist_skipped, added, skipped)
    } else {
        MspText.Res(R.string.msp_library_added_to_playlist, added)
    }
}
