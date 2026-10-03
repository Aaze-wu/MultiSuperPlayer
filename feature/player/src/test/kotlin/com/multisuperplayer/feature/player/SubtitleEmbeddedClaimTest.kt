package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleTrack
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspTrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌字幕轨**什么时候被认下来**，以及认领之后哪些字段必须跟着动。
 *
 * ## 为什么必须单独钉住这个函数
 *
 * `withEmbedded` 是「容器里的字幕」进入界面的唯一一道门，而它在加这份测试之前
 * **一条直接断言都没有**——只有 [SubtitleEmbeddedTrackTextTest] 顺手借它造文档。
 * 于是一个错了很多版本的判断躲在里面没被发现：
 *
 * ```kotlin
 * if (cues.isEmpty() && manual == null) return this   // 旧实现
 * ```
 *
 * 「一行都没读到就先按兵不动」，代价是**实测过的一场自相矛盾**（`multi.mkv`，
 * 40 秒片段、字幕从第 23 秒起）：`20:26:47` 切条目 → `20:26:48` 扫描算出
 * 「文件夹里没有和片名同名的字幕」→ `20:26:53` 内嵌字幕才读到第 1 行。中间约
 * **4.8 秒**里，面板上面写着「未加载外挂字幕文件。」+「文件夹里没有和片名同名的
 * 字幕。」，下面「片源自带的字幕」分区里列着两条可选轨——而内核**已经选了其中一条**。
 *
 * 修法不是改文案（v0.5.17 就是这么修的，改完那句新文案一次都没渲染过），
 * 而是**让认领提前到第一句台词之前**：轨在轨道清单解析出来那一刻就知道了，
 * 那一刻就该说出「用的是这条」。
 *
 * ## 断言里被钉住的四条规则
 *
 * 1. `cues` 为空**也要认领**，且 `document` 保持 null（「已认领、还没读到」是合法中间态，
 *    不是一个空文档）。
 * 2. 认领时 `issue` 必须清掉，而且**手选和自动两条路都要清**——清空那句原本写在
 *    「空 cues 就 return」后面，手选那条路根本执行不到，于是手选内嵌轨之后
 *    `NoMatch` / `NoSubtitles` 会一直挂在面板上。
 * 3. 外挂字幕优先：已经挂上外挂时**自动兑底不认领**（`takeIf` 要求
 *    `attached == null && document == null`）。
 * 4. 手选永远赢：用户亲手指了内嵌那条，已经挂着的外挂就下架。
 */
class SubtitleEmbeddedClaimTest {

    private fun track(id: String = "TEXT:3:0", language: String? = "zh") = MspTrackInfo(
        id = id,
        kind = MspTrackKind.TEXT,
        language = language,
        mimeType = "application/x-media3-cues",
        codec = "application/x-subrip",
    )

    private fun cue(
        index: Int,
        text: String = "line $index",
        translation: String? = null,
    ) = SubtitleCue(
        index = index,
        startMs = index * 1_000L,
        endMs = index * 1_000L + 900L,
        text = text,
        translation = translation,
    )

    private fun document(vararg cues: SubtitleCue) = SubtitleDocument(
        track = SubtitleTrack(id = "test"),
        cues = cues.toList(),
    )

    private fun external(fileName: String = "movie.srt") = SubtitleSource(
        uri = "content://media/external/file/$fileName",
        fileName = fileName,
        format = SubtitleFormat.SRT,
        languageTag = null,
        isForced = false,
        isBilingual = false,
        sizeBytes = 100L,
        matchScore = 100,
        trailingTagCount = 0,
    )

    /** 「自动」+ 什么都没挂 + 扫描已经结束：内核选中的那条轨这时候就该被认领。 */
    private val armed = SubtitleLoadState(phase = SubtitlePhase.READY)

    // ------------------------------------------------------------ 认领的时机

    @Test
    fun `还没读到第一句台词时也先把轨认下来`() {
        val result = armed.withEmbedded(
            autoTrack = track(),
            cues = emptyList(),
            mediaUri = "file:///m.mkv",
        )

        assertEquals("轨必须立刻认领，否则面板会继续说「什么都没挂上」", track(), result.embeddedTrack)
        assertNull(
            "认领时不该造一个空文档：`document == null` 才是「还没读到台词」的表示",
            result.document,
        )
        assertFalse(result.hasTranslation)
    }

