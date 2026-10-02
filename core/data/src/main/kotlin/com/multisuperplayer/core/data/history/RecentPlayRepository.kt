package com.multisuperplayer.core.data.history

import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.RecentPlay
import com.multisuperplayer.core.player.PlaybackPositionStore

/**
 * 「最近播放」页的数据来源。
 *
 * ## 为什么是一次性的 suspend 而不是 Flow
 *
 * 数据的两个来源变化频率和触发方式完全不同：媒体库是 `Flow`（扫描/权限变化会推），
 * 续播记录是**每次写入都会变**（播放中每秒写好几次）的存储。把两者拼成一个 Flow
 * 就等于让「最近播放」页在播放时每秒重组一次列表。
 *
 * 而这一页的语义本来就是**快照**：用户进入这一页时看到「我最近听了什么」，
 * 不需要在停留期间实时刷新。所以这里是「进来读一次」，由界面在合适的时机
 * （首次进入、从后台回到前台、播放结束后）调用。
 *
 * 也没有用缓存：一次读全表 + 一次字典查找，比起「缓存什么时候失效」这件事
 * 便宜得多，而缓存失效写错的表现是「最近播放里有已经不存在的歌」。
 *
 * ## 删除和清空为什么是「删记录」而不是「从列表里拿掉一行」
 *
 * 这一页的每一行都是从一条续播记录投影出来的（[RecentPlayRules.project]），
 * 而那条记录同时就是「下次从这个位置接着播」的依据。所以这里的 [remove] /
 * [clearAll] 删的是**记录本身**，顺带也就把续播位置一起删了。这不是副作用
 * 而是唯一说得通的设计：用户要的是「我从来没在这台设备上听过它」，留着一个
 * 看不见的位置只会在下次播放时冒出来（从中间开始播，而列表里又没有它）。
 *
 * 代价是界面文案必须把这件事说出来（「续播位置也会一起忘掉」），
 * 不能让用户以为只是把一行从屏幕上抹掉。
 */
class RecentPlayRepository(
    private val positionStore: PlaybackPositionStore,
    private val library: MediaLibraryRepository,
) {

    /**
     * 读出最近播放列表。
     *
     * 媒体库还没扫完（[MediaLibraryState.Loading] / 没权限）时返回空列表——
     * 这一页此时本来就无事可做，等界面下一次刷新即可。
     */
    suspend fun recent(limit: Int = RecentPlayRules.DEFAULT_LIMIT): List<RecentPlay> {
        val entries = (library.state.value as? MediaLibraryState.Ready)?.entries ?: return emptyList()
        return RecentPlayRules.project(positionStore.readAll(), entries, limit)
    }

    /**
     * 忘掉一个媒体的记录（位置、时间、「播过」一起）。
     *
     * 参数是整行而不是媒体 id：调用方（最近播放页）手上本来就有这一行，
     * 而写成 `row` 之后「删这一条」这个意思不用再解释一遍——传 id 的写法
     * 会让调用点出现 `delete(row.entry.id)` 这种看起来像是「删 entry」的表达式。
     */
    suspend fun remove(row: RecentPlay) = positionStore.remove(row.entry.id)

    /** 忘掉全部记录。连当前列表里没显示出来的那些也一起（见 [PlaybackPositionStore.clearAll]）。 */
    suspend fun clearAll() = positionStore.clearAll()

    /**
     * 把一行原样写回，位置和时间戳都不变——「删除」旁边那个「撤销」。
     *
     * 不能改用 [PlaybackPositionStore.write]（它会盖上新时间戳）：这一页按时间戳
     * 倒序排，撤销之后那一行就会跳到最上面。见 [RecentPlayRules.recordOf]。
     */
    suspend fun restore(row: RecentPlay) = positionStore.restore(RecentPlayRules.recordOf(row))
}
