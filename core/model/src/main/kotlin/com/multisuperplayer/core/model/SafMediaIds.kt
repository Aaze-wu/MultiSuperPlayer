package com.multisuperplayer.core.model

/**
 * SAF 条目的 id 前缀，以及它和 `documentId` 之间的换算。
 *
 * ## 为什么这个常量必须住在 `core:model`
 *
 * 造 id 的一侧在 `core:data`（`SafScanRules.safIdOf`，扫描时用），
 * 而**读**它的一侧在 `core:model`（`PlaylistItem.sourceOf`，回放时反推来源）。
 * 常量原本只存在于 `core:data`，于是 `core:model` 这一侧只能凭空猜——它猜的是
 * 「`saf:` 开头的也算媒体库」，结果是 SAF 条目回放时去找 `MediaStore` 的
 * `RELATIVE_PATH` 目录，同目录字幕一条都找不到（v1.2.1 修过同一个症状的
 * `file:` 版本，v1.2.4 补上这一半）。
 *
 * `core:model` 在这条依赖链的最下游（`core:data` 依赖它，反向不成立），
 * 所以唯一的出路是把常量放在这里，让两侧都引用同一个值：
 * `SafScanRules.SAF_ID_PREFIX` 现在是 [PREFIX] 的别名。
 *
 * ## id 的形态
 *
 * `"saf:" + documentId`，例如 `saf:primary:Music/a.flac`。
 *
 * 用 `documentId` 而不是「树 uri + 相对路径」的理由见 `SafScanRules.safIdOf`；
 * 这里只需要保证**前缀的判断和剥离**只有一处实现。
 */
object SafMediaIds {

    /** SAF 条目在 [MediaEntry.id] 上的前缀。 */
    const val PREFIX = "saf:"

    /** [documentId] 对应的 [MediaEntry.id]。 */
    fun idOf(documentId: String): String = "$PREFIX$documentId"

    /** 这个 [MediaEntry.id] 是 SAF 扫出来的吗。 */
    fun isSafId(id: String): Boolean = id.startsWith(PREFIX)

    /** [idOf] 的逆运算：把前缀去掉，拿回 `documentId`。 */
    fun documentIdOf(id: String): String = id.removePrefix(PREFIX)
}
