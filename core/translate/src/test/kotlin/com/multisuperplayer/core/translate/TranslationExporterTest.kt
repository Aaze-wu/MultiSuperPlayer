package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.CuePosition
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 导出。
 *
 * 导出文件是要**拿去用**的，所以断言的重点是「时间轴不能有洞」和
 * 「序号必须重新连续」——这两条错了，播放器上看到的就是一段空白/花屏。
 */
class TranslationExporterTest {

    private val target = TranslationTarget.SIMPLIFIED_CHINESE

    @Test
    fun `时间格式化`() {
        assertEquals("00:00:00,000", srtTime(0))
        assertEquals("00:00:01,000", srtTime(1_000))
        assertEquals("01:01:01,500", srtTime(3_661_500))
        assertEquals("00:00:00,000", srtTime(-1), "负时间必须夹到 0，否则会出现 00:-1:59")

        assertEquals("0:00:01.00", assTime(1_000))
        assertEquals("0:00:01.23", assTime(1_230))
        assertEquals("1:00:00.00", assTime(3_600_000))
        assertEquals("0:00:00.00", assTime(-5))
    }

    @Test
    fun `SRT 序号重新连续且跳过注释行`() {
        val document = docOf("a", "注释", "b", commentIndices = setOf(1))
        val text = buildExportedSubtitle(
            document,
            mapOf(0 to "甲", 2 to "乙"),
            SubtitleExportFormat.SRT,
            SubtitleExportMode.TRANSLATION_ONLY,
        )
        // 直接照抄 cue.index 的话，注释行会在序号上留一个洞（1, 3），
        // 有些播放器遇到跳号会直接报错或吞掉后一行。
        assertTrue(text.startsWith("1\n00:00:00,000 --> 00:00:00,900\n甲\n\n"))
        assertTrue(text.contains("2\n00:00:02,000 --> 00:00:02,900\n乙"))
        assertTrue(!text.contains("注释"))
    }

    @Test
    fun `仅译文模式下缺译文的行回退原文`() {
        val document = docOf("原文一", "原文二")
        val text = buildExportedSubtitle(
            document,
            mapOf(0 to "译文一"),
            SubtitleExportFormat.SRT,
            SubtitleExportMode.TRANSLATION_ONLY,
        )
        assertTrue(text.contains("译文一"))
        // 留洞比退回原文糟得多：播放器读到那儿就是一段空白。
        assertTrue(text.contains("原文二"))
    }

    @Test
    fun `双语模式原文在上译文在下`() {
        val document = docOf("原文一")
        val text = buildExportedSubtitle(
            document,
            mapOf(0 to "译文一"),
            SubtitleExportFormat.SRT,
            SubtitleExportMode.BILINGUAL,
        )
        assertTrue(text.contains("原文一\n译文一"))
    }

    @Test
    fun `已有 cue 译文会被用上`() {
        val document = docOf("原文").withTranslations(mapOf(0 to "旧译文"))
        val text = buildExportedSubtitle(
            document,
            mapOf(0 to "新译文"),
            SubtitleExportFormat.SRT,
            SubtitleExportMode.TRANSLATION_ONLY,
        )
        // 显式传入的优先（用户在界面上刚改过的那一份）。
        assertTrue(text.contains("新译文"))
        assertTrue(!text.contains("旧译文"))
    }

    @Test
    fun `下标按列表位置算`() {
        val document = SubtitleDocument(
            track = SubtitleTrack(id = "t"),
            cues = listOf(
                SubtitleCue(index = 100, startMs = 0, endMs = 900, text = "a"),
                SubtitleCue(index = 200, startMs = 1_000, endMs = 1_900, text = "b"),
            ),
        )
        val text = buildExportedSubtitle(
            document,
            mapOf(0 to "甲", 1 to "乙"),
            SubtitleExportFormat.SRT,
            SubtitleExportMode.TRANSLATION_ONLY,
        )
        assertTrue(text.contains("甲"))
        assertTrue(text.contains("乙"))
    }

    @Test
    fun `ASS 结构与转义`() {
        val document = SubtitleDocument(
            track = SubtitleTrack(id = "t"),
            cues = listOf(
                SubtitleCue(
                    index = 0,
                    startMs = 1_000,
                    endMs = 2_500,
                    text = "原文",
                    actor = "角色,甲",
                    styleName = "主标题,大",
                    position = CuePosition(alignment = 8),
                ),
            ),
            metadata = mapOf("Title" to "第一集"),
        )
        val text = buildExportedSubtitle(
            document,
            mapOf(0 to "第一行\n第二行"),
            SubtitleExportFormat.ASS,
            SubtitleExportMode.TRANSLATION_ONLY,
        )
        assertTrue(text.contains("[Script Info]"))
        assertTrue(text.contains("[V4+ Styles]"))
        assertTrue(text.contains("[Events]"))
        assertTrue(text.contains("Title: 第一集"))
        // ASS 里换行要写成 \N。
        assertTrue(text.contains("""第一行\N第二行"""))
        // 对齐从 cue 上带过来。
        assertTrue(text.contains("""{\an8}"""))
        // 逗号是字段分隔符，留在样式名/角色名里会让整行错位。
        assertTrue(text.contains("Dialogue: 0,0:00:01.00,0:00:02.50,主标题，大,角色，甲,0,0,0,,"))
        assertTrue(!text.contains("角色,甲"))
    }

    @Test
    fun `ASS 里的花括号被转义`() {
        val document = docOf("原文")
        val text = buildExportedSubtitle(
            document,
            mapOf(0 to "带{标签}的译文"),
            SubtitleExportFormat.ASS,
            SubtitleExportMode.TRANSLATION_ONLY,
        )
        // 不转义的话 ASS 会把它当覆盖标签，用户看到的是标签文字消失、样式乱掉。
        assertTrue(text.contains("""带\{标签\}的译文"""))
    }

    @Test
    fun `导出文件名带上目标语言与形态`() {
        assertEquals(
            "第一集.zh-Hans.srt",
            exportFileName("第一集.ass", target, SubtitleExportFormat.SRT, SubtitleExportMode.TRANSLATION_ONLY),
        )
        assertEquals(
            "第一集.zh-Hans.bilingual.srt",
            exportFileName("第一集.ass", target, SubtitleExportFormat.SRT, SubtitleExportMode.BILINGUAL),
        )
        assertEquals(
            "movie.ja.ass",
            exportFileName("movie.mkv", TranslationTarget.JAPANESE, SubtitleExportFormat.ASS, SubtitleExportMode.TRANSLATION_ONLY),
        )
        // 没有扩展名也不能拼出 ".zh-Hans.srt" 这种以点开头的名字。
        assertEquals(
            "subtitle.zh-Hans.srt",
            exportFileName("", target, SubtitleExportFormat.SRT, SubtitleExportMode.TRANSLATION_ONLY),
        )
    }
}
