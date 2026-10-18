package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.SafMediaIds

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
    /** 现在找不到这条了（媒体库里没有，且浏览页条目的 uri 也读不到）。界面据此显示「文件已不在」。 */
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
 * - **媒体库条目**（MediaStore 数字 id）：只可能在库里，查不到就是真的没了。
 * - **自带 uri 的条目**（浏览页的 `file:` 前缀、SAF 扫描的 `saf:` 前缀）：
 *   `PlaylistItem.uri` 里写着那个文件在哪，于是可以反过来问一句「它还在不在」。
 *
 * 第二条曾经在真机上表现为：从浏览器加进播放列表的两条，进详情页立刻被标成
 * 「文件已不在」，顶部还挂一条「其中 2 条文件已不在，播放时可能失败」——而文件
 * 就在 `/sdcard/Movies` 里没动过。判据用错了表（拿媒体库当「存在」的裁判），
 * 而不是判定逻辑算错了。
 *
 * ## `saf:` 为什么也得走第二条（v1.2.4）
 *
 * SAF 条目确实是从库里扫出来的，按说 `byId` 一定有。但**去重会把它扔掉**：
 * `LibraryMergeRules` 遇到「同一个文件既在 MediaStore 里、又在授权的 SAF 目录里」
 * 时保留 MediaStore 那条（它的 artist/album/duration 更全），SAF 那条不进库。
 * 于是播放列表里存着的 `saf:` id 永远查不到——在用户眼里就是「我明明授权了那个
 * 文件夹，这条却说我文件不在」。这类条目手里有完整的 document uri，问一句就知道
 * 真相，没有理由不去问。
 *
 * MediaStore 的数字 id 不在其中：它代表的是**索引里的一行**，不是某个位置的文件，
 * 拿 uri 去验它等于把「库里没有」偷偷改成「文件还在」，而筛掉它的正是索引本身。
 *
 * ## 为什么不能自己判断「这个 uri 还能不能读」
 *
 * 同一个问题在 v1.2.4 上换了个面目又出现一次：从**系统文件选择器添加的文件夹**里
 * 加进来的浏览页条目，uri 是 `content://…/tree/…/document/…`，而当时的探针是
 * 一句 `File(path).exists()`——对任何 content uri **必然**返回 false，于是整批
 * 条目又被标成「文件已不在」。判据错在**探针的实现**，不在这一层的逻辑，所以
 * 修法是把探针换成按 scheme 分路的那个（`MediaUriProbe`），而不是在这里加 `if`。
 * 这一层继续只管「库里有没有 / 探针怎么说」。
 */
internal fun resolvePlaylistItem(
    item: PlaylistItem,
    byId: Map<String, MediaEntry>,
    uriExists: (String) -> Boolean,
): MediaEntry? {
    byId[item.mediaId]?.let { return it }
    if (!hasOwnUri(item.mediaId)) return null
    if (!uriExists(item.uri)) return null
    // 快照拼出来的条目：uri 还是那个路径 / 那个 document uri，source 由 id 前缀推出
    // （`file:` + `content://` ⇒ SAF_TREE，`saf:` ⇒ SAF_TREE，见 `PlaylistItem.sourceOf`），
    // 所以它既能播，也能按正确的来源找到同目录字幕。
    return item.toEntry()
}

/**
 * 这个 id 背后的文件是不是「自己就能说清在哪」。
 *
 * 判据是 id 的**前缀**，和 `PlaylistItem.sourceOf` 用同一套词汇：浏览页条目和
 * SAF 条目都是「一个位置上的文件」，MediaStore 的 id 是「索引里的一行」。
 */
private fun hasOwnUri(mediaId: String): Boolean =
    BrowserEntry.isBrowserMediaId(mediaId) || SafMediaIds.isSafId(mediaId)

internal object PlaylistRows {

    /**
     * @param uriExists 浏览页条目的「uri 还在不在」，探针要放在 IO 线程上（见
     *   `PlaylistsViewModel`）。默认值故意选**坏的那一边**（一律当作不在）：忘了传
     *   探针最多多标几条「文件已不在」，而反过来会把已经删掉的文件说成还能播。
     */
    fun build(
        items: List<PlaylistItem>,
        byId: Map<String, MediaEntry>,
        uriExists: (String) -> Boolean = { false },
    ): List<PlaylistRow> = items.map { item ->
        PlaylistRow(item = item, entry = resolvePlaylistItem(item, byId, uriExists))
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
