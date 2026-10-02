package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.BrowserEntry
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
    /** 现在找不到这条了（媒体库里没有，且浏览页条目的路径也不在）。界面据此显示「文件已不在」。 */
    val missing: Boolean get() = entry == null

    /**
     * 用来画这一行的条目：以库为准，查不到时退成快照拼出来的展示用条目。
     *
     * 快照里没有专辑/路径这些字段，所以降级后的副标题会短一些——这是对的，
     * 凭空显示一个「未知专辑」比少显示一行更像故障。
     */
    val display: MediaEntry get() = entry ?: item.toEntry()
}

/**
 * 把这个条目解成「现在还能用的媒体条目」；`null` 表示确实找不到了。
 *
 * ## 两条路
 *
 * - **媒体库条目**（MediaStore 数字 id、`saf:` 前缀）：只可能在库里，查不到就是真的没了。
 * - **浏览页条目**（`file:` 前缀，见 `BrowserEntry.MEDIA_ID_PREFIX`）：`byId` 里**永远
 *   不会有**它们——内置文件选择器的定位就是「MediaStore 看不见的地方」（`Download/`
 *   根、内部存储根、带 `.nomedia` 的目录），扫库时一条都不会进来。
 *
 * 第二点曾经在真机上表现为：从浏览器加进播放列表的两条，进详情页立刻被标成
 * 「文件已不在」，顶部还挂一条「其中 2 条文件已不在，播放时可能失败」——而文件
 * 就在 `/sdcard/Movies` 里没动过。判据用错了表（拿媒体库当「存在」的裁判），
 * 而不是判定逻辑算错了。
 *
 * 浏览页条目的真相在 [PlaylistItem.uri] 里：写进播放列表时它就是那个文件的绝对路径
 * （见 `PlaylistItem.of`），所以这里问一句「路径还在不在」。探针以参数传进来，
 * 判断本身保持是纯函数（单测不用碰文件系统）。
 */
internal fun resolvePlaylistItem(
    item: PlaylistItem,
    byId: Map<String, MediaEntry>,
    fileExists: (String) -> Boolean,
): MediaEntry? {
    byId[item.mediaId]?.let { return it }
    if (!BrowserEntry.isBrowserMediaId(item.mediaId)) return null
    if (!fileExists(item.uri)) return null
    // 快照拼出来的条目：uri 还是那个路径，source 由 id 前缀推出 FILE_SYSTEM
    // （见 `PlaylistItem.toEntry`），所以它既能播，也能按路径找到同目录字幕。
    return item.toEntry()
}

internal object PlaylistRows {

    /**
     * @param fileExists 浏览页条目的「路径还在不在」，探针要放在 IO 线程上（见
     *   `PlaylistsViewModel`）。默认值故意选**坏的那一边**（一律当作不在）：忘了传
     *   探针最多多标几条「文件已不在」，而反过来会把已经删掉的文件说成还能播。
     */
    fun build(
        items: List<PlaylistItem>,
        byId: Map<String, MediaEntry>,
        fileExists: (String) -> Boolean = { false },
    ): List<PlaylistRow> = items.map { item ->
        PlaylistRow(item = item, entry = resolvePlaylistItem(item, byId, fileExists))
    }

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
