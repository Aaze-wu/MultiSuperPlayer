package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.text.MspText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcParserTest {

    private fun parse(content: String) = LrcParser().parse(content)

    @Test
    fun `解析元数据与基本歌词`() {
        val result = parse(
            """
            [ti:测试歌曲]
            [ar:某歌手]
            [00:10.00]第一句
            [00:12.00]第二句
            """.trimIndent(),
        )

        assertEquals("测试歌曲", result.metadata["ti"])
        assertEquals("某歌手", result.metadata["ar"])
        assertEquals(2, result.cues.size)
        assertEquals(10_000L, result.cues[0].startMs)
        assertEquals(12_000L, result.cues[1].startMs)
        assertEquals(SubtitleFormat.LRC, result.format)
    }

    @Test
    fun `offset 会被真正应用到时间轴上`() {
        // 这是最容易「记录了但没生效」的一处：metadata 里写上 offsetAppliedMs
        // 会让人误以为已经处理过，只有断言时间戳能证明它真的生效。
        val result = parse(
            """
            [offset:+500]
            [00:10.00]第一句
            [00:12.00]第二句
            """.trimIndent(),
        )

        assertEquals(9_500L, result.cues[0].startMs)
        assertEquals(11_500L, result.cues[1].startMs)
        assertEquals("500", result.metadata["offsetAppliedMs"])
    }

    @Test
    fun `offset 符号策略可切换`() {
        val content = """
            [offset:+500]
            [00:10.00]第一句
        """.trimIndent()

        val subtract = LrcParser(offsetPolicy = LrcParser.OffsetPolicy.SUBTRACT).parse(content)
        val add = LrcParser(offsetPolicy = LrcParser.OffsetPolicy.ADD).parse(content)

        assertEquals(9_500L, subtract.cues[0].startMs)
        assertEquals(10_500L, add.cues[0].startMs)
    }

    @Test
    fun `结束时间取下一个时间点而不是下一句歌词`() {
        // 中间那个空文本行是「间奏结束」标记，必须被当作时间锚点，
        // 否则上一句歌词会一直挂到第三句开始。
        val result = parse(
            """
            [00:10.00]唱
            [00:20.00]
            [00:30.00]停
            """.trimIndent(),
        )

        assertEquals(2, result.cues.size)
        assertEquals(10_000L, result.cues[0].startMs)
        assertEquals(20_000L, result.cues[0].endMs)
        assertEquals(30_000L, result.cues[1].startMs)
    }

    @Test
    fun `同一时间码两行合并为原文加译文`() {
        val result = parse(
            """
            [00:10.00]Hello
            [00:10.00]你好
            [00:20.00]World
            """.trimIndent(),
        )

        assertEquals(2, result.cues.size)
        assertEquals("Hello", result.cues[0].text)
        assertEquals("你好", result.cues[0].translation)
        assertNull(result.cues[1].translation)
    }

    @Test
    fun `同一时间码三行不做双语合并`() {
        // 三行更可能是重复的副歌，把第三行当译文会显示成垃圾。
        val result = parse(
            """
            [00:10.00]A
            [00:10.00]B
            [00:10.00]C
            """.trimIndent(),
        )

        assertEquals(3, result.cues.size)
        assertTrue(result.cues.none { it.translation != null })
        // 告警现在是一条**能翻译**的 MspText（资源 id + 参数），不再是拼好的中文句子。
        // 只断言「有告警」会漏掉「告警里又写回一句硬编码中文」这种退化——
        // 那正是这次改造要消除的东西，所以这里钉死 id 与两个参数。
        assertEquals(
            MspText.Res(R.string.msp_subtitle_warn_lrc_merge_overflow, 10_000L, 3),
            result.warnings.single(),
        )
    }

    @Test
    fun `增强型 LRC 解析逐字时间`() {
        val result = parse("[00:01.00]<00:01.00>你<00:01.50>好")

        assertEquals(SubtitleFormat.ENHANCED_LRC, result.format)
        val cue = result.cues.single()
        assertEquals("你好", cue.text)
        assertEquals(2, cue.karaoke.size)
        assertEquals(1_000L, cue.karaoke[0].startMs)
        assertEquals(500L, cue.karaoke[0].durationMs)
        assertEquals("你", cue.karaoke[0].text)
        assertEquals(1_500L, cue.karaoke[1].startMs)
        assertEquals("好", cue.karaoke[1].text)
    }

    @Test
    fun `一行多个时间标签产出多条歌词`() {
        val result = parse("[00:10.00][00:20.00]副歌")

        assertEquals(2, result.cues.size)
        assertEquals(10_000L, result.cues[0].startMs)
        assertEquals(20_000L, result.cues[1].startMs)
        assertTrue(result.cues.all { it.text == "副歌" })
    }

    @Test
    fun `没有任何时间标签时抛出解析异常`() {
        val error = runCatching { parse("这只是一段普通文本") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is SubtitleParseException)
    }

    @Test
    fun `最后一句用固定时长兜底`() {
        val result = parse("[00:10.00]最后一句")
        val cue = result.cues.single()
        assertEquals(10_000L, cue.startMs)
        assertTrue(cue.endMs > cue.startMs, "结束时间必须大于开始时间，否则永远不会显示")
    }
}
