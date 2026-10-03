package com.multisuperplayer.feature.player

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 字幕时间轴微调（面板里的 `-0.5 / -0.1 / +0.1 / +0.5 秒`）。
 *
 * ## 为什么符号方向必须有测试
 *
 * `subtitleCuePosition` 的 KDoc 自己写着「抽成一个函数是为了让符号方向只有一个
 * 地方可以写错，并且**能钉在单测里**」——那就把它钉住，否则写反了没人会发现：
 *
 * - 写反的症状不是崩溃，而是「往正数调，字幕更早了」；
 * - 用户按提示调一次发现更糟，会以为自己理解错了方向，然后一直往反方向调，
 *   调到极限（`-0.5` 十次）以为「这功能没用」；
 * - 屏幕上从头到尾都只是「字幕早/晚了一点」，没有任何一处报错。
 *
 * 所以下面两个方向各有一个**反向断言**：调完之后「原来该出现的那一刻不再出现」。
 * 只断言「调完之后新时刻能查到」，符号写反了也一样能过。
 */
class SubtitleOffsetTest {

    /** 一条 [5s, 9s] 的台词。 */
    private val document = SubtitleDocument(
        track = SubtitleTrack(id = "test"),
        cues = listOf(
            SubtitleCue(index = 0, startMs = 5_000L, endMs = 9_000L, text = "A"),
        ),
    )

    private fun cueAt(positionMs: Long, offsetMs: Long) =
        document.cueAt(subtitleCuePosition(positionMs, offsetMs))

    @Test
    fun `微调为零时按原时间轴显示`() {
        assertNull("台词 5s 才开始", cueAt(positionMs = 4_999L, offsetMs = 0L))
        assertNotNull(cueAt(positionMs = 5_000L, offsetMs = 0L))
    }

    @Test
    fun `往正数调表示字幕晚出现`() {
        // 调了 +0.5s 之后，5s 这一刻应该还是空的……
        assertNull(cueAt(positionMs = 5_000L, offsetMs = 500L))
        // ……要等到 5.5s 才出现（也就是「晚 0.5 秒」）。
        assertNotNull(cueAt(positionMs = 5_500L, offsetMs = 500L))
    }

    @Test
    fun `往负数调表示字幕早出现`() {
        // 调了 -0.5s 之后，5s 这一刻字幕**已经**在了……
        assertNotNull(cueAt(positionMs = 5_000L, offsetMs = -500L))
        // ……因为它从 4.5s 就开始显示。
        assertNotNull(cueAt(positionMs = 4_500L, offsetMs = -500L))
        assertNull(cueAt(positionMs = 4_499L, offsetMs = -500L))
    }

    @Test
    fun `微调不会改变台词的时长 只是整体平移`() {
        // 平移不是拉伸：+2s 之后这条台词仍然是 4 秒长，只是从 7s 到 11s。
        assertNull(cueAt(positionMs = 6_999L, offsetMs = 2_000L))
        assertNotNull(cueAt(positionMs = 7_000L, offsetMs = 2_000L))
        assertNotNull(cueAt(positionMs = 10_999L, offsetMs = 2_000L))
        assertNull(cueAt(positionMs = 11_000L, offsetMs = 2_000L))
    }

    // ---------------- formatSubtitleOffset ----------------

    @Test
    fun `微调值带符号 而且只到十分之一秒`() {
        assertEquals("0", formatSubtitleOffset(0L))
        assertEquals("+0.5", formatSubtitleOffset(500L))
        assertEquals("-0.5", formatSubtitleOffset(-500L))
        assertEquals("+0.1", formatSubtitleOffset(100L))
        assertEquals("-0.1", formatSubtitleOffset(-100L))
        assertEquals("-1.5", formatSubtitleOffset(-1_500L))
        // 步长只有 ±0.1/±0.5，所以这里永远不会出现非整百的余数；
        // 真出现的话也算出个能看的数，不值得为它加分支。
        assertEquals("+0.0", formatSubtitleOffset(60L))
    }

    @Test
    fun `微调值不带小数点后面的千分位`() {
        // 500ms 必须显示 `+0.5` 而不是 `+0.500`：面板里那一格是窄的，
        // 多两位数字会把「归零」按钮挤出去。
        assertEquals("+0.5", formatSubtitleOffset(500L))
        assertEquals("+0.9", formatSubtitleOffset(900L))
    }
}
