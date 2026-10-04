package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.text.MspText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 判定 + 兜底这两层是「用户下载的字幕能不能显示出来」的唯一防线。
 */
class SubtitleParserRegistryTest {

    private val registry = SubtitleParserRegistry()

    private val srt = """
        1
        00:00:01,000 --> 00:00:02,500
        你好

        2
        00:00:03,000 --> 00:00:04,000
        世界
    """.trimIndent()

    private val vtt = """
        WEBVTT

        00:00:01.000 --> 00:00:03.000
        第一句

        00:00:04.000 --> 00:00:06.000
        第二句
    """.trimIndent()

    @Test
    fun `按内容识别 SRT`() {
        val result = registry.parse(srt, fileName = "字幕.txt")
        assertEquals(SubtitleFormat.SRT, result.format)
        assertEquals(2, result.cues.size)
        assertEquals(1_000L, result.cues[0].startMs)
        assertEquals(2_500L, result.cues[0].endMs)
    }

    @Test
    fun `按内容识别 VTT 且不受 webvtt 头影响`() {
        val result = registry.parse(vtt)
        assertEquals(SubtitleFormat.VTT, result.format)
        assertEquals(2, result.cues.size)
    }

    @Test
    fun `后缀错误时以内容为准`() {
        // 下载来的字幕后缀几乎没有参考价值，内容嗅探必须优先。
        val result = registry.parse("[00:10.00]歌词", fileName = "看起来像字幕.srt")
        assertEquals(SubtitleFormat.LRC, result.format)
        assertEquals(10_000L, result.cues.single().startMs)
    }

    @Test
    fun `显式 hint 优先于内容嗅探`() {
        val result = registry.parse(srt, hint = SubtitleFormat.SRT)
        assertEquals(SubtitleFormat.SRT, result.format)
    }

    @Test
    fun `hint 为 UNKNOWN 时退回自动判定`() {
        val result = registry.parse(srt, hint = SubtitleFormat.UNKNOWN)
        assertEquals(SubtitleFormat.SRT, result.format)
    }

    @Test
    fun `空内容抛出解析异常`() {
        val error = runCatching { registry.parse("   \n\n  ") }.exceptionOrNull()
        assertTrue(error is SubtitleParseException)
        // 断言的是**资源 id**，不是拼好的句子：`message` 现在只是 `text.toString()`
        // （形如 `Res(id=…, args=[])`），只用来写日志。以前那句
        // `message!!.contains("空")` 能过，恰恰是因为文案被硬编码在解析器里——
        // 也就是「英文界面下这句话永远是中文」的另一面。
        assertEquals(
            MspText.Res(R.string.msp_subtitle_error_empty),
            (error as SubtitleParseException).text,
        )
    }

    @Test
    fun `完全无法解析的内容抛出异常并列出尝试过的格式`() {
        val error = runCatching { registry.parse("\u0001\u0002\u0003 这不是任何字幕") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is SubtitleParseException)

        val text = (error as SubtitleParseException).text
        assertTrue(text is MspText.Res, "异常带的应当是一条资源文案，实际：$text")
        val res = text as MspText.Res
        assertEquals(R.string.msp_subtitle_error_unparsable, res.id)

        // 第 2 个参数就是「尝试过的格式」。只断它非空，不断具体内容：
        // 上面那个 `runCatching { … }.getOrNull() ?: continue` 会把**抛异常**的
        // 解析器直接跳过、不计入候选列表，所以这里能列出来的格式天生是不全的。
        val listed = res.args[1] as String
        assertTrue(listed.isNotBlank(), "候选格式列表不该是空的")
    }

    @Test
    fun `parserFor 能找到支持该格式的解析器`() {
        assertEquals(SubtitleFormat.SRT, registry.parserFor(SubtitleFormat.SRT)?.format)
        // ASS 与 SSA 共用同一个解析器。
        assertTrue(registry.parserFor(SubtitleFormat.SSA)?.supportedFormats?.contains(SubtitleFormat.SSA) == true)
        assertNull(registry.parserFor(SubtitleFormat.PGS))
    }

    @Test
    fun `检测器区分普通 LRC 与增强型 LRC`() {
        assertEquals(
            SubtitleFormat.LRC,
            SubtitleFormatDetector.detect("[00:10.00]普通歌词"),
        )
        assertEquals(
            SubtitleFormat.ENHANCED_LRC,
            SubtitleFormatDetector.detect("[00:10.00]<00:10.00>逐<00:10.50>字"),
        )
    }

    @Test
    fun `检测器区分 ASS 与 SSA`() {
        val assHeader = "[Script Info]\nScriptType: v4.00+\n\n[V4+ Styles]\n"
        val ssaHeader = "[Script Info]\nScriptType: v4.00\n\n[V4 Styles]\n"
        assertEquals(SubtitleFormat.ASS, SubtitleFormatDetector.detect(assHeader))
        assertEquals(SubtitleFormat.SSA, SubtitleFormatDetector.detect(ssaHeader))
    }

    @Test
    fun `检测器输出未知而不是抛错`() {
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFormatDetector.detect("随便一段文字"))
    }
}
