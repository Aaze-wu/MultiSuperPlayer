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
}
