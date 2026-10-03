package com.multisuperplayer.core.player

import com.multisuperplayer.core.common.text.MspText

/**
 * 睡眠定时当前处在什么状态。
 *
 * ## 为什么是一个密封类型而不是「剩余毫秒 + 一个布尔」
 *
 * 三种状态说的不是「还剩多久」，而是**三件不同的事**：
 * - [Off]：没有定时。界面不该画任何倒计时。
 * - [Countdown]：有一个绝对截止时刻。界面能算出还剩多久。
 * - [UntilItemEnd]：没有时刻，等待的是「当前这一集放完」这个事件。
 *
 * 后两者用「剩余毫秒」表达不了：[UntilItemEnd] 的剩余时间**取决于片长**，
 * 而且在片长还没解析出来的时候是未知的。硬凑成一个数字，界面就只能写
 * 「剩余 0:00」，看起来像定时已经失效了。
 *
 * ## 为什么存的是 deadline 而不是 remaining
 *
 * [Countdown.deadlineMs] 是 `SystemClock.elapsedRealtime()` 坐标系里的一个**绝对时刻**。
 * 存「还剩多少」的话，每一个 tick 都要减一次，而 tick 会被跳过（暂停、卡顿、
 * 主线程忙）、减出来的值就会越走越慢，一小时下来能差出好几分钟——
 * 用户设了 30 分钟却放了一小时。绝对时刻没有累积误差。
 *
 * 用 `elapsedRealtime()` 而不是 `currentTimeMillis()`：后者会被用户改系统时间、
 * 或者被 NTP 校时影响，往前跳一下定时就当场到期了。前者是开机以来的单调时钟。
 * 代价是**它不能跨进程重启**——所以这个状态本来就不该持久化（见 [SleepTimerOptions]）。
 */
sealed interface SleepTimerState {

    /** 没有定时。 */
    data object Off : SleepTimerState

    /**
     * 倒计时到一个绝对时刻。
     *
     * @param deadlineMs 到期时刻（`elapsedRealtime()` 坐标系）。
     * @param totalMs 设下去的总时长。用来算进度、也用来在面板上高亮**哪一档**。
     */
    data class Countdown(val deadlineMs: Long, val totalMs: Long) : SleepTimerState

    /** 当前这一集放完就停。 */
    data object UntilItemEnd : SleepTimerState
}

/**
 * 睡眠定时的档位表。
 *
 * ## 档位怎么选的
 *
 * 「我要睡着了」这个决定本身就很粗：没有人需要 7 分钟或者 23 分钟。真正有区别的是
 * 「小睡一会儿」（5/10/15）、「一集的长度」（30/45）、「一部电影」（60/90）。
 * 档位再多只会让面板变长，而选错的代价又能靠最后那一档 [UntilItemEnd] 兜住
 * ——**「本集结束」是唯一永远正确的那一档**，它不需要用户猜自己几分钟能睡着。
 *
 * ## 为什么不持久化「上次用的时长」
 *
 * 睡眠定时是**一次性**的事：周一晚上设了 30 分钟，周二晚上打开播放器时
 * 定时不该还挂着，也不该默认就选中 30 分钟。而且它存不下来
 * ——[SleepTimerState.Countdown] 用的是开机以来的单调时钟，重启之后那个时刻没有意义
 * （见该类的注释）。所以这里既不提供默认档位，也不接 DataStore。
 */
object SleepTimerOptions {

    /** 一分钟多少毫秒。 */
    const val MINUTE_MS = 60_000L

    /**
     * 可选档位（分钟，升序）。
     *
     * 60 和 90 用小时表达（见 [label]）：面板上写「1 小时」比「60 分钟」好读，
     * 而「90 分钟」要写成「1 小时 30 分」——这两种写法都不需要用户自己换算。
     */
    val PRESETS_MINUTES: List<Int> = listOf(5, 10, 15, 30, 45, 60, 90)

    /**
     * 把 [PRESETS_MINUTES] 换算成毫秒。
     *
     * 单独再列一份而不是让调用方每次现算：倒计时那条路径在**每个 tick** 上都会
     * 碰到总时长，而调用方自己乘一次就有写错的可能（`* 60` 而不是 `* 60_000`
     * 会得到「5 秒」——一个短到用户以为是 bug 的定时）。
     */
    val PRESETS_MS: List<Long> = PRESETS_MINUTES.map { it * MINUTE_MS }

    /**
     * 一档的显示文案。
     *
     * 返回 [MspText] 而不是 `String`：这个函数是纯的、要进 JVM 单测，
     * 而单测里没有 `Resources`（见 `MspText` 的类注释）。
     */
    fun label(minutes: Int): MspText = when {
        minutes < 60 -> MspText.Res(R.string.msp_sleep_timer_minutes, minutes)
        minutes % 60 == 0 -> MspText.Res(R.string.msp_sleep_timer_hours, minutes / 60)
        else -> MspText.Res(R.string.msp_sleep_timer_hours_minutes, minutes / 60, minutes % 60)
    }

    /** 「本集结束」那一档的文案。 */
    fun untilItemEndLabel(): MspText = MspText.Res(R.string.msp_sleep_timer_until_item_end)

