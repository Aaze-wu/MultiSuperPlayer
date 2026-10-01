package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind

/**
 * 媒体库的筛选维度。
 *
 * 只做「类型」这一维，不做「按专辑/艺术家分组」——分组是列表的*展示方式*，
 * 与筛选是两个正交的东西，混在一个枚举里会让后面加分组时被迫再加一层。
 * 分组放到后续的 `groupBy` 里做。
 */
enum class LibraryFilter(val label: String) {
    ALL("全部"),
    AUDIO("音乐"),
    VIDEO("视频"),
    ;

    fun accepts(entry: MediaEntry): Boolean = when (this) {
        ALL -> true
        AUDIO -> entry.kind == MediaKind.AUDIO
        VIDEO -> entry.kind == MediaKind.VIDEO
    }
}
