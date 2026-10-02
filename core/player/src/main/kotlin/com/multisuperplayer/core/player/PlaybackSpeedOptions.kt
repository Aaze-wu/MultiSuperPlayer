package com.multisuperplayer.core.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 倍速的可选档位。
 *
 * 放在 `:core:player` 而不是某个界面模块：**设置页**（选默认倍速）和**播放页**
 * （临时改倍速）要的是同一张表，而界面模块之间不应该互相依赖。同目录下就是
 * 内核的 [MIN_PLAYBACK_SPEED]~[MAX_PLAYBACK_SPEED]，档位表紧挨着它们，改一边时
 * 另一边就在眼前。
 *
 * 做成**固定档位**而不是一条连续的滑块：
 *
 * - 连续滑块能取到 1.13× 这种值，但它对用户毫无意义，而对内核是个新的
 *   `PlaybackParameters`——音高补偿（Sonic）在每个速度上的参数都不一样，
 *   档位少而常见，用户听到的声音质量就有保证；
 * - 界面上的显示要做成「1.25×」这样的文字，连续值会变成「1.2499999×」。
 *
 * 档位必须落在 [MIN_PLAYBACK_SPEED]~[MAX_PLAYBACK_SPEED] 之内：内核会把越界的值
 * 夹回去，于是界面上会出现一个「点了没反应」的按钮。这件事由 `PlaybackSpeedOptionsTest`
 * 钉住。
 */
object PlaybackSpeedOptions {

    /** 正常速度。 */
    val DEFAULT = 1f

    /**
     * 档位。1× 放正中间的位置（第 3 位），因为它是被用得最多的那个：
     * 面板是左右滑动的 chip 行，常用值靠中间意味着大多数时候不用滑。
     */
    val PRESETS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

    /**
     * [speed] 最接近的档位下标。
     *
     * 用在「设置里存了一个不在档位表里的值」这种情况（改了档位表、或者被别的
     * 入口写进来的）：界面必须高亮某一个 chip，而不是一个都不高亮。
     * 距离相同时取**靠前**的那个（`<` 而不是 `<=`），这样结果与遍历顺序无关。
     *
     * 非有限值（NaN、±Infinity）一律退回 [DEFAULT]：它们都是「这个值没有意义」，
     * 而 [format] 在同样的情况下也退回默认速度的文字——两边必须一致，否则
     * 设置页会出现「文字写着 1×、高亮的却是 0.5×」。
     */
    fun nearestPresetIndex(speed: Float): Int {
        if (!speed.isFinite()) return PRESETS.indexOf(DEFAULT)
        // 先把输入夹进档位表覆盖的区间，再谈「谁更近」。
        //
        // 不夹的话，`Float.MAX_VALUE` 这类输入是**无法比较**的：`3.4e38 - 0.5`
        // 和 `3.4e38 - 4` 舍入到同一个数（差距远小于该量级的表示精度），
        // 于是每个候选距离都相等、循环一次都不更新，结果变成最慢的 0.5×——
        // 方向整个反过来，而且不报错。夹一次之后它就只是一次普通的大小比较。
        // 用 `Double` 算距离是顺手把「两个 `Float` 相减」本身的舍入也一起排除掉。
        val target = speed.coerceIn(PRESETS.first(), PRESETS.last()).toDouble()
        var bestIndex = 0
        var bestDistance = abs(PRESETS[0].toDouble() - target)
        for (index in 1 until PRESETS.size) {
            val distance = abs(PRESETS[index].toDouble() - target)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    /** [speed] 最接近的档位值。 */
    fun nearestPreset(speed: Float): Float = PRESETS[nearestPresetIndex(speed)]

    /** [speed] 是否正好是一个档位。 */
    fun isPreset(speed: Float): Boolean = PRESETS.any { it == speed }

    /**
     * 显示用的文字，例如 `1.25×`。
     *
     * 两位小数四舍五入之后再决定要不要写小数部分：直接 `Float.toString()` 会得到
     * 「1.25」但也会得到「0.75000006」这种（只要值来自一次浮点运算）。
     * 整数倍速写成「2×」而不是「2.0×」——多一个没信息的字符，而它出现在一个
     * 只有两三个字的按钮上。
     */
    fun format(speed: Float): String {
        if (!speed.isFinite()) return format(DEFAULT)
        val rounded = (speed * 100f).roundToInt() / 100f
        val text = if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
        return "$text×"
    }
}
