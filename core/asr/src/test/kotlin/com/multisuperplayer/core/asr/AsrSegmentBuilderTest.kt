package com.multisuperplayer.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把 VAD 的区间整理成字幕时间轴的测试。
 *
 * 这一步的产物直接决定「字幕什么时候出现」，而它错了**不会报任何错**：
 * 重叠的字幕会是两条同时挂在屏幕上，串位的字幕是「台词对不上口型」。
 * 所以这里要钉住的取舍是：
 *
 * - 前后各外扩 [AsrSegmentBuilder.PAD_MS]（VAD 报的起点比真正的起音晚）；
 * - 外扩只往静音里长，实在没地方就在两段语音的中点分界（**不许重叠**）；
 * - 被前一段包住的区间会被丢掉，但**文本必须跟着区间一起走**，不许按下标配对。
 */
class AsrSegmentBuilderTest {

    /** 16 kHz 下 1 ms = 16 个采样点。 */
    private fun ms(value: Int): Int = value * 16

    private fun detected(fromMs: Int, toMs: Int, text: String = "t"): AsrDetected =
        AsrDetected(AsrRange(ms(fromMs), ms(toMs)), text)

    private fun build(
        vararg items: AsrDetected,
        totalMs: Int = 60_000,
        sampleRate: Int = 16_000,
    ): List<AsrSegment> = AsrSegmentBuilder.build(
        detected = items.toList(),
        totalSamples = totalMs * sampleRate / 1000,
        sampleRate = sampleRate,
    )

    // ------------------------------------------------------------ 余量

    @Test
    fun `前后各外扩 160 毫秒`() {
        val segment = build(detected(1000, 2000)).single()

        // VAD 要攒够一个窗口才敢判「有人说话」，报出的起点比真正的起音晚，
        // 后端余量更是必须的（语音结束后它就不再输出样本了）
        assertEquals(840L, segment.startMs)
        assertEquals(2160L, segment.endMs)
    }

    @Test
    fun `余量不会越过上一段的语音结尾`() {
        // 两段之间只隔 100 ms 静音，而两边各要 160 ms 余量
        val segments = build(
            detected(1000, 2000, "a"),
            detected(2100, 4000, "b"),
        )

        assertEquals(2, segments.size)
        assertEquals(840L, segments[0].startMs)
        // 中点分界：两段的分界落在 2000 和 2100 的正中间
        assertEquals(2050L, segments[0].endMs)
        assertEquals(2050L, segments[1].startMs)
        assertEquals(4160L, segments[1].endMs)
        assertEquals(listOf("a", "b"), segments.map { it.text })
    }

    @Test
    fun `静音足够时两段各自外扩，不去动中点`() {
        val segments = build(
            detected(1000, 2000, "a"),
            detected(3000, 4000, "b"),
        )

        assertEquals(840L, segments[0].startMs)
        assertEquals(2160L, segments[0].endMs)
        assertEquals(2840L, segments[1].startMs)
        assertEquals(4160L, segments[1].endMs)
    }

    @Test
    fun `输入区间自己重叠时，按上一段的语音结尾抹平`() {
        val segments = build(
            detected(1000, 3000, "a"),
            detected(2000, 4000, "b"),
        )

        assertEquals(2, segments.size)
        // 第二段的起点被抬到第一段语音结束的地方（3000 ms），而不是保留 2000 ms
        assertEquals(3000L, segments[0].endMs)
        assertEquals(3000L, segments[1].startMs)
        assertEquals(listOf("a", "b"), segments.map { it.text })
    }

    // ------------------------------------------------------------ 丢区间与文本配对

    @Test
    fun `被前一段包住的区间被丢掉，后面的文本不会串位`() {
        val segments = build(
            detected(1000, 5000, "a"),
            // 完全落在第一段里面：正常 VAD 不会这样输出，但这不是「不会发生」的理由
            detected(2000, 3000, "b"),
            detected(6000, 7000, "c"),
        )

        assertEquals("中间那一段应该被丢掉", 2, segments.size)
        // 关键断言：文本跟着区间走。如果按下标配对，这里会变成 ["a", "b"] ——
        // 字幕看起来完全正常，只是台词晚了一句
        assertEquals(listOf("a", "c"), segments.map { it.text })
        assertEquals(840L, segments[0].startMs)
        assertEquals(5160L, segments[0].endMs)
        assertEquals(5840L, segments[1].startMs)
        assertEquals(7160L, segments[1].endMs)
    }

    @Test
    fun `乱序输入按起点排好`() {
        val segments = build(
            detected(6000, 7000, "c"),
            detected(1000, 2000, "a"),
            detected(3000, 4000, "b"),
        )

        assertEquals(listOf("a", "b", "c"), segments.map { it.text })
        assertTrue(segments[0].startMs < segments[1].startMs)
        assertTrue(segments[1].startMs < segments[2].startMs)
    }

    @Test
    fun `零长与反向的区间被丢掉`() {
        val segments = build(
            detected(1000, 1000, "zero"),
            detected(3000, 2000, "reverse"),
        )

        assertTrue(segments.isEmpty())
    }

    // ------------------------------------------------------------ 边界

    @Test
    fun `空输入、非法总长、非法采样率都返回空表`() {
        assertTrue(AsrSegmentBuilder.build(emptyList(), 16_000, 16_000).isEmpty())
        assertTrue(AsrSegmentBuilder.build(listOf(detected(1000, 2000)), 0, 16_000).isEmpty())
        assertTrue(AsrSegmentBuilder.build(listOf(detected(1000, 2000)), -1, 16_000).isEmpty())
        assertTrue(AsrSegmentBuilder.build(listOf(detected(1000, 2000)), 16_000, 0).isEmpty())
    }

    @Test
    fun `夹进音频总长`() {
        // 片尾：语音一直到最后，外扩会超出总长
        val segment = build(detected(0, 3000), totalMs = 1000).single()

        assertEquals(0L, segment.startMs)
        assertEquals(1000L, segment.endMs)
    }

    @Test
    fun `起点为负的输入被夹到 0`() {
        val segment = build(detected(-500, 500)).single()

        assertEquals(0L, segment.startMs)
        assertEquals(660L, segment.endMs)
    }

    @Test
    fun `整段落在音频之外时返回空表`() {
        // VAD 报了 5 s 之后的一段，而音频只有 1 s：夹完之后是零长，应当丢掉
        assertTrue(build(detected(5000, 6000), totalMs = 1000).isEmpty())
    }

    @Test
    fun `毫秒换算用的是传进来的采样率`() {
        val segment = AsrSegmentBuilder.build(
            detected = listOf(AsrDetected(AsrRange(0, 8000), "t")),
            totalSamples = 8000,
            sampleRate = 8_000,
        ).single()

        // 8000 个采样点在 8 kHz 下正好是 1 秒
        assertEquals(1000L, segment.endMs)
    }

    @Test
    fun `输出的时间轴单调且两两不重叠`() {
        val segments = build(
            detected(1000, 2000, "a"),
            detected(2050, 3000, "b"),
            detected(3010, 3020, "c"),
            detected(10000, 12000, "d"),
        )

        segments.forEach { assertTrue("每条都要有正的时长：$it", it.endMs > it.startMs) }
        for (index in 0 until segments.size - 1) {
            assertTrue(
                "第 $index 条与下一条重叠了：${segments[index]} / ${segments[index + 1]}",
                segments[index].endMs <= segments[index + 1].startMs,
            )
        }
        assertEquals(listOf("a", "b", "c", "d"), segments.map { it.text })
    }
}
