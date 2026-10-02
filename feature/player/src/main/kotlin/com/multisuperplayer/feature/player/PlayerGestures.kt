package com.multisuperplayer.feature.player

import kotlin.math.roundToInt

/**
 * 播放页手势的**数学部分**。
 *
 * 单独抽出来是因为手势的坑全在这些边界上，而它们全都可以用纯函数钉死：
 * 「正好点在正中间算哪边」、「往上拖是加还是减」、「拖到底会不会把屏幕拖黑」。
 * 混在 `pointerInput` 的闭包里的话，这些分支只能靠手动试，而「差一点点」的错误
 * 在手上是感觉不出来的（拖动 3% 和 5% 的差别没人分得出来）。
 */
object PlayerGestures {

    /**
     * 双击快进/快退的时长。
     *
     * 10 秒是各家的共识：5 秒太小（要点很多次），30 秒太大（一次就越过了要找的位置）。
     */
    const val DOUBLE_TAP_SEEK_MS = 10_000L

    /** 屏幕的哪一半。 */
    enum class Side { LEFT, RIGHT }

    /**
     * 点在左半边还是右半边。
     *
     * 边界取「右半」：`x` **正好等于**中线时归右边。
     *
     * 为什么不取左：中线正好是一个像素列，判定写成 `<=` 还是 `<` 只影响这一个
     * 像素列上的触摸，但两者必须选一个并写下来——不写下来的话，读代码的人会
     * 默认它是 `<=`，然后某天把它「顺手改成」左半，双指同时点在两侧的测试就变了。
     * 更实际的原因：`width / 2` 是整数除法，用 `x < width/2` 时右半比左半**多一个
     * 像素**，与「右半包含中线」是等价的。
     */
    fun sideOf(x: Float, width: Float): Side =
        if (width <= 0f || x < width / 2f) Side.LEFT else Side.RIGHT

    /**
     * 竖直拖动能调整的量（亮度/音量）的取值范围。
     *
     * 亮度**不**从 0 开始：`Window.attributes.screenBrightness = 0f` 就是
     * `BRIGHTNESS_OVERRIDE_OFF`，屏幕会真的黑掉，用户会以为拖坏了。留 1% 保证
     * 「拖到底」仍然是「很暗但看得见」，也保证他能再拖回来。
     */
    data class LevelRange(val min: Float, val max: Float) {
        fun clamp(value: Float): Float =
            if (value.isNaN()) min else value.coerceIn(min, max)

        companion object {
            val Brightness = LevelRange(min = 0.01f, max = 1f)
            val Volume = LevelRange(min = 0f, max = 1f)
        }
    }

    /**
     * 从 [start] 起在竖直方向拖了 [totalDy]（向下为正）之后的取值。
     *
     * 「拖满整个高度 = 变化一整段」：手感的依据是「我拖过屏幕的百分之几」，
     * 而不是「我拖了多少像素」——后者在高分屏上会变得极其迟钝（拖 200px 才动 5%）。
     *
     * [totalDy] 用**累计**位移而不是每次事件的增量：增量会累积浮点误差（一次拖动
     * 上百个事件），而且用户中途反向拖回去时，累计值能自然回到原处，增量式的
     * 「先加后减」则会因为两边各自被夹紧而回不去。
     */
    fun levelAfterDrag(
        start: Float,
        totalDy: Float,
        height: Float,
        range: LevelRange,
    ): Float {
        if (height <= 0f || !height.isFinite()) return range.clamp(start)
        // `-totalDy`：屏幕坐标 y 向下增长，而「往上拖 = 变大」才是直觉。
        return range.clamp(start - totalDy / height)
    }

    /** 显示用的百分比（0–100 的整数）。 */
    fun levelPercent(level: Float): Int =
        if (level.isNaN()) 0 else (level.coerceIn(0f, 1f) * 100f).roundToInt()
}
