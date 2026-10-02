package com.multisuperplayer.feature.player

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack
import com.multisuperplayer.core.translate.CueFailure
import com.multisuperplayer.core.translate.TranslationEditsCodec
import com.multisuperplayer.core.translate.TranslationFailure
import com.multisuperplayer.core.translate.withTranslations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译状态层里那些纯函数的规则。
 *
 * 这些函数碰不到网络、缓存、DataStore，所以用最朴素的 JUnit 测。
 * 它们才是「用户看到的那几行字对不对」的最终决定者，
 * 上层界面只是把这些结果画出来。
 */
class SubtitleTranslationControllerTest {

    // ------------------------------------------------------------ 人工修正

    @Test
    fun `人工修正按行号加原文摘要定位，换了字幕版本不会错位`() {
        val cues = cuesOf("你好", "再见")

        // 只有第一行的键（行号 + 原文摘要）对得上。
        val edits = mapOf(
            TranslationEditsCodec.key(0, "你好") to "Hello",
            // 行号同样是 0，但原文变了 —— 这是「另一版字幕的第一行」。
            TranslationEditsCodec.key(0, "早上好") to "Good morning",
        )

        val applied = manualTranslations(cues, edits)

        assertEquals(mapOf(0 to "Hello"), applied)
    }

    @Test
    fun `人工修正认行号，同一句原文出现在两行时各归各行`() {
        val cues = cuesOf("嗯", "嗯")

        val edits = mapOf(
            TranslationEditsCodec.key(1, "嗯") to "Yeah",
        )

        val applied = manualTranslations(cues, edits)

        assertEquals(mapOf(1 to "Yeah"), applied)
    }

    @Test
    fun `空白的修正值不留空壳`() {
        val cues = cuesOf("你好")

        val applied = manualTranslations(cues, mapOf(TranslationEditsCodec.key(0, "你好") to ""))

        // 空串仍然是一条存在的修正（“我就是要把这句清空”），
        // 该不该显示由下游的三态语义决定，这里不替它做决定。
        assertEquals(mapOf(0 to ""), applied)
    }

    // ------------------------------------------------------------ 翻译范围

    @Test
    fun `翻到当前位置只翻到那一句为止`() {
        // 每行 1s 起、持续 0.9s：t0 = [0,900) t1 = [1000,1900) t2 = [2000,2900)
        val document = doc(5)

        assertEquals(listOf(0, 1, 2), translatableUpTo(document, positionMs = 2_000L))
        // 还没开始播（位置在任何一行之前）时没有东西要翻。
        assertEquals(emptyList<Int>(), translatableUpTo(document, positionMs = -1L))
    }

    @Test
    fun `翻到当前位置会跳过已经有译文的行`() {
        val document = doc(4).withTranslations(mapOf(0 to "零", 1 to "一"))

        // 0/1 已经有译文了。若不过滤，进度条会在每次「翻译到当前位置」时
        // 从 0 重新爬一遍——已有的译文被重复计入「待翻译」。
        assertEquals(listOf(2, 3), translatableUpTo(document, positionMs = 10_000L))
    }

    @Test
    fun `翻到当前位置会跳过注释行`() {
        val document = doc(3, commentIndices = setOf(1))

        assertEquals(listOf(0, 2), translatableUpTo(document, positionMs = 10_000L))
    }

    @Test
    fun `翻到当前位置会跳过空行`() {
        val document = docOf("", "有内容", "  ")

        assertEquals(listOf(1), translatableUpTo(document, positionMs = 10_000L))
    }

    @Test
    fun `自动翻译的窗口只覆盖当前位置往后的一小段`() {
        val document = doc(40)

        val window = translatableWindow(document, positionMs = 5_000L, window = 3)

        // 5s 落在 t5 上（5_000 in [5000,5900)）。
        assertEquals(listOf(5, 6, 7), window)
    }

    @Test
    fun `自动翻译的窗口在片子末尾不会越界`() {
        val document = doc(3)

        val window = translatableWindow(document, positionMs = 2_100L, window = 10)

        assertEquals(listOf(2), window)
    }

    @Test
    fun `当前位置落在两行之间时从下一行开始`() {
        // 1950ms 已经过了 t1 的结束（1900）还没到 t2 的开始（2000）。
        val document = doc(5)

        assertEquals(listOf(2, 3), translatableWindow(document, positionMs = 1_950L, window = 2))
    }

