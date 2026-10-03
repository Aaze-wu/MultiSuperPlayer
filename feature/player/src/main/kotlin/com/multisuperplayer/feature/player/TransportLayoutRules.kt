package com.multisuperplayer.feature.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 播放控制行（随机 / 上一首 / 播放 / 下一首 / 循环 / 字幕 / 全屏）的版面规则。
 *
 * 抽成纯函数而不是写死在组合函数里，是因为这一行有一条**只有真机才会暴露**的硬约束：
 * 七个按钮的天然宽度超过了不少手机的可用宽度，而溢出的表现不是「挤一点」——
 * `Row` 会把多出来的部分全压在最后一个子项上，最后那个按钮被压成几 dp 的缝，
 * 里面的 24dp 图标跟着缩成一个点。屏幕上看不出哪里报错，也不是崩，就是
 * 「最后多了一个空按钮，里面有个逗号」。
 *
 * 现场数字（1080×2400 @480dpi 的真机，即 **360dp 宽**）：
 *
 * ```
 * 48 + 48 + 64 + 48 + 48 + 48 + 48 = 352dp   // 七个按钮的天然宽度
 * 360 - 24 × 2 = 312dp                       // 实际可用宽度
 * 溢出 40dp → 最后一个按钮只剩 8dp
 * ```
 *
 * @see layoutFor
 */
internal object TransportLayoutRules {

    /**
     * 一个侧按钮至少要占的宽度。
     *
     * **不是我们定的**：Material3 的 `IconButton` / `FilledTonalIconButton` 内部套了
     * `minimumInteractiveComponentSize()`，把**可点区域**抬到 48dp，父级 `Row` 量到的
     * 就是这个值。想在这一行多塞东西只能从别处省；压这个值等于「按钮看着在、
     * 手指点不中」，比溢出更难发现。
     */
    internal val BUTTON_MIN = 48.dp

    /** 两侧内边距的上限。宽屏保持它，窄屏从这里先让步。 */
    internal val PADDING_MAX = 24.dp

    /** 竖屏播放键直径。比别的按钮大一档：它是这一行唯一的主操作。 */
    internal val PLAY_MAX = 64.dp

    /** 横屏（`compact`）播放键直径。横屏高度只有 400 多 dp，纵向空间要先让给画面。 */
    internal val PLAY_MAX_COMPACT = 56.dp

    /** 播放控制行的排布结果。 */
    @Immutable
    internal data class Layout(
        /** 两侧内边距。 */
        val horizontalPadding: Dp,
        /** 播放键直径。 */
        val playButtonSize: Dp,
    )

    /**
     * 按可用宽度算这一行怎么排。
     *
     * 让步顺序是**固定的**，不能换：
     *
     * 1. 先让内边距——纯装饰，让到 0 也不影响任何操作；
     * 2. 再让播放键——它是这一行唯一「小一点还不损失功能」的按钮，缩到和别的按钮
     *    一样大仍然点得准、也仍然是最显眼的那个（它是唯一的实心圆）；
     * 3. 可点区域 48dp 是硬下限，**永远不让**。
     *
     * 第 3 条意味着侧按钮 6 个时这一行的真下限是 `48 × 6 + 48 = 336dp`：比这更窄的窗口
     * （分屏、或把「显示大小」拉到最大的小屏）无论如何都排不下，这里不假装能修——
     * 返回值会如实溢出，而不是靠 `coerceAtLeast(最小尺寸)` 把数字凑好看。
     * 那种「算出来放得下、实际还是溢出」的假象正是这个 bug 一开始能藏住的原因。
     *
     * @param availableWidth 这一行真正拿到的宽度（已经扣掉父级内边距）。
     *   传入无界约束（`Dp.Infinity`，例如放在横向滚动容器里）时按宽屏处理。
     * @param sideSlots 侧按钮个数：竖屏 **6**（随机/上一首/下一首/循环/字幕/全屏），
     *   横屏与音频横屏 **5**（那两处刻意不摆全屏按钮，见 `PlayerTransportControls`）。
     * @param compact 横屏。只影响播放键的上限。
     */
    fun layoutFor(
        availableWidth: Dp,
        sideSlots: Int,
        compact: Boolean,
    ): Layout {
        val preferred = if (compact) PLAY_MAX_COMPACT else PLAY_MAX
        val required = BUTTON_MIN * sideSlots + preferred

        // 宽屏：一个数字都不动，保持原来的样子。
        if (availableWidth >= required + PADDING_MAX * 2) {
            return Layout(horizontalPadding = PADDING_MAX, playButtonSize = preferred)
        }

        // 按钮放得下，只是内边距得收窄：只收内边距。
        // （内边距取整到像素时可能比这里少 1px，最坏情况让最后一个按钮少 1px——
        // 远大于 24dp 的图标，看不出来，所以不为此留更大的余量。）
        if (availableWidth >= required) {
            val padding = ((availableWidth - required) / 2).coerceAtMost(PADDING_MAX)
            return Layout(horizontalPadding = padding, playButtonSize = preferred)
        }

        // 连按钮都放不下：播放键缩到和别的按钮一样大，内边距归零。
        val play = (availableWidth - BUTTON_MIN * sideSlots)
            .coerceIn(BUTTON_MIN, preferred)
        return Layout(horizontalPadding = 0.dp, playButtonSize = play)
    }
}