    /**
     * 把任意输入收敛成「一个能用的时长」。
     *
     * 不认的值（null、0、负数、NaN）一律返回 `null`，也就是「不设定时」，
     * 而不是夹到最短那一档：调用方传 null 表达的就是「关掉」，
     * 把它夹成 5 分钟会让「关掉定时」这个动作**反而开了一个定时**。
     *
     * 超出档位表的长值**照样接受**（只要求是有限的整数毫秒）：档位表是界面提供的
     * 快捷选项，不是内核的合法范围。将来如果有一个「自定义时长」的入口，
     * 它不该被这里拦掉。
     */
    fun normalizeDuration(durationMs: Long?): Long? =
        durationMs?.takeIf { it > 0L }

    /**
     * 面板上该高亮哪一档分钟数。
     *
     * 只有恰好等于某个档位、**并且**这个档位确实是当前倒计时的总时长时才选中：
     * 用「最近的档位」会让 12 分钟的定时显示成「高亮 10 分钟」，
     * 用户就会以为自己的设置被改了。认不出就返回 null（面板上没有任何一行是选中的），
     * 这比选中一个错的好。
     */
    fun presetFor(state: SleepTimerState): Int? {
        if (state !is SleepTimerState.Countdown) return null
        return PRESETS_MINUTES.firstOrNull { it * MINUTE_MS == state.totalMs }
    }
}

/**
 * 睡眠定时的纯规则。
 *
 * 全部显式收 `nowMs`（`elapsedRealtime()` 坐标系），**不在里面读时钟**：
 * 读时钟的纯函数在单测里只能靠 sleep 去等，而等待既慢又不可靠。
 * 把「现在几点」当参数传进来，「什么时候到期」就是一个可以逐条钉死的算术题。
 */
object SleepTimerRules {

    /**
     * 开一个倒计时。
     *
     * 非正值一律当成「没有定时」返回 [SleepTimerState.Off]，理由见
     * [SleepTimerOptions.normalizeDuration]。
     */
    fun startCountdown(nowMs: Long, durationMs: Long?): SleepTimerState {
        val duration = SleepTimerOptions.normalizeDuration(durationMs)
            ?: return SleepTimerState.Off
        return SleepTimerState.Countdown(
            deadlineMs = nowMs + duration,
            totalMs = duration,
        )
    }

    /**
     * 还剩多少毫秒。**不适用时返回 null**。
     *
     * [SleepTimerState.UntilItemEnd] 返回 null 而不是片长剩下的时间：那需要调用方
     * 自己去问内核「还剩多久」，而这条路径只在 UI 上显示一行字，
     * 让它去订阅位置流会把播放页拖进每秒重组。
     */
    fun remainingMs(state: SleepTimerState, nowMs: Long): Long? =
        (state as? SleepTimerState.Countdown)
            ?.let { (it.deadlineMs - nowMs).coerceAtLeast(0L) }

    /**
     * 到点了没有。
     *
     * **用 `>=` 而不是 `>`**：`==` 这一瞬间必须算到期，否则在「刚好一秒不差」时
     * 会多等一个 tick——而 tick 是 200ms，这种边界在真机上偶尔会撞到，
     * 表现为「定时到了但还在放」。
     *
     * [SleepTimerState.UntilItemEnd] 永远是 false：它的到期条件是一个播放事件
     * （当前条目结束），不是时间。放在这里返回 false 而不是让调用方自己 `is` 判断，
     * 是为了让内核那条 tick 逻辑只问一个问题。
     */
    fun isExpired(state: SleepTimerState, nowMs: Long): Boolean =
        state is SleepTimerState.Countdown &&
            state.deadlineMs <= nowMs

    /**
     * 倒计时进度（0~1），用于面板上那条细线。
     *
     * 总时长不可信时（≤ 0）返回 1f 而不是抛异常或除零：那是一个「已经走完」的形状，
     * 画成满格比画成 NaN（在 Compose 里表现为什么都不画）更容易被当成一个显示问题
     * 而不是崩溃。
     */
    fun progress(state: SleepTimerState, nowMs: Long): Float {
        val countdown = state as? SleepTimerState.Countdown ?: return 0f
        if (countdown.totalMs <= 0L) return 1f
        val remaining = (countdown.deadlineMs - nowMs).coerceIn(0L, countdown.totalMs)
        return 1f - remaining.toFloat() / countdown.totalMs.toFloat()
    }

    /**
     * 把剩余时间格式化成 `M:SS`（不足一小时）或 `H:MM:SS`。
     *
     * 和项目里其他时长格式化分开写、不复用：那些都是「媒体时长」，
     * 允许超过 100 小时（很长的音频合集），第一位会变成三位数；这里是倒计时，
     * 上限就是一小时半，用不上那个分支，硬凑过去会让 `0:05` 显示成 `00:05`
     * ——两个数字位数不同的地方长得不一样，反而是个噪音。
     *
     * 负数和 NaN 一律当 0：它在正常路径上不会出现，但显示 `-1:-3` 比显示 `0:00`
     * 更糟——用户会以为播放器坏了，而不是以为定时到了。
     */
    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = if (remainingMs > 0L) remainingMs / 1000L else 0L
        val seconds = totalSeconds % 60L
        val minutes = (totalSeconds / 60L) % 60L
        val hours = totalSeconds / 3600L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
