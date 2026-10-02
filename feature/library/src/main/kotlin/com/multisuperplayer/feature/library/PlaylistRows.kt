package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.PlaylistItem

/**
 * 播放列表详情里的一行。
 *
 * [entry] 是**当前库里**能解出来的条目；解不出来时是 null，但这一行仍然照常显示——
 * [PlaylistItem] 自带标题/艺术家/时长快照，正是为了这种情况（见 `Playlist.kt` 里
 * 那段「为什么 id 之外还要存一份显示快照」）。
 */
data class PlaylistRow(
    val item: PlaylistItem,
    val entry: MediaEntry?,
) {
    /** 库里已经找不到这条了。界面据此显示「文件已不在」。 */
    val missing: Boolean get() = entry == null

    /**
     * 用来画这一行的条目：以库为准，查不到时退成快照拼出来的展示用条目。
     *
     * 快照里没有专辑/路径这些字段，所以降级后的副标题会短一些——这是对的，
     * 凭空显示一个「未知专辑」比少显示一行更像故障。
     */
    val display: MediaEntry get() = entry ?: item.toEntry()
}

internal object PlaylistRows {

    fun build(items: List<PlaylistItem>, byId: Map<String, MediaEntry>): List<PlaylistRow> =
        items.map { item -> PlaylistRow(item = item, entry = byId[item.mediaId]) }

    /**
     * 可播放队列，顺序与播放列表一致。
     *
     * ## 为什么失效的条目也留在队列里
     *
     * 两条路都想过，选「留下」的理由：
     *
     * 1. **队列长度必须等于列表长度**，否则「点第 3 行」和「队列里第 3 个」对不上；
     *    而且错位只在「前面恰好有一条失效」时才出现，是最容易漏的那种 bug。
     * 2. 库查不到不等于放不出来。最常见的一种是「存储权限被回收了、文件其实还在」，
     *    这时快照里的内容 uri 仍然有效（`PlaylistItem.toEntry()` 就是为此存在的）。
     *    一律标成不可播，等于把还能用的条目也一起判了死刑。
     *
     * 代价是：真放不出来时错误要等播放器报。所以界面上给失效的行标了「文件已不在」，
     * 让用户在点之前就知道这条可能有问题。
     */
    fun queue(items: List<PlaylistItem>, byId: Map<String, MediaEntry>): List<MediaEntry> =
        items.map { item -> byId[item.mediaId] ?: item.toEntry() }
}
