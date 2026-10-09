package com.multisuperplayer.core.data.update

import com.multisuperplayer.core.common.format.DurationDraft
import com.multisuperplayer.core.common.format.DurationInput
import com.multisuperplayer.core.common.format.DurationInputParser

/**
 * 自动检查更新的**冷却时长**：距上次检查不满这么久就跳过这一次自动检查。
 *
 * ## 为什么它是一个可以由用户改的数
 *
 * 在这之前它写死 12 小时（`UpdateRules.AUTO_CHECK_INTERVAL_MS`）。写死的代价不是
 * 「不够灵活」，而是**它把两拨互相冲突的人都当成了一拨**：
 *
 * - 一天开好几次的人每次启动都被查一遍（旧行为里 12 小时一过就查），他想要的是
 *   「别老问」——手上有网、也知道去哪儿看新版本；
 * - 一周开一次的人每次启动都会检查，12 小时对他没有任何影响，但如果他能把窗口
 *   调短，就能在「我今天刚打开过、想看看到底有没有新版」时不被上一次的检查挡住。
 *
 * 所以真正要给的不是「更大的间隔」，而是**让用户自己说这个应用该多积极地找更新**。
 *
 * ## 和「自动检查更新」那个开关的分工
 *
 * 开关是**总闸**（关掉 = 永远不自动查），窗口只管**节流**（自动查，但两次之间
 * 至少隔这么久）。两件事合成一个控件（比如「关闭 / 1 小时 / 1 天」那种一维选择）
 * 之后，「关掉」和「设成很长」会变成同一个状态，而它们下一次要做的动作完全相反
 * ——前者是「我想让它查」，后者是「我不想被它打扰」。手动点「检查更新」两样都不看
 * （见 [UpdateRules.skipsAutoCheck]）。
 *
 * ## 上下界是怎么定的
 *
 * - **最短 15 分钟**。这是唯一的硬约束，因为它是唯一会导致**外部代价**的一头：
 *   GitHub 对未认证请求的限制是每小时 60 次，窗口比 15 分钟更短就只是把
 *   「用户自己按刷新」变成「应用替他按刷新」；15 分钟对应最坏每小时 4 次，
 *   离限流还有一个数量级的余量。
 * - **最长 7 天**。它接的是**打错的手**（输入框允许手填），不是真实需求：
 *   档位表到 7 天，再长就该直接把自动检查关掉——那是一条比「设成 30 天」
 *   意思更清楚的路。和睡眠定时一样，上限只在**输入**时才拦，
 *   读到的越界值会被 [normalize] 夹回来（旧版本写进去的、或者被改坏的文件）。
 *
 * ## 为什么常量是毫秒
 *
 * 因为内核那一侧（[UpdateRules.skipsAutoCheck]）比的是毫秒，而设置里存的也是
 * 毫秒。只在**写文案**那一层换算成「小时 / 天」（见 `UpdateSummaries.interval`），
 * 中间任何一层自己乘一次都有写错的可能（`* 60` 而不是 `* 60_000` 会得到
 * 一个 12 秒的窗口——一个比限流更难查的 bug）。
 */
object UpdateCooldown {

    /** 一分钟多少毫秒。 */
    const val MINUTE_MS: Long = 60_000L

    /** 一小时多少分钟。 */
    const val MINUTES_PER_HOUR: Int = 60

    /** 一天多少小时。 */
    const val HOURS_PER_DAY: Int = 24

    /** 一小时多少毫秒。 */
    const val HOUR_MS: Long = MINUTES_PER_HOUR * MINUTE_MS

    /** 一天多少毫秒（是 24 小时，不是「日历上的一天」——这里不需要时区）。 */
    const val DAY_MS: Long = HOURS_PER_DAY * HOUR_MS

    /**
     * 档位（毫秒）。这是**界面**提供的快捷选项，不是合法值的全集
     * ——面板上还有一格「自定义…」，用户能填任意 15 分钟 ~ 7 天的值。
     *
     * 这几档是按「一天里查几次」摆的：1 小时（每次启动都查）/ 6 小时（一天几次）/
     * 12 小时（一天两次，旧行为）/ 24 小时（一天一次）/ 3 天 / 7 天（一周一次）。
     * 每一档都单独起个名字，是因为文案那一边要 `when` 出各自的一句话
     * （见 `UpdateSummaries.interval`），而「`PRESETS_MS[3]` 对应第 4 句文案」
     * 这种靠下标对齐的写法在改表的时候不会报错。
     */
    const val PRESET_1H_MS: Long = HOUR_MS
    const val PRESET_6H_MS: Long = 6 * HOUR_MS
    const val PRESET_12H_MS: Long = 12 * HOUR_MS
    const val PRESET_24H_MS: Long = DAY_MS
    const val PRESET_3D_MS: Long = 3 * DAY_MS
    const val PRESET_7D_MS: Long = 7 * DAY_MS

