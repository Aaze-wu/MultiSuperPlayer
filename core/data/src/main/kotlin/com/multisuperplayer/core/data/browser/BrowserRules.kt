package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.BrowserEntry

/**
 * 单层目录最多显示多少条。
 *
 * 这个上限**不是**为了省内存（一行就是一个小对象），而是因为 `listFiles()` /
 * provider 查询是**同步展开**的：`.thumbnails`、`WhatsApp/Media` 这类位置
 * 一层几万个文件真的存在，全量展开会让进入目录的那一下卡住好几秒。
 *
 * 截断在**排序之后**做（见 [BrowserRules.arrange]）：先截断再排序，
 * 保留哪一批就取决于 provider 的返回顺序——那是个不该被界面依赖的东西。
 */
internal const val MAX_ENTRIES_PER_DIRECTORY = 5_000

/**
 * 文件浏览器的显示规则：过滤与排序。
 *
 * ## 为什么单独抽成一个纯对象
 *
 * 三个来源里，只有 `FileSystemSource` 能在 JVM 单测里真跑（`java.io.File`
 * 是纯 JDK）；`SafDocumentSource` 要 `Context` + `DocumentsContract`，
 * 在单测里只能拿到 `Method ... not mocked`。
 *
 * 于是「目录排前面、隐藏文件怎么处理、时间未知的排哪儿」这些**唯一会被用户看见**
 * 的规则，如果写在 source 里面，就等于一行都测不到——而它们恰恰是最容易写错、
 * 出错了又最不报错的部分（顺序错了没人会发现）。放在这里就能一行一行地测。
 */
internal object BrowserRules {

    /**
     * 名称排序用的序。用 `CASE_INSENSITIVE_ORDER` 而不是 `lowercase()` 比较：
     * 后者每比一次就分配一个字符串，几千行的列表上是实打实的开销。
     */
    private val NAME_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER

    /**
     * 目录永远排在文件前面。
     *
     * 这条**先于**任何用户选的排序生效，且不受 A→Z / Z→A 影响：
     * 文件管理器里目录和文件混排是反直觉的。代价是「Z→A」时目录块内部反向、
     * 但目录块整体仍在最前——这正是想要的。
     */
    private val DIRECTORY_FIRST: Comparator<BrowserEntry> =
        compareBy<BrowserEntry> { if (it.isDirectory) 0 else 1 }

    /**
     * 过滤隐藏项 + 排序 + 按 [MAX_ENTRIES_PER_DIRECTORY] 截断。
     *
     * 三步必须在同一个函数里、按这个顺序做完：分散到调用点就会出现
     * 「截断发生在过滤之前」（隐藏文件把名额占光）这种只在特定目录复现的 bug。
     */
    fun arrange(
        entries: List<BrowserEntry>,
        sort: BrowserSort,
        showHidden: Boolean,
    ): Arranged {
        val kept = entries
            .asSequence()
            .filter { showHidden || !it.hidden }
            .sortedWith(comparator(sort))
            .toList()
        return if (kept.size <= MAX_ENTRIES_PER_DIRECTORY) {
            Arranged(kept, truncated = false)
        } else {
            Arranged(kept.subList(0, MAX_ENTRIES_PER_DIRECTORY).toList(), truncated = true)
        }
    }

    /** 完整排序规则：目录优先，然后按 [sort]。 */
    fun comparator(sort: BrowserSort): Comparator<BrowserEntry> =
        DIRECTORY_FIRST.then(bySort(sort))

    private fun bySort(sort: BrowserSort): Comparator<BrowserEntry> = when (sort) {
        BrowserSort.NAME_ASC -> byName(ascending = true)
        BrowserSort.NAME_DESC -> byName(ascending = false)
        BrowserSort.NEWEST -> byTime(newestFirst = true)
        BrowserSort.OLDEST -> byTime(newestFirst = false)
    }

    private fun byName(ascending: Boolean): Comparator<BrowserEntry> {
        val order = if (ascending) NAME_ORDER else NAME_ORDER.reversed()
        // 同名时按 ref 兜底：两个来源可能给出同名条目（如 `.nomedia` 之外的重名），
        // 没有兜底键的话 `sortedWith` 的顺序取决于输入顺序，界面会「自己抖动」。
        return compareBy<BrowserEntry, String>(order) { it.name }.thenBy { it.ref }
    }

    private fun byTime(newestFirst: Boolean): Comparator<BrowserEntry> {
        // `compareBy` / `compareByDescending` 的**单参数**重载只吃一个类型参数（`<T>`），
        // 写 `<BrowserEntry, Long>` 会编译不过（那个两参数重载要求再传一个 comparator）。
        // 所以这里靠变量的显式类型把 `it` 推成 BrowserEntry，而不是靠类型实参。
        val time: Comparator<BrowserEntry> = if (newestFirst) {
            compareByDescending { it.lastModifiedMs }
        } else {
            compareBy { it.lastModifiedMs }
        }
        // 「时间未知」（`lastModifiedMs <= 0`）恒排最后，与升降序无关。
        // 这一档必须显式处理：SAF provider 完全可以不返回 `LAST_MODIFIED`，
        // 那时值是 0，在新→旧里会沉底、在旧→新里会**顶到最前面**，
        // 于是同一批「无时间」的文件在两个排序下都觉得很正常，但顺序毫无依据。
        return compareBy<BrowserEntry> { it.lastModifiedMs <= 0L }
            .then(time)
            .thenBy { it.name.lowercase() }
            .thenBy { it.ref }
    }

    /** [arrange] 的结果：排好序的条目，以及有没有被截断。 */
    data class Arranged(val entries: List<BrowserEntry>, val truncated: Boolean)
}
