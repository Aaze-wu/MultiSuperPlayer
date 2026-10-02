package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry

/**
 * 分组标题。
 *
 * 分成「有值」和「未知」两类而不是直接放一个 String，是因为「未知艺术家」这几个字
 * 必须**可翻译**、而且由界面决定怎么显示（有的界面想显示成斜体的「未知艺术家」）。
 * 纯逻辑层里塞一个已经拼好的中文字符串，就等于把翻译绑死在数据层。
 */
internal sealed interface LibraryGroupTitle {
    /** 用户数据（艺术家名、专辑名、文件夹路径），原样显示，不翻译。 */
    data class Text(val value: String) : LibraryGroupTitle

    /** 该字段为空/缺失。显示什么由 [LibraryGroupMode.unknownLabel] 决定。 */
    data object Unknown : LibraryGroupTitle
}

/** 一个分组。 */
internal data class LibraryGroup(
    val key: String,
    val title: LibraryGroupTitle,
    val entries: List<MediaEntry>,
)

/**
 * 列表里的一行。
 *
 * 分组头和条目行放进**同一个**列表，是为了让界面只写一次 `LazyColumn`：
 * 之前项目里踩过的坑正是「列表和空状态是两个平行的 `if`，于是同时画在屏幕上」。
 * 这里同理——如果把「分组头」做成 `LazyColumn` 外面的一层嵌套，滚动、key 复用、
 * 空状态互斥这些事就要在两个地方各写一遍。
 */
internal sealed interface LibraryRow {
    /** 稳定且唯一的 key（条目 id 在整棵树里唯一，所以加前缀即可）。 */
    val key: String

    data class Header(val groupKey: String, val title: LibraryGroupTitle, val count: Int) : LibraryRow {
        override val key: String get() = "h:$groupKey"
    }

    /** [index] 是它在**整个展示顺序**里的下标，直接用作播放队列的起点。 */
    data class Item(val entry: MediaEntry, val index: Int) : LibraryRow {
        override val key: String get() = "i:${entry.id}"
    }
}

/**
 * 排序 + 分组，纯函数。
 *
 * 「不分组」时 [groups] 返回空列表而 [rows] 返回排好序的平铺列表——
 * 这两种返回值的差异被刻意保留：界面在「不分组」时不该去画分组头，
 * 而如果让它返回「一个 key 为 null 的分组」，界面就得再判一次这个 null。
 */
internal object LibraryArrangement {

    /** 按 [mode] 分组并按 [sort] 排序。`NONE` 或空输入返回空列表。 */
    fun groups(
        entries: List<MediaEntry>,
        sort: LibrarySort,
        mode: LibraryGroupMode,
    ): List<LibraryGroup> {
        if (mode == LibraryGroupMode.NONE || entries.isEmpty()) return emptyList()
        val comparator = sort.comparator()

        // 用 LinkedHashMap 记录「第一次出现」的顺序，但最终顺序由 GROUP_ORDER 决定：
        // 保留插入顺序只是为了标题**大小写不同**时展示第一次出现的那个写法。
        val buckets = LinkedHashMap<String, MutableList<MediaEntry>>()
        val titles = HashMap<String, LibraryGroupTitle>()
        entries.forEach { entry ->
            val key = keyOf(entry, mode)
            titles.putIfAbsent(key, titleOf(entry, mode))
            buckets.getOrPut(key) { mutableListOf() } += entry
        }
        return buckets.map { (key, list) ->
            LibraryGroup(key = key, title = titles.getValue(key), entries = list.sortedWith(comparator))
        }.sortedWith(GROUP_ORDER)
    }

    /**
     * 界面要画的行序列。
     *
     * 排序**永远**先生效、再分组；组内顺序也用同一个比较器。组与组之间的顺序固定按
     * 标题升序（未知组最后），**不**跟随 [sort]：否则选一次「名称（降序）」会把所有分组
     * 的顺序也倒过来，而用户点这个菜单时想改的是「组里条目的顺序」。
     */
    fun rows(
        entries: List<MediaEntry>,
        sort: LibrarySort,
        mode: LibraryGroupMode,
    ): List<LibraryRow> {
        val groups = groups(entries, sort, mode)
        if (groups.isEmpty()) {
            return entries.sortedWith(sort.comparator())
                .mapIndexed { index, entry -> LibraryRow.Item(entry, index) }
        }
        var index = 0
        return buildList {
            groups.forEach { group ->
                add(LibraryRow.Header(group.key, group.title, group.entries.size))
                group.entries.forEach { entry ->
                    add(LibraryRow.Item(entry, index))
                    index++
                }
            }
        }
    }

    private fun keyOf(entry: MediaEntry, mode: LibraryGroupMode): String = when (mode) {
        LibraryGroupMode.NONE -> ""
        LibraryGroupMode.ARTIST -> groupKey(entry.artist)
        LibraryGroupMode.ALBUM -> groupKey(entry.album)
        // 文件夹用**完整相对路径**做 key：不同目录下的同名文件夹（`Music/Live` 与
        // `Download/Live`）是两个不同的分组，按最后一段合并会把它们混成一堆。
        LibraryGroupMode.FOLDER -> groupKey(entry.relativePath?.trimEnd('/'))
    }

    private fun titleOf(entry: MediaEntry, mode: LibraryGroupMode): LibraryGroupTitle = when (mode) {
        LibraryGroupMode.NONE -> LibraryGroupTitle.Unknown
        LibraryGroupMode.ARTIST -> groupTitle(entry.artist)
        LibraryGroupMode.ALBUM -> groupTitle(entry.album)
        LibraryGroupMode.FOLDER -> groupTitle(entry.relativePath?.trimEnd('/'))
    }

    private fun groupKey(value: String?): String =
        value?.trim().orEmpty().lowercase().ifEmpty { UNKNOWN_KEY }

    private fun groupTitle(value: String?): LibraryGroupTitle {
        val text = value?.trim().orEmpty()
        return if (text.isEmpty()) LibraryGroupTitle.Unknown else LibraryGroupTitle.Text(text)
    }

    private const val UNKNOWN_KEY = "\u0000unknown"

    /** 未知组永远最后；其余按标题忽略大小写升序，标题相同再按 key（保证全序）。 */
    private val GROUP_ORDER = Comparator<LibraryGroup> { a, b ->
        val aUnknown = a.title is LibraryGroupTitle.Unknown
        val bUnknown = b.title is LibraryGroupTitle.Unknown
        when {
            aUnknown && bUnknown -> a.key.compareTo(b.key)
            aUnknown -> 1
            bUnknown -> -1
            else -> {
                val byTitle = String.CASE_INSENSITIVE_ORDER.compare(a.sortTitle(), b.sortTitle())
                if (byTitle != 0) byTitle else a.key.compareTo(b.key)
            }
        }
    }

    private fun LibraryGroup.sortTitle(): String =
        (title as? LibraryGroupTitle.Text)?.value.orEmpty()
}