    /** 界面上的档位表（升序）。 */
    val PRESETS_MS: List<Long> = listOf(
        PRESET_1H_MS,
        PRESET_6H_MS,
        PRESET_12H_MS,
        PRESET_24H_MS,
        PRESET_3D_MS,
        PRESET_7D_MS,
    )

    /** 能设的最短窗口（15 分钟），见类注释。 */
    const val MIN_MINUTES: Int = 15

    /** 能设的最长窗口（7 天 = 10080 分钟），见类注释。 */
    const val MAX_MINUTES: Int = 7 * HOURS_PER_DAY * MINUTES_PER_HOUR

    const val MIN_MS: Long = MIN_MINUTES * MINUTE_MS
    const val MAX_MS: Long = MAX_MINUTES * MINUTE_MS

    /**
     * 用户没改过时用的窗口：**12 小时**，也就是 v1.2.3 之前写死的那个值。
     *
     * 默认值必须等于旧行为：升级上来的用户没有做过任何选择，让他「什么都没点，
     * 但查更新的频率变了」是一种静默改设置。他改过之后才按他改的走。
     */
    const val DEFAULT_MS: Long = PRESET_12H_MS

    /**
     * 把任意读到的值收敛成「一个能用的窗口」。
     *
     * 读路径和写路径共用它：写的时候夹一次保证存储里永远是合法值，读的时候再夹
     * 一次是为了接住**不是这个版本写进去的值**（旧版本、被外部改过的偏好文件、
     * 或者将来某一版把上下界改了）。两次都要，因为它们是两个不同的入口。
     *
     * null / 0 / 负数（= 没有值、或者被改坏）一律退回 [DEFAULT_MS]，**不夹到
     * 最短那一档**：夹成 15 分钟等于把一个「没设置」读成「用户要求最短窗口」，
     * 那会让应用开始替用户频繁请求，而这件事在界面上完全看不出来。
     */
    fun normalize(intervalMs: Long?): Long = when {
        intervalMs == null || intervalMs <= 0L -> DEFAULT_MS
        else -> intervalMs.coerceIn(MIN_MS, MAX_MS)
    }

    /**
     * 这个窗口是不是档位表里的某一档；认不出就是「自定义」。
     *
     * 判据是**值**而不是「当初从哪一格设进来的」：用户手填 12 小时，那它就**是**
     * 12 小时那一档，选择框该把那一档标成选中。记「来源」会得到「停在自定义那一格、
     * 值却恰好等于 12 小时」这种双份真相（睡眠定时那边同一个坑）。
     */
    fun presetFor(intervalMs: Long): Long? = PRESETS_MS.firstOrNull { it == intervalMs }

    /** 这个窗口认不出档位（手填出来的），见 [presetFor]。 */
    fun isCustom(intervalMs: Long): Boolean = presetFor(intervalMs) == null

    /**
     * 毫秒窗口是多少分钟（不足一分钟向上取整）。
     *
     * 非正值返回 0 而不是负数：正常路径上构造不出来（见 [normalize]），
     * 但除法和取整比别处更早碰到边界，写成 0 能让调用方少一个分支。
     * 先除再取整而不是先加再除：加法在 `Long.MAX_VALUE` 附近会溢出成负数。
     */
    fun minutesOf(intervalMs: Long): Int {
        if (intervalMs <= 0L) return 0
        val whole = intervalMs / MINUTE_MS
        val rounded = if (intervalMs % MINUTE_MS == 0L) whole else whole + 1
        return rounded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 自定义输入框的初值：当前窗口拆成「几小时 + 几分钟」。
     *
     * 预填是为了「在刚才那个数上改一点」这条最常见的路径（用户设了 3 天、想改成
     * 4 天，不该把两格重新打一遍）。[DurationDraft] 会把为 0 的那一格留空。
     */
    fun draftOf(intervalMs: Long): DurationDraft {
        val minutes = minutesOf(intervalMs)
        return DurationDraft(hours = minutes / MINUTES_PER_HOUR, minutes = minutes % MINUTES_PER_HOUR)
    }

    /**
     * 解析自定义输入（小时、分钟两格的原文）。
     *
     * 解析本身在 `core:common` 的 [DurationInputParser] 里，和睡眠定时共用同一条
     * 实现（两个对话框让用户填的是同一件事）。这里只负责把本档的上下界填进去。
     */
    fun parseInput(hoursText: String, minutesText: String): DurationInput =
        DurationInputParser.parse(
            hoursText = hoursText,
            minutesText = minutesText,
            minMinutes = MIN_MINUTES,
            maxMinutes = MAX_MINUTES,
        )
}