    @Test
    fun `读到台词之后才造文档并算译文`() {
        val result = armed.withEmbedded(
            autoTrack = track(),
            cues = listOf(cue(0, "Hello"), cue(1, "World", translation = "世界")),
            mediaUri = "file:///m.mkv",
        )

        assertEquals(2, result.document?.cues?.size)
        // hasTranslation 是加载时算一次存起来的，这里必须跟着**新的** document 走。
        assertTrue("有一条带译文就该认出来", result.hasTranslation)
    }

    @Test
    fun `一行译文都没有时不谎报有译文`() {
        val result = armed.withEmbedded(
            autoTrack = track(),
            cues = listOf(cue(0, "Hello")),
            mediaUri = null,
        )

        assertNotNull(result.document)
        assertFalse(result.hasTranslation)
    }

    // ------------------------------------------------------------ issue 的清理

    @Test
    fun `自动认领时扫描算出的 issue 必须清掉`() {
        val result = SubtitleLoadState(phase = SubtitlePhase.READY, issue = SubtitleIssue.NoMatch)
            .withEmbedded(autoTrack = track(), cues = emptyList(), mediaUri = null)

        assertNull(
            "屏幕上有字幕、面板里却写着「文件夹里没有和片名同名的字幕」",
            result.issue,
        )
    }

    @Test
    fun `手选内嵌轨时扫描算出的 issue 也要清掉`() {
        // 这一条曾经**永远为假**：旧实现里「空 cues 就 return this」写在清空那句之前，
        // 手选（embeddedTrack 非 null）走的是空 cues 那条路时直接返回，`issue` 留着。
        // 症状是手选内嵌轨之后「文件夹里没有和片名同名的字幕」一直挂在面板上下不来。
        val result = SubtitleLoadState(
            phase = SubtitlePhase.READY,
            embeddedTrack = track(),
            issue = SubtitleIssue.NoSubtitles,
        ).withEmbedded(autoTrack = null, cues = emptyList(), mediaUri = null)

        assertEquals(track(), result.embeddedTrack)
        assertNull(result.issue)
    }

    // ------------------------------------------------------------ 两条来源的优先级

    @Test
    fun `已经挂上外挂字幕时不会被内嵌轨顶掉`() {
        val original = SubtitleLoadState(
            phase = SubtitlePhase.READY,
            attached = external(),
            document = document(cue(0, "Hello")),
        )

        val result = original.withEmbedded(
            autoTrack = track(),
            cues = listOf(cue(0, "容器的字幕")),
            mediaUri = null,
        )

        // 两个都自动挂上会让屏幕上同时出现两份字幕，而它们的时间轴还可能不一样。
        // 认领根本不该发生：状态原样返回（同一个实例）。
        assertSame("外挂优先，连状态都不该动", original, result)
    }

    @Test
    fun `手选的内嵌轨会顶掉已经挂着的外挂字幕`() {
        val result = SubtitleLoadState(
            phase = SubtitlePhase.READY,
            attached = external(),
            document = document(cue(0, "Hello")),
            embeddedTrack = track(),
        ).withEmbedded(autoTrack = null, cues = listOf(cue(0, "容器的字幕")), mediaUri = null)

        assertNull("用户亲手指了内嵌那条，外挂就该下架", result.attached)
        assertEquals(track(), result.embeddedTrack)
        assertEquals("容器的字幕", result.document?.cues?.first()?.text)
    }

    @Test
    fun `用户关掉自动之后内核选中的轨不该被认领`() {
        // 「不要自动」是用户明确说的：这时认领一条他没选过的轨，
        // 就成了「设置被无视」。候选列表里仍然列着它，他自己点。
        val original = SubtitleLoadState(phase = SubtitlePhase.READY, autoSelected = false)

        assertSame(
            original,
            original.withEmbedded(autoTrack = track(), cues = listOf(cue(0)), mediaUri = null),
        )
    }

    @Test
    fun `扫描还没结束时不认领`() {
        // 扫描期间面板显示的是「正在查找字幕…」，这时候认领会让状态先跳一下。
        // 认领的时机是**扫描得出结果之后**，而那仍然早于第一句台词（这正是修复的要点）。
        val original = SubtitleLoadState(phase = SubtitlePhase.SCANNING)

        assertSame(
            original,
            original.withEmbedded(autoTrack = track(), cues = emptyList(), mediaUri = null),
        )
    }

    @Test
    fun `内核一条轨都没选中时什么都不改`() {
        assertSame(
            armed,
            armed.withEmbedded(autoTrack = null, cues = listOf(cue(0)), mediaUri = null),
        )
    }
}
