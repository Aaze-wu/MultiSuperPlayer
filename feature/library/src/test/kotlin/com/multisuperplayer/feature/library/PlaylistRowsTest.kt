package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import com.multisuperplayer.core.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PlaylistRows` 的测试。
 *
 * 这个类存在的唯一理由是「列表下标 == 队列下标」。一旦这里出问题，用户点第 3 行
 * 会播第 4 首——一个不会抛异常、只会让人觉得播放器错乱的缺陷。所以每条规则
 * 都单独钉住，尤其是查不到的那一条。
 */
class PlaylistRowsTest {

    private fun entry(id: String, title: String = id, kind: MediaKind = MediaKind.AUDIO) =
        MediaEntry(
            id = id,
            uri = "content://media/$id",
            title = title,
            kind = kind,
        )

    private fun item(id: String, title: String = id) = PlaylistItem.of(entry(id, title))

    @Test
    fun `能解析的条目直接指向媒体库里的那一项`() {
        val live = entry("a1", "晴天")

        val rows = PlaylistRows.build(listOf(item("a1")), mapOf("a1" to live))

        assertEquals(1, rows.size)
        assertSame(live, rows[0].entry)
        assertFalse(rows[0].missing)
        assertSame(live, rows[0].display)
    }

    @Test
    fun `查不到时标成丢失但保留快照`() {
        val rows = PlaylistRows.build(listOf(item("gone", "旧歌")), emptyMap())

        assertEquals(1, rows.size)
        assertNull(rows[0].entry)
        assertTrue(rows[0].missing)
        // 展示不能因为查不到就变成空白：撤掉存储权限之后整张列表都查不到，
        // 那时用户更需要看到自己当初存进来的是什么。
        assertEquals("旧歌", rows[0].display.title)
        assertEquals("gone", rows[0].display.id)
    }

    @Test
    fun `队列长度永远和条目数一致且顺序不变`() {
        val items = listOf(item("a"), item("gone"), item("c"))

        val queue = PlaylistRows.queue(items, mapOf("a" to entry("a"), "c" to entry("c")))

        assertEquals(items.size, queue.size)
        assertEquals(listOf("a", "gone", "c"), queue.map { it.id })
    }

    @Test
    fun `查不到的条目留在原位而不是被丢掉`() {
        val items = listOf(item("gone"), item("b"))

        val queue = PlaylistRows.queue(items, mapOf("b" to entry("b")))

        // 丢掉它会让队列变成 [b]，于是「点第 2 行播第 2 行」错位成「播第 1 行」。
        assertEquals("gone", queue[0].id)
        assertEquals("b", queue[1].id)
    }

    @Test
    fun `查不到时用快照补出一个可播放的条目`() {
        val queue = PlaylistRows.queue(listOf(item("gone", "旧歌")), emptyMap())

        val restored = queue.single()
        assertEquals("gone", restored.id)
        assertEquals("旧歌", restored.title)
        // mediaId == entry.id 是这套存储的前提，快照路径必须也满足它，
        // 否则播放列表文件里会写出一个永远对不上的 id。
        assertEquals(item("gone").mediaId, restored.id)
    }

    @Test
    fun `条目顺序与输入一一对应`() {
        val items = listOf(item("a"), item("b"), item("c"))

        val rows = PlaylistRows.build(items, emptyMap())

        assertEquals(listOf("a", "b", "c"), rows.map { it.item.mediaId })
    }

    @Test
    fun `空列表两种调用都返回空`() {
        assertTrue(PlaylistRows.build(emptyList(), emptyMap()).isEmpty())
        assertTrue(PlaylistRows.queue(emptyList(), emptyMap()).isEmpty())
    }

    // ── 浏览页条目（file: 前缀）────────────────────────────────────────────────

    /**
     * 浏览页写进来的条目：id 带 `file:` 前缀，uri 就是绝对路径。
     *
     * `PlaylistItem.of` 记的就是浏览页条目传过来的那两个字段（见 `BrowserEntry.toMediaEntry`）。
     */
    private fun browserItem(path: String, title: String = path.substringAfterLast('/')) =
        PlaylistItem.of(
            MediaEntry(
                id = BrowserEntry.mediaIdOf(path),
                uri = path,
                title = title,
                kind = MediaKind.VIDEO,
                source = MediaSource.FILE_SYSTEM,
            ),
        )

    @Test
    fun `文件还在的浏览页条目不标成已不在`() {
        val item = browserItem("/sdcard/Movies/a.mp4", "a")

        val rows = PlaylistRows.build(listOf(item), emptyMap()) { it == "/sdcard/Movies/a.mp4" }

        assertFalse(rows[0].missing)
        assertEquals(item.mediaId, rows[0].display.id)
    }

    @Test
    fun `文件被删掉的浏览页条目还是标成已不在`() {
        val item = browserItem("/sdcard/Movies/gone.mp4", "gone")

        val rows = PlaylistRows.build(listOf(item), emptyMap()) { false }

        assertTrue(rows[0].missing)
        // 标成不在也要能画出一行：标题走快照，否则界面上就是一行空白。
        assertEquals("gone", rows[0].display.title)
    }

    @Test
    fun `没传探针时浏览页条目按不在算`() {
        val rows = PlaylistRows.build(listOf(browserItem("/sdcard/Movies/a.mp4")), emptyMap())

        // 默认值故意选坏的那一边：忘了传探针只会多标一条「文件已不在」，
        // 反过来会把已经删掉的文件说成还能播。
        assertTrue(rows[0].missing)
    }

    @Test
    fun `探针问的是条目自己写下来的那个 uri`() {
        val item = browserItem("/sdcard/Movies/a.mp4", "a")
        val asked = mutableListOf<String>()

        PlaylistRows.build(listOf(item), emptyMap()) { path ->
            asked.add(path)
            true
        }

        assertEquals(listOf("/sdcard/Movies/a.mp4"), asked)
    }

    @Test
    fun `库里查得到的条目不去问文件系统`() {
        val live = entry("a1", "晴天")
        var probeCalls = 0

        val rows = PlaylistRows.build(listOf(item("a1")), mapOf("a1" to live)) {
            probeCalls++
            false
        }

        assertSame(live, rows[0].entry)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `库里的条目即使路径也还在也不走快照`() {
        // 同一个 id 两边都能解释时必须以库为准：库里有 artist/duration，快照里没有，
        // 走错一边的后果是「有的行有艺术家、有的行没有」。
        val live = entry("a1", "晴天")

        val rows = PlaylistRows.build(listOf(item("a1")), mapOf("a1" to live)) { true }

        assertSame(live, rows[0].entry)
    }

    @Test
    fun `库查不到的非浏览页条目不会靠路径蒙混过去`() {
        // MediaStore 的 id 和 `file:` 前缀是两套东西；数字 id 不可能变成合法路径，
        // 所以这一支连探针都不该问。
        var probeCalls = 0

        val rows = PlaylistRows.build(listOf(item("12345")), emptyMap()) {
            probeCalls++
            true
        }

        assertTrue(rows[0].missing)
        assertEquals(0, probeCalls)
    }

    @Test
    fun `浏览页条目的队列项带着文件系统来源`() {
        val item = browserItem("/sdcard/Movies/a.mp4", "a")

        val restored = PlaylistRows.queue(listOf(item), emptyMap()).single()

        // 来源错了字幕就找不到（见 `PlaylistItem.sourceOf`）：播放列表里的浏览页条目
        // 必须仍然按路径去列同目录，而不是去 MediaStore 查相对路径。
        assertEquals(MediaSource.FILE_SYSTEM, restored.source)
        assertEquals("/sdcard/Movies/a.mp4", restored.uri)
    }
}
