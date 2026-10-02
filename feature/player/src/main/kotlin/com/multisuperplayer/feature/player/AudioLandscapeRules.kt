package com.multisuperplayer.feature.player

import androidx.annotation.StringRes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.multisuperplayer.core.model.SubtitleDocument

/**
 * 横屏音频页的两条版面规则。
 *
 * 抽成纯函数而不是直接写在组合函数里，理由是这两条都不是「画什么」而是「**怎么判断**」：
 *
 * 1. 封面该多大——它同时受宽度、高度和旁边那两行文字约束，写死在布局里就得靠
 *    实机一次次试；写成函数之后「窗口只有 60dp 高」这种退化输入也有确定答案。
 * 2. 右栏该画歌词还是画提示语——**四种情况必须给出四种不同的说法**，
 *    合并任意两种都会让用户看到一句不成立的话（详见 [LyricsPlaceholder]）。
 *
 * 这也让它们能被 JVM 单测覆盖：本模块里 Compose 布局没法在单测里跑，
 * 但「判断」可以。
 */
internal object AudioLandscapeRules {

    /**
     * 封面左右两侧各留的空白。放在 weight(1f) 那一栏里，这一栏本来是屏宽的 1/3。
     *
     * `internal` 而不是 `private`：单测要用同一组数字去校验「放得下」，
     * 抄一遍就多了一处需要同步的副本。
     */
    internal val SIDE_INSET = 24.dp

    /**
     * 封面下面那两行文字（标题最多两行 + 格式/解码方式一行）以及左上角出口胶囊的预留高度。
     *
     * 这个数偏大一点没关系：封面小一圈只是小一圈，而少了这几 dp 会让文字被挤出屏幕。
     */
    internal val INFO_RESERVE = 168.dp

    /**
     * 封面边长。
     *
     * 取「宽度允许」和「高度允许」里更小的那个，所以封面永远是个正方形、也永远放得下。
     * 不做 `coerceAtLeast(最小尺寸)` 那种「保证不会太小」的兜底：在窄窗口里那会让封面
     * **反过来溢出**，而溢出会把标题和出口胶囊顶出屏幕——宁可封面小得看不见。
     *
     * @return 0..min(可用宽, 可用高)，退化输入（0 或负的可用空间）返回 0。
     */
    fun artworkSize(availableWidth: Dp, availableHeight: Dp): Dp {
        if (availableWidth <= 0.dp || availableHeight <= 0.dp) return 0.dp
        return min(availableWidth - SIDE_INSET * 2, availableHeight - INFO_RESERVE)
            .coerceAtLeast(0.dp)
    }

    /**
     * 右栏现在应该画什么。
     *
     * @return [LyricsSlot.Lines] 表示可以正常画歌词；[LyricsSlot.Notice] 表示这块地方
     *   没有歌词可画，要显示一句说明和一个选择入口。
     */
    fun lyricsSlot(state: SubtitleUiState): LyricsSlot {
        val document = state.document
        val issue = state.issue
        // 顺序就是这个判断的全部内容：
        //
        // - 「能画」最先判，因为它是唯一一种不需要解释的情况；
        // - 「正在加载」排在「加载失败」前面：加载中 `issue` 可能还挂着上一次的失败，
        //   先说「失败」就是把一次还没结束的尝试判成死刑；
        // - **只有「没能去看」才算失败**，见 [isFailure]；
        // - 「有字幕但被关掉了」和「压根没有字幕」是两件事：前者用户只要打开就行，
        //   后者得去选一条。两句话必须不一样，否则「字幕已关闭」的用户会去翻
        //   文件选择器找一个本来就挂着的字幕。
        return when {
            state.isRendering -> LyricsSlot.Lines(requireNotNull(document))
            state.isLoading -> LyricsSlot.Notice(LyricsPlaceholder.LOADING)
            issue != null && issue.isFailure() -> LyricsSlot.Notice(LyricsPlaceholder.FAILED)
            document != null && !document.isEmpty -> LyricsSlot.Notice(LyricsPlaceholder.OFF)
            else -> LyricsSlot.Notice(LyricsPlaceholder.EMPTY)
        }
    }

    /**
     * 这条 issue 是「出错了」，还是只是「找过了，没有」。
     *
     * 这个区分必须先做，因为 **`issue` 非空并不等于出错**：`NoSubtitles`（目录里没有字幕文件）
     * 和 `NoMatch`（有字幕文件但没一个对得上片名）都是正常结论，它们照样填在 `issue` 里。
     * 第一版直接把 `issue != null` 当作失败，于是「这首歌旁边没有 .lrc」——最常见的那种正常情况——
     * 在屏幕上变成「歌词加载失败」（真机截图里一眼看到的）。
     *
     * 两者的**用户下一步动作**不同：真出错要去排查原因（权限、格式），
     * 「找过了没有」就只是一条信息，下一步是手动选一条字幕。
     *
     * 写成穷尽的 `when` 而不是 `!is NoSubtitles && !is NoMatch`：以后再加一个
     * issue 分支时编译器会在这里提醒你它是哪一类，不会默默归进某一类。
     */
    private fun SubtitleIssue.isFailure(): Boolean = when (this) {
        SubtitleIssue.NoDirectory, SubtitleIssue.DirectoryInvisible -> true
        is SubtitleIssue.ScanFailed, is SubtitleIssue.LoadFailed -> true
        SubtitleIssue.NoSubtitles, SubtitleIssue.NoMatch -> false
    }
}

/** 右栏的两种可能。 */
internal sealed interface LyricsSlot {
    /** 有歌词，正常画。 */
    data class Lines(val document: SubtitleDocument) : LyricsSlot

    /** 没歌词可画，显示 [kind] 对应的说明。 */
    data class Notice(val kind: LyricsPlaceholder) : LyricsSlot
}

/**
 * 「为什么这里没有歌词」的四种答案。
 *
 * 四种各占一条，不合并：它们的**用户下一步动作**不一样（等待 / 重试或看看提示 /
 * 直接打开 / 去选一条字幕），而一句话只对应一个动作。合并任意两种，都会有一半的
 * 用户读到一句与自己处境不符的话——这是那种没人会报 bug、只会让人绕远路的错。
 */
internal enum class LyricsPlaceholder(@StringRes val messageRes: Int) {
    /** 正在扫字幕 / 解析。 */
    LOADING(R.string.msp_player_lyrics_loading),

    /** 这一条媒体上有字幕，但用户把它关了。 */
    OFF(R.string.msp_player_lyrics_off),

    /** 找过、读过，但没读到东西（包括「目录里没有字幕文件」和「没一个对得上片名」）。 */
    EMPTY(R.string.msp_player_lyrics_empty),

    /** 加载出错——**没能去看**，和 [EMPTY] 的「看过了没有」是两件事。 */
    FAILED(R.string.msp_player_lyrics_failed),
}
