package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件浏览器排序/过滤规则的单元测试。
 *
 * 每一条都在防一个**界面上看不出来**的失败：目录混进文件里 ⇒ 用户以为排序坏了；
 * 隐藏开关先截断后过滤 ⇒ 有文件的目录显示成空的；时间未知不兜底 ⇒
 * 「旧→新」把一堆 0 顶到最前面，看起来像正常排序但毫无依据。
 */
class BrowserRulesTest {

    // ------------------------------------------------------------ 目录优先

    @Test
    fun `目录恒排在文件前面，与排序方向无关`() {
        val entries = listOf(file("a.mp4"), dir("z_folder"), file("b.mp4"), dir("a_folder"))

        val asc = BrowserRules.arrange(entries, BrowserSort.NAME_ASC, showHidden = false).entries
        assertEquals(listOf("a_folder", "z_folder", "a.mp4", "b.mp4"), asc.map { it.name })

        // 降序时**只有文件内部**反过来，目录仍然全在前。
        // 如果整个列表一起反转，用户点「名称 Z-A」会看到文件跑到目录前面。
        val desc = BrowserRules.arrange(entries, BrowserSort.NAME_DESC, showHidden = false).entries
        assertEquals(listOf("z_folder", "a_folder", "b.mp4", "a.mp4"), desc.map { it.name })
    }

    @Test
    fun `名称排序忽略大小写`() {
        val entries = listOf(file("gamma.mp4"), file("Beta.mp4"), file("alpha.mp4"))
        assertEquals(
            listOf("alpha.mp4", "Beta.mp4", "gamma.mp4"),
            BrowserRules.arrange(entries, BrowserSort.NAME_ASC, false).entries.map { it.name },
        )
        // 后缀大小写完全不同的一批：`MOVIE.MKV` 这种在存储卡上很常见。
        assertEquals(
            listOf("gamma.mp4", "Beta.mp4", "alpha.mp4"),
            BrowserRules.arrange(entries, BrowserSort.NAME_DESC, false).entries.map { it.name },
        )
    }

    // ------------------------------------------------------------ 时间排序

    @Test
    fun `时间排序把时间未知的条目恒放最后`() {
        val entries = listOf(
            file("old.mp4", time = 1_000L),
            file("unknown.mp4", time = 0L),
            file("new.mp4", time = 9_000L),
        )

        assertEquals(
            listOf("new.mp4", "old.mp4", "unknown.mp4"),
            BrowserRules.arrange(entries, BrowserSort.NEWEST, false).entries.map { it.name },
        )

        // 这一半才是真正需要兜底的地方：按时间值升序时 0 会排到最前面，
        // 于是「最旧在前」的列表头是一堆**不知道什么时候**的文件。
        // 「不知道」既不是最新也不是最旧，只能有一个位置：最后。
        assertEquals(
            listOf("old.mp4", "new.mp4", "unknown.mp4"),
            BrowserRules.arrange(entries, BrowserSort.OLDEST, false).entries.map { it.name },
        )
    }

    @Test
    fun `负数时间也当成未知`() {
        // `File.lastModified()` 在读不到元数据时返回 0，但某些 provider 会给出负数。
        // 判据写成 `== 0L` 的话负数会跑到最前面——所以规则是 `<= 0`。
        val entries = listOf(
            file("real.mp4", time = 500L),
            file("broken.mp4", time = -1L),
        )
        assertEquals(
            listOf("real.mp4", "broken.mp4"),
            BrowserRules.arrange(entries, BrowserSort.OLDEST, false).entries.map { it.name },
        )
    }

    // ------------------------------------------------------------ 隐藏文件

    @Test
    fun `隐藏条目按开关过滤`() {
        val entries = listOf(file(".nomedia"), dir(".hidden"), file("a.mp4"))

        assertEquals(
            listOf("a.mp4"),
            BrowserRules.arrange(entries, BrowserSort.NAME_ASC, showHidden = false).entries.map { it.name },
        )
        // 打开开关时隐藏项是**正常条目**，按同样的规则排序（这里是名称升序）。
        assertEquals(
            listOf(".hidden", ".nomedia", "a.mp4"),
            BrowserRules.arrange(entries, BrowserSort.NAME_ASC, showHidden = true).entries.map { it.name },
        )
    }

