package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry

/**
 * 关键词匹配。
 *
 * 抽成顶层的 `internal` 函数而不是藏在 ViewModel 的伴生对象里，是为了能被单测直接调用：
 * 「搜不到」是用户最容易归咎于「这 App 的搜索坏了」的问题，而它只由这一个函数决定。
 */
internal fun MediaEntry.matchesQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return title.contains(q, ignoreCase = true) ||
        artist?.contains(q, ignoreCase = true) == true ||
        album?.contains(q, ignoreCase = true) == true ||
        displayName?.contains(q, ignoreCase = true) == true
}

/**
 * 按关键词筛一遍。
 *
 * 注意**不**匹配专辑艺术家：`albumArtist` 与 `artist` 在合辑里往往不同，
 * 两个都匹配会让「按歌手搜」返回一堆合辑，用户会疑惑为什么朴树的歌里混进了别人的。
 */
internal fun List<MediaEntry>.filterByQuery(query: String): List<MediaEntry> =
    if (query.isBlank()) this else filter { it.matchesQuery(query) }
