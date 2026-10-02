package com.multisuperplayer.feature.library

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排序比较器的测试。
 *
 * 这里最值得钉住的一条是「未知值排最后」：用 `compareBy { durationMs }` 之类的写法，
 * 0（= 不知道）会被当成「最短 / 最小」而排到最前面，用户点一下「时长（短→长）」
 * 看到的是「一堆时长未知的文件在最上面」。所以未知值必须自己一档。
 */
class LibraryViewOptionsTest {

    private fun entry(
        id: String,
        title: String = id,
        dateAddedSeconds: Long = 0L,
        durationMs: Long = 0L,
        sizeBytes: Long = 0L,
    ) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = MediaKind.AUDIO,
        dateAddedSeconds = dateAddedSeconds,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
    )

    private fun List<MediaEntry>.sortedBy(sort: LibrarySort): List<String> =
        sortedWith(sort.comparator()).map { it.id }

    @Test
    fun `按标题升序且忽略大小写`() {
        val list = listOf(entry("b", "banana"), entry("a", "Apple"), entry("c", "cherry"))
        assertEquals(listOf("a", "b", "c"), list.sortedBy(LibrarySort.TITLE_ASC))
    }

    @Test
    fun `按标题降序`() {
        val list = listOf(entry("b", "banana"), entry("a", "Apple"), entry("c", "cherry"))
        assertEquals(listOf("c", "b", "a"), list.sortedBy(LibrarySort.TITLE_DESC))
    }

    @Test
    fun `同标题时用 id 兜底形成全序`() {
        // 重扫之后输入顺序可能变（合并的顺序取决于两个来源谁先返回）。
        // 没有 id 兜底时，同一标题的两条会跟着输入顺序来回跳。
        val list = listOf(entry("z", "同名"), entry("a", "同名"), entry("m", "同名"))
        assertEquals(listOf("a", "m", "z"), list.sortedBy(LibrarySort.TITLE_ASC))
        assertEquals(
            list.sortedBy(LibrarySort.TITLE_ASC),
            list.reversed().sortedBy(LibrarySort.TITLE_ASC),
        )
    }

    @Test
    fun `空标题退化到 id 而不是全部堆在一起`() {
        val list = listOf(entry("b"), entry("a")).map { it.copy(title = "") }
        assertEquals(listOf("a", "b"), list.sortedBy(LibrarySort.TITLE_ASC))
    }

    @Test
    fun `最新添加在前`() {
        val list = listOf(
            entry("old", dateAddedSeconds = 100),
            entry("new", dateAddedSeconds = 300),
            entry("mid", dateAddedSeconds = 200),
        )
        assertEquals(listOf("new", "mid", "old"), list.sortedBy(LibrarySort.NEWEST))
    }

    @Test
    fun `最早添加在前`() {
        val list = listOf(
            entry("old", dateAddedSeconds = 100),
            entry("new", dateAddedSeconds = 300),
            entry("mid", dateAddedSeconds = 200),
        )
        assertEquals(listOf("old", "mid", "new"), list.sortedBy(LibrarySort.OLDEST))
    }

    @Test
    fun `添加时间未知在两个方向上都排最后`() {
        // 0 是「MediaStore 没给这个字段」，不是「1970 年添加的」。
        // 升序时若把 0 当最小值，这些条目会跑到最前面 —— 正是这条测试要防的。
        val known = entry("known", dateAddedSeconds = 100)
        val unknown = entry("unknown", dateAddedSeconds = 0L)
        val input = listOf(unknown, known)

        assertEquals(listOf("known", "unknown"), input.sortedBy(LibrarySort.OLDEST))
        assertEquals(listOf("known", "unknown"), input.sortedBy(LibrarySort.NEWEST))
    }

    @Test
    fun `时长未知排最后`() {
        val list = listOf(
            entry("short", durationMs = 1_000),
            entry("unknown", durationMs = 0L),
            entry("long", durationMs = 9_000),
        )
        assertEquals(listOf("long", "short", "unknown"), list.sortedBy(LibrarySort.LONGEST))
    }

    @Test
    fun `体积未知排最后`() {
        val list = listOf(
            entry("small", sizeBytes = 10),
            entry("unknown", sizeBytes = 0L),
            entry("big", sizeBytes = 900),
        )
        assertEquals(listOf("big", "small", "unknown"), list.sortedBy(LibrarySort.LARGEST))
    }

    @Test
    fun `未知档内部按标题排`() {
        val list = listOf(
            entry("b", title = "Bee", durationMs = 0L),
            entry("a", title = "Ant", durationMs = 0L),
        )
        // 两条的时长都是未知，于是第二档按标题决定顺序（而不是输入顺序）。
        assertEquals(listOf("a", "b"), list.sortedBy(LibrarySort.LONGEST))
    }

    @Test
    fun `每种排序都能排完整张表且不丢条目`() {
        val list = listOf(
            entry("a", title = "c", dateAddedSeconds = 2, durationMs = 0, sizeBytes = 5),
            entry("b", title = "a", dateAddedSeconds = 0, durationMs = 3, sizeBytes = 0),
            entry("c", title = "b", dateAddedSeconds = 1, durationMs = 7, sizeBytes = 5),
        )
        LibrarySort.entries.forEach { sort ->
            val sorted = list.sortedWith(sort.comparator())
            assertEquals("${sort.name} 丢条目了", 3, sorted.size)
            assertEquals("${sort.name} 出现重复", 3, sorted.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `排序与分组标签都挂着真实资源 id`() {
        // 运行期拿不到 Resources 的单测里最容易漏掉的就是「忘记写文案」：
        // 枚举项一旦漏挂资源，界面会显示成空字符串而不是崩溃，很难被发现。
        // 这里退一步，只保证每个枚举项都挂着**非 0** 的资源 id。
        LibrarySort.entries.forEach { assertTrue(it.name, it.label.hasResource()) }
        LibraryGroupMode.entries.forEach { assertTrue(it.name, it.label.hasResource()) }
        LibraryGroupMode.entries.forEach { assertTrue(it.name, it.unknownLabel.hasResource()) }
    }

    private fun MspText.hasResource(): Boolean = this is MspText.Res && id > 0
}
