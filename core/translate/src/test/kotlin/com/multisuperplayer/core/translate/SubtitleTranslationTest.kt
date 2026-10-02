package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * 译文合并进字幕的三态语义。
 *
 * 「map 里不含这个下标」和「含但它为空」必须是**两种**结果：
 * 混成一种就会出现「用户手动删掉一条译文、一刷新又回来了」。
 */
class SubtitleTranslationTest {

    @Test
    fun `不含则不动 空则清掉 有内容则替换`() {
        val document = docOf("t0", "t1", "t2")
            .withTranslations(mapOf(0 to "零", 1 to "壹"))
        assertNull(document.cues[2].translation)

        val after = document.withTranslations(mapOf(1 to "  ", 2 to "贰"))
        assertEquals("零", after.cues[0].translation, "没提到的行必须原样保留")
        assertNull(after.cues[1].translation, "空串 = 用户删掉了这条")
        assertEquals("贰", after.cues[2].translation)
    }

    @Test
    fun `没有变化时返回原对象`() {
        val document = docOf("t0")
        assertSame(document, document.withTranslations(emptyMap()))
        // 内容相同也算没变化：否则每次进度事件都会新建一份文档，列表整屏重绘。
        assertSame(document, document.withTranslations(mapOf(0 to "  ")))
    }

    @Test
    fun `不改动轨道身份`() {
        val document = docOf("t0").withTranslations(mapOf(0 to "零"))
        assertEquals("track-1", document.track.id)
    }

    @Test
    fun `可翻下标跳过注释与空行`() {
        val document = doc(5, commentIndices = setOf(1, 3))
        assertEquals(listOf(0, 2, 4), document.translatableIndices())
    }

    @Test
    fun `已译行数统计`() {
        val cues = docOf("a", "b", "c")
            .withTranslations(mapOf(0 to "甲", 1 to ""))
            .cues
        assertEquals(1, cues.translatedCount())
    }

    @Test
    fun `下标是列表位置而不是 cue 自带序号`() {
        // 解析器可能把 index 填成原始行号；本模块其余部分全按列表位置，
        // 这里也必须一致，否则会出现"翻译没生效"的假象。
        val document = SubtitleDocument(
            track = SubtitleTrack(id = "t"),
            cues = listOf(
                SubtitleCue(index = 100, startMs = 0, endMs = 900, text = "t0"),
                SubtitleCue(index = 200, startMs = 1_000, endMs = 1_900, text = "t1"),
            ),
        )
        val translated = document.withTranslations(mapOf(0 to "零", 1 to "壹"))
        assertEquals("零", translated.cues[0].translation)
        assertEquals("壹", translated.cues[1].translation)
    }
}
