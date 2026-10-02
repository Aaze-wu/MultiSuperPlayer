package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏音频版面规则测试。
 *
 * 锁的两条规则，都是「错了也不会报错」的那种：
 *
 * 1. **封面尺寸**：算大了会把标题和出口顶出屏幕（横屏高度只有 400 多 dp），
 *    算小了只是小一圈。所以这里钉的是「永远放得下」，不是「足够大」。
 * 2. **右栏该画什么**：四种「没有歌词」的情况必须给出四句不同的话。
 *    合并任意两种都会让一部分用户读到一句与自己的处境不符的说明——
 *    屏幕上看不出异常，只是会把人带去做一件没用的事。
 */
class AudioLandscapeRulesTest {

    // ---------------------------------------------------------------- 封面尺寸

    /** 1080×2400 @420dpi 横过来，左栏（屏宽 1/3）大约就是这个尺寸。 */
    private val columnWidth = 305.dp
    private val columnHeight = 411.dp

    @Test
    fun `高度是瓶颈时按高度算`() {
        val size = AudioLandscapeRules.artworkSize(columnWidth, columnHeight)

        assertEquals(columnHeight - AudioLandscapeRules.INFO_RESERVE, size)
        assertTrue("封面 + 预留文字必须放得下", size + AudioLandscapeRules.INFO_RESERVE <= columnHeight)
    }

    @Test
    fun `宽度是瓶颈时按宽度算`() {
        // 窄而高的窗口（比如左边只分到很窄的一条）：这时封面该跟着宽度缩。
        val narrow = 200.dp
        val size = AudioLandscapeRules.artworkSize(narrow, 800.dp)

        assertEquals(narrow - AudioLandscapeRules.SIDE_INSET * 2, size)
    }

    @Test
    fun `封面加左右留白不会超出这一栏的宽度`() {
        val size = AudioLandscapeRules.artworkSize(columnWidth, columnHeight)

        assertTrue(
            "左栏只有 1/3 屏宽，封面加留白宽出去就会把右栏挤掉",
            size + AudioLandscapeRules.SIDE_INSET * 2 <= columnWidth,
        )
    }

    @Test
    fun `可用空间越小时封面只会变小`() {
        val normal = AudioLandscapeRules.artworkSize(columnWidth, columnHeight)
        val shorter = AudioLandscapeRules.artworkSize(columnWidth, columnHeight - 60.dp)

        assertTrue(shorter < normal)
        assertTrue(shorter > 0.dp)
    }

    @Test
    fun `退化输入返回 0 而不是负数`() {
        // 极小的窗口（分屏、小窗模式）里「宽度允许」和「高度允许」都会算成负数。
        // 负数被拿去 `Modifier.size()` 会直接崩，所以这条兜底是必须的。
        assertEquals(0.dp, AudioLandscapeRules.artworkSize(40.dp, 40.dp))
        assertEquals(0.dp, AudioLandscapeRules.artworkSize(0.dp, columnHeight))
        assertEquals(0.dp, AudioLandscapeRules.artworkSize(columnWidth, 0.dp))
        assertEquals(0.dp, AudioLandscapeRules.artworkSize(-10.dp, -10.dp))
    }

    // -------------------------------------------------------------- 右栏内容

    private fun document(vararg cues: SubtitleCue) = SubtitleDocument(
        track = SubtitleTrack(id = "test"),
        cues = cues.toList(),
    )

    private fun cue(index: Int) = SubtitleCue(
        index = index,
        startMs = index * 1_000L,
        endMs = index * 1_000L + 900L,
        text = "第 $index 行",
    )

    private fun lines(slot: LyricsSlot): SubtitleDocument {
        assertTrue("这一条应该画歌词，实际是 $slot", slot is LyricsSlot.Lines)
        return (slot as LyricsSlot.Lines).document
    }

    private fun notice(slot: LyricsSlot): LyricsPlaceholder {
        assertTrue("这一条不该画歌词，实际是 $slot", slot is LyricsSlot.Notice)
        return (slot as LyricsSlot.Notice).kind
    }

    @Test
    fun `有歌词且没关掉时直接画歌词`() {
        val document = document(cue(0), cue(1))

        val slot = AudioLandscapeRules.lyricsSlot(
            SubtitleUiState(
                effectiveMode = SubtitleDisplayMode.ORIGINAL_ONLY,
                document = document,
            ),
        )

        assertEquals("必须画的是同一份歌词，不是复制一份", document, lines(slot))
    }

    @Test
    fun `加载中不算失败`() {
        // 扫描期间上一次的 issue 可能还挂在那儿。先判失败就是把一次还没结束的
        // 尝试判成死刑，用户会去做一次没有意义的「重新选择字幕」。
        val slot = AudioLandscapeRules.lyricsSlot(
            SubtitleUiState(
                phase = SubtitlePhase.SCANNING,
                issue = SubtitleIssue.LoadFailed("a.lrc", MspText.Plain("上一首的失败")),
            ),
        )

        assertEquals(LyricsPlaceholder.LOADING, notice(slot))
    }

