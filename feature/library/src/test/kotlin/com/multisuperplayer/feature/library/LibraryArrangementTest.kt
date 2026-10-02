package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排序 + 分组的纯逻辑测试。
 *
 * 这些断言全部对着「屏幕上从上到下会是什么」来写（分组顺序、组内顺序、行下标），
 * 因为这一层唯一的职责就是回答这个问题。它不碰 Compose，所以能在 JVM 上跑。
 */
class LibraryArrangementTest {

    private fun entry(
        id: String,
        title: String = id,
        artist: String? = null,
        album: String? = null,
        relativePath: String? = null,
    ) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = MediaKind.AUDIO,
        artist = artist,
        album = album,
        relativePath = relativePath,
    )

    private fun titles(groups: List<LibraryGroup>): List<String> = groups.map { group ->
        when (val title = group.title) {
            is LibraryGroupTitle.Text -> title.value
            LibraryGroupTitle.Unknown -> "<未知>"
        }
    }

    private fun ids(rows: List<LibraryRow>): List<String> =
        rows.filterIsInstance<LibraryRow.Item>().map { it.entry.id }

    @Test
    fun `不分组时 groups 为空而 rows 是平铺`() {
        val list = listOf(entry("b", "b"), entry("a", "a"))
        assertTrue(LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.NONE).isEmpty())

        val rows = LibraryArrangement.rows(list, LibrarySort.TITLE_ASC, LibraryGroupMode.NONE)
        assertEquals(listOf("a", "b"), ids(rows))
        assertTrue(rows.all { it is LibraryRow.Item })
    }

    @Test
    fun `空输入给出空行列表`() {
        LibraryGroupMode.entries.forEach { mode ->
            assertTrue(LibraryArrangement.groups(emptyList(), LibrarySort.TITLE_ASC, mode).isEmpty())
            assertTrue(LibraryArrangement.rows(emptyList(), LibrarySort.TITLE_ASC, mode).isEmpty())
        }
    }

    @Test
    fun `按艺术家分组`() {
        val list = listOf(
            entry("q1", artist = "Queen"),
            entry("j1", artist = "周杰伦"),
            entry("q2", artist = "Queen"),
        )
        val groups = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        // 「Queen」在「周杰伦」前面：这是码位序，不是拼音序（已知限制，README 有记）。
        assertEquals(listOf("Queen", "周杰伦"), titles(groups))
        assertEquals(listOf(listOf("q1", "q2"), listOf("j1")), groups.map { g -> g.entries.map { it.id } })
    }

    @Test
    fun `艺术家名忽略大小写与首尾空白地合并`() {
        val list = listOf(
            entry("a", artist = "Queen"),
            entry("b", artist = "queen"),
            entry("c", artist = "  QUEEN  "),
        )
        val groups = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        assertEquals(1, groups.size)
        // 显示第一次出现的写法，而不是小写化之后的形式。
        assertEquals("Queen", titles(groups).single())
        assertEquals(listOf("a", "b", "c"), groups.single().entries.map { it.id })
    }

    @Test
    fun `缺失的艺术家归入未知组且排在最后`() {
        val list = listOf(
            entry("x", artist = null),
            entry("y", artist = "  "),
            entry("z", artist = "Adele"),
        )
        val groups = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        assertEquals(listOf("Adele", "<未知>"), titles(groups))
        assertEquals(listOf("x", "y"), groups.last().entries.map { it.id })
    }

    @Test
    fun `按专辑分组`() {
        val list = listOf(
            entry("a", album = "B"),
            entry("b", album = null),
            entry("c", album = "A"),
        )
        val groups = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ALBUM)
        assertEquals(listOf("A", "B", "<未知>"), titles(groups))
        assertEquals(listOf("b"), groups.last().entries.map { it.id })
    }

    @Test
    fun `按文件夹分组保留完整路径`() {
        // 只取最后一段的话，`Music/Live` 和 `Download/Live` 会被合并成一个「Live」，
        // 用户看到的是两个来源的内容混在一起。
        val list = listOf(
            entry("a", relativePath = "Music/Live/"),
            entry("b", relativePath = "Download/Live/"),
            entry("c", relativePath = "Music/Live"),
        )
        val groups = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.FOLDER)
        assertEquals(listOf("Download/Live", "Music/Live"), titles(groups))
        // 带尾部斜杠的那个和没带的是同一个目录。
        assertEquals(listOf("a", "c"), groups.last().entries.map { it.id })
    }

    @Test
    fun `没有相对路径的条目归入未知文件夹`() {
        val list = listOf(entry("a", relativePath = null), entry("b", relativePath = "Music/"))
        val groups = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.FOLDER)
        assertEquals(listOf("Music", "<未知>"), titles(groups))
    }

    @Test
    fun `组内条目按排序方式排`() {
        val list = listOf(
            entry("a2", title = "b", artist = "Queen"),
            entry("a1", title = "a", artist = "Queen"),
        )
        val asc = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        assertEquals(listOf("a1", "a2"), asc.single().entries.map { it.id })
        val desc = LibraryArrangement.groups(list, LibrarySort.TITLE_DESC, LibraryGroupMode.ARTIST)
        assertEquals(listOf("a2", "a1"), desc.single().entries.map { it.id })
    }

    @Test
    fun `组的顺序不随排序方式改变`() {
        // 排序菜单管的是「组内条目的顺序」。如果组的顺序也跟着倒过来，
        // 用户点一次「名称（降序）」会发现整个页面的大结构也变了。
        val list = listOf(
            entry("q", title = "z", artist = "Queen"),
            entry("j", title = "a", artist = "Jay"),
        )
        val byTitle = LibraryArrangement.groups(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        val byNewest = LibraryArrangement.groups(list, LibrarySort.NEWEST, LibraryGroupMode.ARTIST)
        assertEquals(titles(byTitle), titles(byNewest))
    }

    @Test
    fun `rows 的分组头带条目数`() {
        val list = listOf(
            entry("q1", artist = "Queen"),
            entry("q2", artist = "Queen"),
            entry("j1", artist = "Jay"),
        )
        val rows = LibraryArrangement.rows(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        val headers = rows.filterIsInstance<LibraryRow.Header>()
        // Jay 一组（1 条）、Queen 一组（2 条）：组按标题升序，所以数量是 1、2。
        assertEquals(listOf(1, 2), headers.map { it.count })
        assertEquals(listOf("h:jay", "h:queen"), headers.map { it.key })
    }

    @Test
    fun `rows 的 item 下标是跨组连续的全局下标`() {
        // 这个下标直接当播放队列的起点用。如果它按「组内下标」算，
        // 点第二组的第二首歌会从第一组的第二首开始播。
        val list = listOf(
            entry("j1", title = "a", artist = "Jay"),
            entry("j2", title = "b", artist = "Jay"),
            entry("q1", title = "c", artist = "Queen"),
            entry("q2", title = "d", artist = "Queen"),
        )
        val rows = LibraryArrangement.rows(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        val items = rows.filterIsInstance<LibraryRow.Item>()
        assertEquals(listOf(0, 1, 2, 3), items.map { it.index })
        assertEquals(listOf("j1", "j2", "q1", "q2"), items.map { it.entry.id })
        // 行序列的形状：头, 条目, 条目, 头, 条目, 条目
        assertEquals(
            listOf("Header", "Item", "Item", "Header", "Item", "Item"),
            rows.map { it::class.simpleName },
        )
    }

    @Test
    fun `行的 key 稳定且不重复`() {
        val list = listOf(
            entry("q1", artist = "Queen"),
            entry("q2", artist = "Queen"),
            entry("j1", artist = "Jay"),
        )
        val rows = LibraryArrangement.rows(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST)
        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        // 条目 id 和分组 key 都在同一命名空间里，前缀保证两者不会撞。
        assertTrue(keys.contains("i:q1"))
        assertTrue(keys.contains("h:queen"))
    }

    @Test
    fun `同一份数据无论输入顺序如何都得到同样的行序列`() {
        val list = listOf(
            entry("b", title = "b", artist = "Queen"),
            entry("a", title = "a", artist = "Queen"),
            entry("c", title = "c", artist = "Jay"),
        )
        assertEquals(
            LibraryArrangement.rows(list, LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST).map { it.key },
            LibraryArrangement.rows(list.reversed(), LibrarySort.TITLE_ASC, LibraryGroupMode.ARTIST).map { it.key },
        )
    }
}
