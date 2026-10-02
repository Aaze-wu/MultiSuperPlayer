package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.PlaylistItem

/**
 * 播放列表的纯规则。放在这里而不是塞进 store，是因为它们每一条都**很容易写反**，
 * 而写反之后界面上表现正常（列表还是列表），只是「加两次变成两行」
 * 或者「删一条删掉了两条」。
 */
internal object PlaylistRules {

    /** 名字长度上限。取 80：比这更长在列表里会被截断，用户也写不出这么长的名字。 */
    const val MAX_NAME_LENGTH = 80

    /** 一个播放列表最多的条目数。 */
    const val MAX_ITEMS = 5_000

    /** 最多几个播放列表。 */
    const val MAX_PLAYLISTS = 100

    /**
     * 名字清洗：去掉首尾空白并截断长度。
     *
     * 不做「空了就改成『未命名』」——那是一个**面向用户的默认值**，
     * 属于界面层的文案（要能翻译），数据层返回空串让调用方自己决定。
     */
    fun sanitizeName(raw: String): String = raw.trim().take(MAX_NAME_LENGTH)

    /**
     * 追加条目，返回新的条目列表（原来的顺序不变，新的接在后面）。
     *
     * 三件事必须一起做对：
     * - **按 [PlaylistItem.mediaId] 去重**，包括 [incoming] 内部的重复：
     *   「全部选中 → 加入播放列表」这种操作里，同一个文件很容易出现两次。
     * - 已经在列表里的条目**不更新**：它带着上次的快照，而快照的来源可能是
     *   另一个来源（比如从 SAF 加入、后来从 MediaStore 也能看到）。
     *   静默替换会让「我在播放列表里看到的名字」莫名其妙地变。
     * - 超出 [MAX_ITEMS] 的部分**丢弃**（而不是让列表无限长下去）。
     */
    fun withAdded(existing: List<PlaylistItem>, incoming: List<PlaylistItem>): List<PlaylistItem> {
        if (incoming.isEmpty()) return existing
        val known = existing.mapTo(HashSet()) { it.mediaId }
        val appended = ArrayList<PlaylistItem>(incoming.size)
        for (item in incoming) {
            if (item.mediaId.isBlank()) continue
            if (!known.add(item.mediaId)) continue
            appended += item
            if (existing.size + appended.size >= MAX_ITEMS) break
        }
        if (appended.isEmpty()) return existing
        return existing + appended
    }

    /**
     * 从 [from] 移到 [to]（都是当前列表里的下标）。
     *
     * 界面上拿到的下标可能已经过期（列表在拖动期间被别处改了），
     * 所以越界时**原样返回**而不是抛异常或 clamp——clamp 到一个「看起来很近」
     * 的位置会让条目跑到用户没指定的地方。
     */
    fun move(items: List<PlaylistItem>, from: Int, to: Int): List<PlaylistItem> {
        if (from == to) return items
        if (from !in items.indices || to !in items.indices) return items
        val result = items.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }

    /** 按 id 删条目；不存在的 id 直接忽略。 */
    fun withRemoved(items: List<PlaylistItem>, mediaIds: Collection<String>): List<PlaylistItem> {
        if (mediaIds.isEmpty() || items.isEmpty()) return items
        val removing = mediaIds.toHashSet()
        val result = items.filterNot { it.mediaId in removing }
        // 没有真的删掉东西时返回原对象，方便调用方按 `!==` 判断「不用写盘了」。
        return if (result.size == items.size) items else result
    }

    /**
     * 按当前媒体库解析每条快照，**按照传入的顺序**返回。
     *
     * [Playlist.items] 的顺序就是用户排的顺序，这里只做「换成最新元数据」，
     * 不排序、不分组。
     */
    fun resolve(items: List<PlaylistItem>, byId: Map<String, MediaEntry>): List<PlaylistItem> =
        items.map { it.resolvedBy(byId) }

    /** 列表里显示用的名字：空名字给一个能翻译的兜底由界面提供，这里只负责判断。 */
    fun hasUsableName(playlist: Playlist): Boolean = playlist.name.isNotBlank()
}
