package com.multisuperplayer.feature.player

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 播放页手势的**数学部分**。
 *
 * 单独抽出来是因为手势的坑全在这些边界上，而它们全都可以用纯函数钉死：
 * 「正好点在正中间算哪边」、「往上拖是加还是减」、「拖到底会不会把屏幕拖黑」。
 * 混在 `pointerInput` 的闭包里的话，这些分支只能靠手动试，而「差一点点」的错误
 * 在手上是感觉不出来的（拖动 3% 和 5% 的差别没人分得出来）。
 */
object PlayerGestures {

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

    // ---- 拖动方向 ----

    /**
     * 一次拖动被**锁定**到哪个方向。
     *
     * 这不是「这一帧往哪边动了多少」，而是一个一次性决定、之后整条手势都遵守的锁。
     * 非做成锁不可的原因：竖直拖动（音量/亮度）和水平拖动（进度）都是拖动，
     * 两者都会在超过 slop 时 `consume()` 事件，谁先被判定都会把事件吃掉。两个
     * 检测器并排挂着的后果是**斜着划一下会同时改音量、跳进度**，而且改多少取决于
     * 两个检测器各自收到事件的顺序——这种 bug 在手上表现为「有时候音量自己变了」，
     * 极难复现。锁定之后，一次手势从头到尾只认一个方向。
     */
    enum class DragAxis { NONE, HORIZONTAL, VERTICAL }

    /**
     * 从按下点开始的位移 [dx]/[dy] 是否已经足够判定方向。
     *
     * 返回 [DragAxis.NONE] 表示「还看不出来」——手指没动，或者没动够 [slop]。
     * 调用方要一直问，直到拿到非 NONE 或者手指抬起/长按超时。
     *
     * [slop] 必须传 `viewConfiguration.touchSlop`：那是系统认为「这已经不是一次点击」
     * 的阈值，自己写一个 30px 之类的常数会在小屏上让点击被误判成拖动、在大屏上
     * 让拖动迟钝。
     *
     * 平局（`abs(dx) == abs(dy)`，也就是正好 45°）判**竖直**：水平拖动会在**松手
     * 那一刻**一次性跳转进度，竖直拖动是可以随手拖回来的连续量。两者一样近的时候，
     * 选那个「万一猜错了、用户损失更小」的。
     */
    fun axisFor(dx: Float, dy: Float, slop: Float): DragAxis {
        // NaN 会一路骗过所有比较（`NaN > x` 恒假），落到竖直分支上；
        // 真出现 NaN 说明上游拿到的位置是坏的，此时什么也不做最安全。
        if (!dx.isFinite() || !dy.isFinite()) return DragAxis.NONE
        val ax = abs(dx)
        val ay = abs(dy)
        if (ax < slop && ay < slop) return DragAxis.NONE
        return if (ax > ay) DragAxis.HORIZONTAL else DragAxis.VERTICAL
    }

    /**
     * 水平拖动之后的播放位置。
     *
     * 「拖满整个宽度 = 跳过整片时长」：和 [levelAfterDrag] 一样，手感要跟屏幕的
     * 比例走而不是跟像素走。1080p 的手机上拖 1px ≈ 长片（2 小时）的 6.7 秒，
     * 拖过四分之一屏就是半小时——这正是各家播放器的比例，也刚好够「精确到几秒」。
     *
     * 结果一律夹在 `0..durationMs`：越界不夹的话会把负数或超过结尾的位置喂给
     * `seekTo`，内核对超出结尾的位置会跳到结尾并从那儿继续（表现为「拖过头就播完了」）。
     *
     * 时长未知时返回**原位置**（不是 0）：时长未知只发生在起播阶段，此时用户
     * 拖了一下却被弹回开头，比「拖了没反应」更像故障。[totalDx] 为 NaN 同理。
     */
    fun seekTarget(
        startPositionMs: Long,
        totalDx: Float,
        width: Float,
        durationMs: Long,
    ): Long {
        if (durationMs <= 0L || !totalDx.isFinite()) return startPositionMs.coerceAtLeast(0L)
        val start = startPositionMs.coerceIn(0L, durationMs)
        if (width <= 0f || !width.isFinite()) return start
        // 用 Double 乘：一部 3 小时的片子是 1.08e7 毫秒，Float 只有 24 位有效位，
        // 乘法后半段的精度会掉到几毫秒，长时间轴的拖动手感会变得一顿一顿的。
        val offset = durationMs.toDouble() * (totalDx / width)
        return (start + offset.roundToLong()).coerceIn(0L, durationMs)
    }
}
