package com.multisuperplayer.feature.player

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕状态合成规则测试。
 *
 * 这里锁的唯一一条硬规则是：**任何情况下都不能显示空白字幕**。
 * 「用户选了仅译文、而这份字幕只有原文」时如果老老实实渲染译文，
 * 屏幕上什么都没有——用户会认为字幕坏了，然后去折腾一个其实没坏的东西。
 */
class SubtitleStateResolutionTest {

    private fun document(
        vararg cues: SubtitleCue,
        warnings: List<String> = emptyList(),
    ) = SubtitleDocument(
        track = SubtitleTrack(id = "test"),
        cues = cues.toList(),
        warnings = warnings,
    )

    private fun cue(
        index: Int,
        text: String,
        translation: String? = null,
    ) = SubtitleCue(
        index = index,
        startMs = index * 1_000L,
        endMs = index * 1_000L + 900L,
        text = text,
        translation = translation,
    )

    private fun loaded(
        document: SubtitleDocument?,
        hasTranslation: Boolean,
    ) = SubtitleLoadState(
        phase = SubtitlePhase.READY,
        attached = SubtitleSource(
            uri = "content://media/external/file/movie.srt",
            fileName = "movie.srt",
            format = SubtitleFormat.SRT,
            languageTag = null,
            isForced = false,
            isBilingual = false,
            sizeBytes = 100L,
            matchScore = 100,
            // 这个用例关心的是「显示模式怎么降级」，与「同分候选怎么排序」无关；
            // 这个字段只参与排序，给 0 最省事。
            trailingTagCount = 0,
        ),
        document = document,
        hasTranslation = hasTranslation,
    )

    // ------------------------------------------------------------ 仅译文降级

    @Test
    fun `仅译文但没有译文时降级显示原文并给出提示`() {
        val state = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello")), hasTranslation = false),
            displayMode = SubtitleDisplayMode.TRANSLATION_ONLY,
        )

