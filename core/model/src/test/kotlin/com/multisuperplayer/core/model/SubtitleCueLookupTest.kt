package com.multisuperplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SubtitleDocument.cueAt] 与 [SubtitleDocument.cueFocusedAt] 的语义差异测试。
 *
 * 这两个方法看起来几乎一样，混用不会报错、不会崩，只会让**歌词在每句末尾
 * 闪一下**或者让**字幕在静默段落里一直挂着**。所以必须用测试把「谁该用哪个」
 * 钉死，而不是靠注释。
 */
class SubtitleCueLookupTest {

    private fun document(vararg ranges: Pair<Long, Long>): SubtitleDocument = SubtitleDocument(
        track = SubtitleTrack(id = "test"),
        cues = ranges.mapIndexed { index, (start, end) ->
            SubtitleCue(index = index, startMs = start, endMs = end, text = "第 ${index + 1} 句")
        },
    )

    @Test
    fun `空档里 cueAt 返回 null 而 cueFocusedAt 继续指向上一行`() {
        // 1s-2s 一句，5s-6s 一句，3s 落在空档。
        val document = document(1_000L to 2_000L, 5_000L to 6_000L)

        assertNull("静默段落里屏幕上不该还挂着上一句台词", document.cueAt(3_000L))

        val focused = document.cueFocusedAt(3_000L)
        assertNotNull("歌词页必须继续高亮上一行，否则每句末尾都会闪一下", focused)
        assertEquals(0, focused?.index)
        assertEquals(0, document.cueFocusIndexAt(3_000L))
    }

    @Test
    fun `第一句之前两者都返回空`() {
        val document = document(1_000L to 2_000L)

        assertNull(document.cueAt(500L))
        assertNull(document.cueFocusedAt(500L))
        assertEquals(-1, document.cueFocusIndexAt(500L))
    }

    @Test
    fun `最后一句结束之后 cueAt 为空但高亮仍停在最后一行`() {
        // LRC 没有「结束时间」这个概念，解析器只能补一个尾部时长。
        // 补出来的时长用完之后，歌词页不该突然什么都没了。
        val document = document(1_000L to 2_000L, 3_000L to 4_000L)

        assertNull(document.cueAt(9_000L))
        assertEquals(1, document.cueFocusIndexAt(9_000L))
    }

    @Test
    fun `边界时间点上两者给出同一个 cue`() {
        val document = document(1_000L to 2_000L, 5_000L to 6_000L)

        assertEquals(0, document.cueAt(1_000L)?.index)
        assertEquals(0, document.cueFocusIndexAt(1_000L))

        // 恰好落在结束时刻：末尾是高亮上一行，屏幕上则已经没有字幕了。
        assertNull(document.cueAt(2_000L))
        assertEquals("边界处不能提前跳到下一句", 0, document.cueFocusIndexAt(2_000L))
    }

    @Test
    fun `首尾相邻的 cue 在交界处交接`() {
        val document = document(1_000L to 2_000L, 2_000L to 3_000L)

        assertEquals(0, document.cueAt(1_999L)?.index)
        assertEquals(1, document.cueAt(2_000L)?.index)
        assertEquals(1, document.cueFocusIndexAt(2_000L))
    }

    @Test
    fun `空文档不会崩`() {
        val document = document()

        assertNull(document.cueAt(1_000L))
        assertNull(document.cueFocusedAt(1_000L))
        assertEquals(-1, document.cueFocusIndexAt(1_000L))
    }

    @Test
    fun `二分查找在长列表上也定位准确`() {
        // 上千条 cue 是常态（一部剧一集就一两千行），
        // 这里用等间隔的一千条把「最大 startMs <= position」验证到位。
        val document = document(*Array(1_000) { index -> (index * 1_000L) to (index * 1_000L + 500L) })

        assertEquals(0, document.cueFocusIndexAt(0L))
        assertEquals(499, document.cueFocusIndexAt(499_500L))
        assertEquals(500, document.cueFocusIndexAt(500_000L))
        assertEquals(999, document.cueFocusIndexAt(999_999L))
        assertEquals(-1, document.cueFocusIndexAt(-1L))
    }
}