    @Test
    fun `截断发生在过滤之后，隐藏条目不会占掉名额`() {
        // 先截断再过滤的后果：名额被隐藏项吃光，用户看到一个「空目录」。
        // 这是本类里最不显眼、也最容易写错的一条。
        val entries = List(MAX_ENTRIES_PER_DIRECTORY) { file(".hidden$it") } + listOf(file("visible.mp4"))

        val arranged = BrowserRules.arrange(entries, BrowserSort.NAME_ASC, showHidden = false)

        assertEquals(listOf("visible.mp4"), arranged.entries.map { it.name })
        assertFalse(arranged.truncated)
    }

    // ------------------------------------------------------------ 上限

    @Test
    fun `超过单目录上限时截断并如实报告`() {
        val entries = List(MAX_ENTRIES_PER_DIRECTORY + 5) { file("f%05d.mp4".format(it)) }

        val arranged = BrowserRules.arrange(entries, BrowserSort.NAME_ASC, showHidden = false)

        assertEquals(MAX_ENTRIES_PER_DIRECTORY, arranged.entries.size)
        assertTrue(arranged.truncated)
        // 留下的是**排好序的前 N 个**，不是碰巧挨着的前 N 个。
        // 如果截断发生在排序之前，这里会看到一串没有规律的编号，
        // 而且每次刷新看到的还不一样（`listFiles` 顺序不保证）。
        assertEquals("f00000.mp4", arranged.entries.first().name)
        assertEquals("f%05d.mp4".format(MAX_ENTRIES_PER_DIRECTORY - 1), arranged.entries.last().name)
    }

    @Test
    fun `恰好等于上限时不算截断`() {
        // 边界差一。报错的方向很讲究：这里报「已截断」会让用户以为漏了文件。
        val entries = List(MAX_ENTRIES_PER_DIRECTORY) { file("f$it.mp4") }
        assertFalse(BrowserRules.arrange(entries, BrowserSort.NAME_ASC, false).truncated)
    }

    // ------------------------------------------------------------ 稳定性

    @Test
    fun `同名条目按 ref 兜底以保证顺序稳定`() {
        val fromB = BrowserEntry(ref = "/b/same.mp4", name = "same.mp4", isDirectory = false)
        val fromA = BrowserEntry(ref = "/a/same.mp4", name = "same.mp4", isDirectory = false)

        // 两种输入顺序必须给出同一个输出顺序，否则 `sortedWith` 的结果取决于
        // `listFiles()` 的返回顺序，界面每次刷新都会「自己抖动」。
        val first = BrowserRules.arrange(listOf(fromB, fromA), BrowserSort.NAME_ASC, false).entries
        val second = BrowserRules.arrange(listOf(fromA, fromB), BrowserSort.NAME_ASC, false).entries

        assertEquals(listOf("/a/same.mp4", "/b/same.mp4"), first.map { it.ref })
        assertEquals(first.map { it.ref }, second.map { it.ref })
    }

    @Test
    fun `空目录返回空结果且不报截断`() {
        val arranged = BrowserRules.arrange(emptyList(), BrowserSort.NAME_ASC, showHidden = false)
        assertTrue(arranged.entries.isEmpty())
        assertFalse(arranged.truncated)
    }

    // ------------------------------------------------------------ 工具

    private fun file(name: String, time: Long = 0L): BrowserEntry = BrowserEntry(
        ref = "/x/$name",
        name = name,
        isDirectory = false,
        lastModifiedMs = time,
        kind = MediaKind.VIDEO,
    )

    private fun dir(name: String): BrowserEntry = BrowserEntry(
        ref = "/x/$name",
        name = name,
        isDirectory = true,
    )
}
