package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
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
}
