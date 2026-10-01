package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.SubtitleFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssParserTest {

    private val sample = """
        [Script Info]
        Title: 测试
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 480

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,思源黑体,48,&H000000FF,&H00FF0000,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,1,2,10,10,20,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.50,Default,小明,0,0,0,,你好, 世界\N第二行
        Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\an8}上方字幕
        Comment: 0,0:00:07.00,0:00:08.00,Default,,0,0,0,,注释行
    """.trimIndent()

    private fun parse(content: String = sample) = AssParser().parse(content)

    @Test
    fun `解析脚本信息与样式表`() {
        val result = parse()
        assertEquals("测试", result.metadata["title"])
        assertEquals("640", result.metadata["playresx"])
        val style = result.styles["Default"]
        assertNotNull(style)
        assertEquals("思源黑体", style.fontFamily)
        assertEquals(true, style.bold) // ASS 用 -1 表示 true
    }

    @Test
    fun `ASS 颜色是 BGR 且 alpha 表示透明度`() {
        val style = assertNotNull(parse().styles["Default"])

        // &H000000FF → BB=00 GG=00 RR=FF → 红色。写反了这里会变成蓝色。
        assertEquals(0xFFFF0000L, style.primaryColorArgb)

        // &H00FF0000 → BB=FF GG=00 RR=00 → 蓝色。
        assertEquals(0xFF0000FFL, style.secondaryColorArgb)

        // &H80000000 → alpha = FF - 80 = 7F。ASS 的 alpha 是「透明度」，
        // 直接搬进 ARGB 会得到一张几乎全透明的字幕。
        assertEquals(0x7F000000L, style.backColorArgb)
    }

    @Test
    fun `正文里的逗号不会把 Text 字段切碎`() {
        val cue = parse().cues.first()
        assertEquals("你好, 世界\n第二行", cue.text)
        assertEquals("Default", cue.styleName)
        assertEquals("小明", cue.actor)
        assertEquals(1_000L, cue.startMs)
        assertEquals(3_500L, cue.endMs)
    }

    @Test
    fun `转义序列 N 变硬换行 h 变不换行空格`() {
        val result = parse(
            """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,甲\N乙\h丙
            """.trimIndent(),
        )
        assertEquals("甲\n乙\u00A0丙", result.cues.single().text)
    }

    @Test
    fun `行内覆盖标签被保留而不是丢弃`() {
        val cue = parse().cues[1]
        assertEquals("上方字幕", cue.text)
        assertEquals("8", cue.overrides["an"])
    }

    @Test
    fun `an8 在没有 MarginV 时也定位到顶部`() {
        // MarginV 默认 0（这里被 takeIf 过滤成 null），早期实现在这种情况下
        // 会回落到默认的底部锚点，让 \an8 完全失效。
        val position = assertNotNull(parse().cues[1].position)
        assertTrue(position.anchorY < 0.5f, "\\an8 应该靠上，实际 anchorY=${position.anchorY}")
        assertEquals(8, position.alignment)
    }

    @Test
    fun `pos 覆盖按 PlayRes 归一化`() {
        val result = parse(
            """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 640
            PlayResY: 480

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\pos(320,120)}居中偏上
            """.trimIndent(),
        )
        val position = assertNotNull(result.cues.single().position)
        assertEquals(0.5f, position.anchorX)
        assertEquals(0.25f, position.anchorY)
    }

    @Test
    fun `卡拉OK 逐字时长按厘秒换算且起始时间逐段累加`() {
        val result = parse(
            """
            [Script Info]
            ScriptType: v4.00+

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\k50}你{\k30}好
            """.trimIndent(),
        )

        val cue = result.cues.single()
        assertEquals("你好", cue.text)
        assertEquals(2, cue.karaoke.size)

        assertEquals(1_000L, cue.karaoke[0].startMs)
        assertEquals(500L, cue.karaoke[0].durationMs) // 50 厘秒
        assertEquals("你", cue.karaoke[0].text)

        // 第二段必须从第一段结束处开始，不能还是从行首算。
        assertEquals(1_500L, cue.karaoke[1].startMs)
        assertEquals(300L, cue.karaoke[1].durationMs) // 30 厘秒
        assertEquals("好", cue.karaoke[1].text)
    }

    @Test
    fun `Comment 行被保留但标记为注释`() {
        val comment = parse().cues.single { it.startMs == 7_000L }
        assertTrue(comment.isComment)
    }

    @Test
    fun `格式判定为 ASS`() {
        assertEquals(SubtitleFormat.ASS, parse().format)
    }
}
