package com.multisuperplayer.feature.player

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.subtitleSourceOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「候选为空时那句『这个文件夹里没有找到可用的字幕文件』该不该出现」。
 *
 * ## 为什么值得单独测
 *
 * 这句话不是一句描述，而是一个**断言**：我查过了，那个文件夹里没有能用的字幕。
 * 原来的条件是 `candidates.isEmpty() && !isLoading`——**完全不看 `issue`**，
 * 于是「查不到目录」的媒体会同时显示两句：
 *
 * - 「无法确定这个文件所在的文件夹，没法自动找同名字幕。」（上面那句 issue）
 * - 「这个文件夹里没有找到可用的字幕文件。」（下面那句占位）
 *
 * 上面说「我不知道是哪个文件夹」，下面说「那个文件夹里没有」——同一屏自相矛盾。
 * 代价不是难看，而是**误导**：用户会照后者去反复改字幕文件名，而真正的问题在权限/
 * 来路上，怎么改都没用。真机上就是这么看到的。
 *
 * 今天还剩下谁会落到 `NoDirectory`？只有 Android 9 及以下的媒体库条目
 * （系统没给 `RELATIVE_PATH` 这一列）。文件浏览器打开的条目已经改走「直接列目录」
 * 那一支了，所以下面的用例是**直接构造**这个状态来钉规则，而不是靠某条真实来源
 * 必然产生它——真来源将来再变，这条规则本身仍然成立。
 *
 * `issue == NoSubtitles` 时同理：issue 已经说了「这个文件夹里没有字幕文件」，
 * 占位再补一句只是重复。
 */
class SubtitleEmptyHintTest {

    private val candidate: SubtitleSource = subtitleSourceOf(
        "/sdcard/Movies/Show.chs.srt",
        "Show.chs.srt",
    )

    @Test
    fun `没有 issue 而且真的没有候选时才提示`() {
        val state = SubtitleUiState(
            phase = SubtitlePhase.READY,
            candidates = emptyList(),
            issue = null,
        )

        assertTrue(showsNoUsableSubtitleHint(state))
    }

    @Test
    fun `拿不到目录时不提示 上面的 issue 已经解释了`() {
        val state = SubtitleUiState(
            phase = SubtitlePhase.READY,
            candidates = emptyList(),
            issue = SubtitleIssue.NoDirectory,
        )

        assertFalse(showsNoUsableSubtitleHint(state))
    }

    @Test
    fun `目录不可见时不提示 原因是没有权限`() {
        val state = SubtitleUiState(
            phase = SubtitlePhase.READY,
            candidates = emptyList(),
            issue = SubtitleIssue.DirectoryInvisible,
        )

        assertFalse(showsNoUsableSubtitleHint(state))
    }

    @Test
    fun `扫描出错时不提示 原因是出错不是没有`() {
        val state = SubtitleUiState(
            phase = SubtitlePhase.READY,
            candidates = emptyList(),
            issue = SubtitleIssue.ScanFailed(MspText.Plain("boom")),
        )

        assertFalse(showsNoUsableSubtitleHint(state))
    }

    @Test
    fun `文件夹里确实没字幕时不重复提示`() {
        val state = SubtitleUiState(
            phase = SubtitlePhase.READY,
            candidates = emptyList(),
            issue = SubtitleIssue.NoSubtitles,
        )

        assertFalse(showsNoUsableSubtitleHint(state))
    }

    @Test
    fun `读失败了也不提示 那不是没有可用字幕`() {
        val state = SubtitleUiState(
            phase = SubtitlePhase.READY,
            candidates = emptyList(),
            issue = SubtitleIssue.LoadFailed("Show.chs.srt", MspText.Plain("bad bytes")),
        )

        assertFalse(showsNoUsableSubtitleHint(state))
    }

    @Test
    fun `加载中不提示 还不知道有没有`() {
        val state = SubtitleUiState(phase = SubtitlePhase.SCANNING, candidates = emptyList())

        assertFalse(showsNoUsableSubtitleHint(state))
    }

    @Test
    fun `有候选时不提示`() {
        val state = SubtitleUiState(phase = SubtitlePhase.READY, candidates = listOf(candidate))

        assertFalse(showsNoUsableSubtitleHint(state))
    }
}