    @Test
    fun `把字幕关掉和压根没有字幕是两句话`() {
        val off = AudioLandscapeRules.lyricsSlot(
            SubtitleUiState(
                effectiveMode = SubtitleDisplayMode.OFF,
                document = document(cue(0)),
            ),
        )
        val empty = AudioLandscapeRules.lyricsSlot(SubtitleUiState())

        assertEquals(LyricsPlaceholder.OFF, notice(off))
        assertEquals(LyricsPlaceholder.EMPTY, notice(empty))
        assertNotEquals(
            "「你把它关了」和「本来就没有」的用户下一步动作不一样，说法也必须不一样",
            LyricsPlaceholder.OFF.messageRes,
            LyricsPlaceholder.EMPTY.messageRes,
        )
    }

    @Test
    fun `读到了文件但一行歌词都没有算没有`() {
        // 这是最容易被归错类的一种：attached/document 都在，只是 cues 是空的
        // （纯音乐、或者这份文件里真的没有时间轴）。它不是「歌词已关闭」——
        // 用户会去开一个本来就开着的开关，然后发现还是什么都没有。
        val slot = AudioLandscapeRules.lyricsSlot(
            SubtitleUiState(
                effectiveMode = SubtitleDisplayMode.ORIGINAL_ONLY,
                document = document(),
            ),
        )

        assertEquals(LyricsPlaceholder.EMPTY, notice(slot))
    }

    @Test
    fun `加载失败有自己的一句话`() {
        val slot = AudioLandscapeRules.lyricsSlot(
            SubtitleUiState(
                phase = SubtitlePhase.READY,
                issue = SubtitleIssue.LoadFailed("a.lrc", MspText.Plain("解析不了")),
            ),
        )

        assertEquals(LyricsPlaceholder.FAILED, notice(slot))
    }

    @Test
    fun `目录里没有字幕文件只是没有 不是失败`() {
        // 真机上抓到的：这首歌旁边根本没有 .lrc，屏幕却写着「歌词加载失败」。
        // 原因就是 `NoSubtitles` 也填在 `issue` 里，而第一版把「issue 非空」当成失败。
        // 这是最常见的一种**正常**情况（绝大部分歌就是没有歌词文件），判错等于把
        // 绝大多数歌曲都报成故障，而且它还挡着上面那句「选择字幕与歌词」——用户会去
        // 排查一个不存在的问题。
        val slot = AudioLandscapeRules.lyricsSlot(
            SubtitleUiState(
                phase = SubtitlePhase.READY,
                issue = SubtitleIssue.NoSubtitles,
            ),
        )

        assertEquals(LyricsPlaceholder.EMPTY, notice(slot))
    }

    @Test
    fun `有字幕文件却一个都对不上 也算没有`() {
        // 和上一条同一类，只是「找过」的程度不同：目录里有字幕，只是片名对不上。
        // 用户的下一步都是「手动选一条」，所以说法也用同一句。
        val slot = AudioLandscapeRules.lyricsSlot(
            SubtitleUiState(
                phase = SubtitlePhase.READY,
                issue = SubtitleIssue.NoMatch,
            ),
        )

        assertEquals(LyricsPlaceholder.EMPTY, notice(slot))
    }

    @Test
    fun `每一条 issue 都明确属于没有 或者属于失败`() {
        // `issue` 非空**不等于**出错，所以「哪一条算失败」必须逐条写下来。
        // 表格化的意义就在这里：以后 `SubtitleIssue` 加一个分支，生产代码里的 `when`
        // 会编译不过（提醒你它是哪一类），而这条用例把现有六条的分类钉住，
        // 免得有人后来把某条「看过了没有」挪进失败那一侧。
        val cases = listOf<Pair<SubtitleIssue, LyricsPlaceholder>>(
            SubtitleIssue.NoSubtitles to LyricsPlaceholder.EMPTY,
            SubtitleIssue.NoMatch to LyricsPlaceholder.EMPTY,
            SubtitleIssue.NoDirectory to LyricsPlaceholder.FAILED,
            SubtitleIssue.DirectoryInvisible to LyricsPlaceholder.FAILED,
            SubtitleIssue.ScanFailed(MspText.Plain("查询炸了")) to LyricsPlaceholder.FAILED,
            SubtitleIssue.LoadFailed("a.lrc", MspText.Plain("解析不了")) to LyricsPlaceholder.FAILED,
        )

        cases.forEach { (issue, expected) ->
            val slot = AudioLandscapeRules.lyricsSlot(
                SubtitleUiState(phase = SubtitlePhase.READY, issue = issue),
            )

            assertEquals("$issue 该算「$expected」", expected, notice(slot))
        }
    }

    @Test
    fun `四种情况的文案两两不同`() {
        val ids = LyricsPlaceholder.entries.map { it.messageRes }

        assertEquals("四种情况四种说法，不能有一个是重复的", ids.size, ids.toSet().size)
        assertTrue("每一条都必须真的有文案", ids.none { it == 0 })
    }
}