        assertEquals(SubtitleDisplayMode.ORIGINAL_ONLY, state.effectiveMode)
        assertTrue("必须说明为什么没按用户选的模式显示", state.translationUnavailable)
        assertTrue("降级之后照样有东西可画", state.isRendering)
    }

    @Test
    fun `仅译文且有译文时原样生效`() {
        val state = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello", translation = "你好")), hasTranslation = true),
            displayMode = SubtitleDisplayMode.TRANSLATION_ONLY,
        )

        assertEquals(SubtitleDisplayMode.TRANSLATION_ONLY, state.effectiveMode)
        assertFalse(state.translationUnavailable)
    }

    @Test
    fun `字幕压根没加载成功时不能谎报「没有译文」`() {
        // 加载失败和「有字幕但没译文」是两件事，提示语完全不同：
        // 前者要说清失败原因（在 issue 里），后者才说「这份字幕没有译文」。
        val state = resolveSubtitleState(
            load = SubtitleLoadState(
                phase = SubtitlePhase.READY,
                issue = SubtitleIssue.LoadFailed("movie.srt", MspText.Plain("无法读取文件")),
            ),
            displayMode = SubtitleDisplayMode.TRANSLATION_ONLY,
        )

        assertFalse("没有字幕≠没有译文", state.translationUnavailable)
        assertFalse(state.isRendering)
    }

    @Test
    fun `其他模式下这条降级规则完全不参与`() {
        val bilingual = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello")), hasTranslation = false),
            displayMode = SubtitleDisplayMode.BILINGUAL,
        )
        val original = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello")), hasTranslation = false),
            displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
        )

        assertEquals(SubtitleDisplayMode.BILINGUAL, bilingual.effectiveMode)
        assertFalse(bilingual.translationUnavailable)
        assertEquals(SubtitleDisplayMode.ORIGINAL_ONLY, original.effectiveMode)
        assertFalse(original.translationUnavailable)
    }

    // ------------------------------------------------------------ 渲染开关

    @Test
    fun `关闭时不渲染，但字幕本身仍然留着`() {
        // 不卸载是刻意的：用户重新打开时应该立刻出现，而不是再扫一遍目录
        // 再解析一遍（几千条字幕要跑一会儿正则）。
        val state = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello")), hasTranslation = false),
            displayMode = SubtitleDisplayMode.OFF,
        )

        assertFalse(state.isRendering)
        assertEquals(1, state.cueCount)
    }

    @Test
    fun `空字幕文档不算「可渲染」`() {
        // 一个 0 条字幕的文件是解析成功了但没内容，显示空壳不如不显示。
        val state = resolveSubtitleState(
            load = loaded(document(), hasTranslation = false),
            displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
        )

        assertFalse(state.isRendering)
        assertEquals(0, state.cueCount)
    }

    @Test
    fun `还没有条目时什么都不渲染`() {
        val state = resolveSubtitleState(
            load = SubtitleLoadState(),
            displayMode = SubtitleDisplayMode.DEFAULT,
        )

        assertFalse(state.isRendering)
        assertFalse(state.isLoading)
        assertEquals(SubtitlePhase.IDLE, state.phase)
    }

    // ------------------------------------------------------------ 速率带进状态

    @Test
    fun `速率被带进状态里`() {
        // 覆盖层（`SubtitleOverlay`）和歌词页（`LyricsPane`）都从 `SubtitleUiState`
        // 取速率。这一格要是没接上，界面上数字会变、字幕却一动不动——
        // 而「按了没反应」是最难查的一类：没有任何日志、没有任何报错。
        val state = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello")), hasTranslation = false),
            displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
            subtitleRatePermille = 1_040,
        )

        assertEquals(1_040, state.subtitleRatePermille)
    }

    @Test
    fun `不传速率时默认原速`() {
        val state = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello")), hasTranslation = false),
            displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
        )

        assertEquals(SUBTITLE_RATE_BASE_PERMILLE, state.subtitleRatePermille)
    }

    @Test
    fun `速率只改时间映射，不影响其他判定`() {
        // 速率是「同一份字幕换个时间轴」，不是「换一份字幕」：
        // 可渲染性、条数、显示模式都不该跟着变。
        val slow = resolveSubtitleState(
            load = loaded(document(cue(0, "Hello")), hasTranslation = false),
            displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
            subtitleRatePermille = 960,
        )

        assertTrue(slow.isRendering)
        assertEquals(1, slow.cueCount)
        assertEquals(SubtitleDisplayMode.ORIGINAL_ONLY, slow.effectiveMode)
    }

    @Test
    fun `扫描和加载阶段都要能让界面显示进度`() {
        val scanning = resolveSubtitleState(
            load = SubtitleLoadState(phase = SubtitlePhase.SCANNING),
            displayMode = SubtitleDisplayMode.DEFAULT,
        )
        val loading = resolveSubtitleState(
            load = SubtitleLoadState(phase = SubtitlePhase.LOADING),
            displayMode = SubtitleDisplayMode.DEFAULT,
        )
        val ready = resolveSubtitleState(
            load = SubtitleLoadState(phase = SubtitlePhase.READY),
            displayMode = SubtitleDisplayMode.DEFAULT,
        )

        assertTrue(scanning.isLoading)
        assertTrue(loading.isLoading)
        assertFalse(ready.isLoading)
    }

    @Test
    fun `解析告警要透到界面`() {
        val state = resolveSubtitleState(
            load = loaded(
                document(cue(0, "Hello"), warnings = listOf("跳过了 3 行坏数据")),
                hasTranslation = false,
            ),
            displayMode = SubtitleDisplayMode.ORIGINAL_ONLY,
        )

        assertEquals(listOf("跳过了 3 行坏数据"), state.warnings)
    }

    // ------------------------------------------------------------ 译文判定

    @Test
    fun `全空白的译文不算译文`() {
        // 解析器碰到 `Hello\n   ` 时可能给出一条只有空白的译文。把它当成
        // 「有译文」会让「仅译文」模式显示一片空白——正是要防的那个结果。
        val onlyBlank = document(
            cue(0, "Hello", translation = "   "),
            cue(1, "World", translation = ""),
        )

        assertFalse(onlyBlank.hasTranslation())

        val state = resolveSubtitleState(
            load = loaded(onlyBlank, hasTranslation = onlyBlank.hasTranslation()),
            displayMode = SubtitleDisplayMode.TRANSLATION_ONLY,
        )
        assertTrue(state.translationUnavailable)
        assertTrue(state.isRendering)
    }

    @Test
    fun `只要有一条译文就算有译文`() {
        val partial = document(
            cue(0, "Hello", translation = null),
            cue(1, "World", translation = "世界"),
        )

        assertTrue("部分翻译也是翻译，缺的那几条按原文显示即可", partial.hasTranslation())
    }

    @Test
    fun `没有任何 cue 时不算有译文`() {
        assertFalse(document().hasTranslation())
    }
}