    @Test
    fun `自动翻译的窗口跳过注释空行和已有译文`() {
        val document = docOf("a", "b", "c", "d", commentIndices = setOf(1))
            .withTranslations(mapOf(2 to "已有"))

        val window = translatableWindow(document, positionMs = 0L, window = 4)

        assertEquals(listOf(0, 3), window)
    }

    @Test
    fun `自动翻译窗口的默认宽度是个正数`() {
        // 这个常量被用来决定「一次自动翻译请多少行」，
        // 变成 0 或负数时窗口会永远空——功能静默失效，没有任何报错。
        assertTrue(AUTO_TRANSLATE_WINDOW > 0)
    }

    // ------------------------------------------------------ 译文的合成与隔离

    @Test
    fun `人工修正压过模型译文`() {
        val texts = TranslationTexts(
            token = "a.srt",
            fromModel = mapOf(0 to "模型译的"),
            manual = mapOf(0 to "我改的"),
        )

        assertEquals("我改的", texts.effective[0])
    }

    @Test
    fun `没有人工修正时直接用模型译文`() {
        val texts = TranslationTexts(token = "a.srt", fromModel = mapOf(0 to "模型译的"))

        assertEquals(mapOf(0 to "模型译的"), texts.effective)
    }

    @Test
    fun `token 对不上时宁可显示原文也不显示别人的译文`() {
        val document = doc(2)
        val texts = TranslationTexts(token = "b.srt", fromModel = mapOf(0 to "这是另一部片子的译文"))

        val applied = texts.appliedTo(document, token = "a.srt")

        // 关键：不是「清空译文」，而是**原样返回**。
        assertSame(document, applied)
        assertNull(applied?.cues?.first()?.translation)
    }

    @Test
    fun `token 对得上时把译文贴到对应的行上`() {
        val document = doc(3)
        val texts = TranslationTexts(token = "a.srt", fromModel = mapOf(1 to "一", 2 to "二"))

        val applied = texts.appliedTo(document, token = "a.srt")

        assertNull(applied?.cues?.get(0)?.translation)
        assertEquals("一", applied?.cues?.get(1)?.translation)
        assertEquals("二", applied?.cues?.get(2)?.translation)
    }

    @Test
    fun `文档为空时贴不出东西来`() {
        assertNull(TranslationTexts(token = "a.srt").appliedTo(null, token = "a.srt"))
    }

    // ------------------------------------------------------------------ 进度

    @Test
    fun `总数为零时进度是零而不是除零`() {
        assertEquals(0f, TranslationUiState().progress, 0.0001f)
    }

    @Test
    fun `进度是已完成除以总数`() {
        assertEquals(0.5f, TranslationUiState(done = 3, total = 6).progress, 0.0001f)
    }

    @Test
    fun `有失败行时也还能算失败条数`() {
        val state = TranslationUiState(
            failures = listOf(
                CueFailure(0, TranslationFailure.Network("x")),
                CueFailure(1, TranslationFailure.Network("x")),
            ),
        )

        assertEquals(2, state.failedCount)
        assertFalse(state.active)
    }

    @Test
    fun `正在跑或者有中止原因时算活跃`() {
        assertTrue(TranslationUiState(running = true).active)
        assertTrue(TranslationUiState(failure = TranslationFailure.Network("x")).active)
    }

    // ---------------------------------------------------------------- 夹具

    private fun cuesOf(vararg texts: String): List<SubtitleCue> = texts.mapIndexed { index, text ->
        SubtitleCue(
            index = index,
            startMs = index * 1_000L,
            endMs = index * 1_000L + 900L,
            text = text,
        )
    }

    /** 文本为 `t<下标>` 的字幕，方便直接对照行号。 */
    private fun doc(n: Int, commentIndices: Set<Int> = emptySet()): SubtitleDocument =
        docOf(*Array(n) { "t$it" }, commentIndices = commentIndices)

    private fun docOf(
        vararg texts: String,
        commentIndices: Set<Int> = emptySet(),
    ): SubtitleDocument = SubtitleDocument(
        track = SubtitleTrack(id = "track-1"),
        cues = texts.mapIndexed { index, text ->
            SubtitleCue(
                index = index,
                startMs = index * 1_000L,
                endMs = index * 1_000L + 900L,
                text = text,
                isComment = index in commentIndices,
            )
        },
    )
}
