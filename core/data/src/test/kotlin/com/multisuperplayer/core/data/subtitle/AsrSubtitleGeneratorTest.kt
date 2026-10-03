package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.asr.AsrSegment
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.subtitle.SrtParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别结果 → SRT 文本」的测试。
 *
 * 这段是所有生成字幕的**唯一出口**，而它错起来的样子是「文件看起来没毛病」：
 * 序号从 0 开始、时间码少一格、多行文本被压成一行——都在 VLC 里能放，
 * 只是这条字幕在那台设备上跳一下或者只显示一半。所以用「自己写出去、
 * 再让自己家的 [SrtParser] 读回来」的方式钉住：解析器读不回来的 SRT，
 * 别的播放器也读不回来。
 */
class AsrSubtitleGeneratorTest {

    private val trackId = "msp-generated://abc123"

    private fun segment(startMs: Long, endMs: Long, text: String) = AsrSegment(startMs, endMs, text)

    private fun srtOf(vararg segments: AsrSegment) = srtTextOf(trackId, segments.toList())

    @Test
    fun `生成的字幕能被自己的 SRT 解析器读回来`() {
        val text = srtOf(
            segment(840, 2_160, "你好，世界"),
            segment(3_000, 4_500, "第二句话"),
            segment(5_000, 6_000, "hello world"),
        )

        val parsed = SrtParser().parse(text)

        assertEquals(SubtitleFormat.SRT, parsed.format)
        assertEquals("解析器不该有警告：${parsed.warnings}", emptyList<String>(), parsed.warnings)
        assertEquals(
            listOf("你好，世界", "第二句话", "hello world"),
            parsed.cues.map { it.text },
        )
        assertEquals(listOf(840L, 3_000L, 5_000L), parsed.cues.map { it.startMs })
        assertEquals(listOf(2_160L, 4_500L, 6_000L), parsed.cues.map { it.endMs })
    }

    @Test
    fun `时间戳是 HH MM SS,mmm`() {
        val text = srtOf(
            segment(840, 2_160, "短句"),
            segment(3_723_004, 3_724_000, "一小时零两分的句子"),
        )

        // 时间码格式错了不会报错，只会在别的播放器里整条字幕错位
        assertTrue(text.contains("00:00:00,840 --> 00:00:02,160"))
        assertTrue(text.contains("01:02:03,004 --> 01:02:04,000"))
    }

    @Test
    fun `序号从 1 开始连续递增`() {
        val text = srtOf(
            segment(0, 1_000, "一"),
            segment(1_000, 2_000, "二"),
            segment(2_000, 3_000, "三"),
        )

        val blocks = text.trim().split("\n\n".toRegex())
        assertEquals(listOf("1", "2", "3"), blocks.map { it.lines().first() })
    }

    @Test
    fun `多行文本原样保留`() {
        val text = srtOf(segment(0, 1_000, "第一行\n第二行"))

        // 换行被压成空格的话，「双语对照」这种写法就直接坏掉了
        assertTrue(text.contains("第一行\n第二行"))
        assertEquals(listOf("第一行\n第二行"), SrtParser().parse(text).cues.map { it.text })
    }

    @Test
    fun `内部 uri 不会被写进字幕文件`() {
        val text = srtOf(segment(0, 1_000, "内容"))

        // SRT 里没有放 id 的地方，硬塞进去会污染用户看到/导出的文件
        assertFalse(text.contains(trackId))
        assertFalse(text.contains("msp-generated"))
    }

    @Test
    fun `一条字幕都没有时是空文本`() {
        // 0 条 → 空文本 → 落盘后是 0 字节 → `exists()` 为 false（0 字节不算存在），
        // 于是面板显示「没有字幕」而不是「有一条空字幕」，两边口径一致。
        assertEquals("", srtTextOf(trackId, emptyList()))
    }

    @Test
    fun `不同媒体用不同 trackId，文本内容不受影响`() {
        val segments = listOf(segment(0, 1_000, "内容"))

        assertEquals(srtTextOf("msp-generated://aaa", segments), srtTextOf("msp-generated://bbb", segments))
    }
}
