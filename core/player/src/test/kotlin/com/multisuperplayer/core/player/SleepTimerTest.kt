package com.multisuperplayer.core.player

import com.multisuperplayer.core.common.text.MspText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 睡眠定时的纯规则。
 *
 * 这一组测试盯的是**「定时到了却没停」和「定时不该到却停了」这两个相反的方向**。
 * 它们的共同点是：都不会崩、都不会报错，只会让用户第二天早上发现片子放了一整夜
 * （或者睡了十分钟就被静音吵醒）。所以每一条都写成「某个时刻，答案必须是什么」。
 *
 * 时钟是**参数**，测试里从不 sleep：一旦真的去等，「30 分钟档」这条测试就要跑
 * 30 分钟，没人会留着它，删掉之后这个方向就再也没人守着了。
 */
class SleepTimerTest {

    /** 假时钟的起点。用一个非零值：0 是「没设置」的形状，容易掩盖「忘了传时钟」。 */
    private val t0 = 1_000_000L

    // ------------------------------------------------------------ 开始倒计时

    @Test
    fun `开始倒计时用的是绝对截止时刻而不是剩余量`() {
        val state = SleepTimerRules.startCountdown(t0, 30 * SleepTimerOptions.MINUTE_MS)
        assertEquals(
            SleepTimerState.Countdown(
                deadlineMs = t0 + 30 * SleepTimerOptions.MINUTE_MS,
                totalMs = 30 * SleepTimerOptions.MINUTE_MS,
            ),
            state,
        )
    }

    @Test
    fun `非正时长一律当成没有定时`() {
        // 关键方向：调用方传 null 表达的是「关掉定时」。若这里把它夹到最短那一档，
        // 「关掉」这个动作会**反而开一个 5 分钟的定时**——一个非常难查的相反行为。
        listOf(null, 0L, -1L, Long.MIN_VALUE).forEach { duration ->
            assertEquals(
                SleepTimerState.Off,
                SleepTimerRules.startCountdown(t0, duration),
                "时长 $duration 应当表示「没有定时」",
            )
        }
    }

    @Test
    fun `每一档都能开出对应的倒计时`() {
        SleepTimerOptions.PRESETS_MS.forEach { preset ->
            val state = SleepTimerRules.startCountdown(t0, preset)
            assertTrue(state is SleepTimerState.Countdown, "$preset 应当开出倒计时")
            assertEquals(preset, (state as SleepTimerState.Countdown).totalMs)
        }
    }

    // ------------------------------------------------------------ 到期判定

    @Test
    fun `刚好到截止时刻就算到期`() {
        // `>=` 与 `>` 的差别：写成 `>` 时，恰好在截止那一瞬间的 tick 不算到期，
        // 要再等一个 tick（200ms）。真机上偶尔会撞到，表现为「定时到了但还在放」。
        val state = SleepTimerRules.startCountdown(t0, 5 * SleepTimerOptions.MINUTE_MS)
        val deadline = t0 + 5 * SleepTimerOptions.MINUTE_MS
        assertTrue(SleepTimerRules.isExpired(state, deadline), "正好等于截止时刻必须算到期")
        assertTrue(SleepTimerRules.isExpired(state, deadline + 1), "超过截止时刻必须算到期")
        assertTrue(!SleepTimerRules.isExpired(state, deadline - 1), "差一毫秒时还不能停")
    }

    @Test
    fun `到期判定只对倒计时成立`() {
        // 「本集结束」的到期条件是一个播放事件，不是时间。让它在这里返回 true
        // 会把「看到一半就停」变成一个定时器 bug。
        assertTrue(!SleepTimerRules.isExpired(SleepTimerState.Off, t0 + 1_000_000L))
        assertTrue(!SleepTimerRules.isExpired(SleepTimerState.UntilItemEnd, t0 + 1_000_000L))
    }

    // ------------------------------------------------------------ 剩余量与进度

    @Test
    fun `剩余量在过期后归零而不是变负`() {
        val state = SleepTimerRules.startCountdown(t0, 5 * SleepTimerOptions.MINUTE_MS)
        assertEquals(5 * SleepTimerOptions.MINUTE_MS, SleepTimerRules.remainingMs(state, t0))
        assertEquals(0L, SleepTimerRules.remainingMs(state, t0 + 999 * SleepTimerOptions.MINUTE_MS))
    }

    @Test
    fun `没有倒计时时剩余量是 null 而不是 0`() {
        // 返回 0 的话，面板上会显示「剩余 0:00」，看起来像定时已经失效了；
        // 「本集结束」这一档尤其容易被误读成「已经到点了」。
        assertNull(SleepTimerRules.remainingMs(SleepTimerState.Off, t0))
        assertNull(SleepTimerRules.remainingMs(SleepTimerState.UntilItemEnd, t0))
    }

    @Test
    fun `进度在起点为零、终点为满`() {
        val total = 10 * SleepTimerOptions.MINUTE_MS
        val state = SleepTimerRules.startCountdown(t0, total)
        assertEquals(0f, SleepTimerRules.progress(state, t0), 0.001f)
        assertEquals(0.5f, SleepTimerRules.progress(state, t0 + total / 2), 0.001f)
        assertEquals(1f, SleepTimerRules.progress(state, t0 + total), 0.001f)
    }

    @Test
    fun `总时长为零时进度是满格而不是 NaN`() {
        // 正常路径上构造不出 Countdown(totalMs = 0)，但 `progress` 会被界面直接拿去做
        // 除法：NaN 在 Compose 里表现成「什么都不画」，比画成满格更难归因。
        val broken = SleepTimerState.Countdown(deadlineMs = t0, totalMs = 0L)
        assertEquals(1f, SleepTimerRules.progress(broken, t0), 0.001f)
    }

    @Test
    fun `没有倒计时时进度是零`() {
        assertEquals(0f, SleepTimerRules.progress(SleepTimerState.Off, t0), 0.001f)
        assertEquals(0f, SleepTimerRules.progress(SleepTimerState.UntilItemEnd, t0), 0.001f)
    }

    // ------------------------------------------------------------ 文本

    @Test
    fun `剩余时间按一小时分两种写法`() {
        assertEquals("0:00", SleepTimerRules.formatRemaining(0L))
        assertEquals("0:05", SleepTimerRules.formatRemaining(5_000L))
        assertEquals("3:05", SleepTimerRules.formatRemaining(185_000L))
        assertEquals("59:59", SleepTimerRules.formatRemaining(3_599_000L))
        // 跨过一小时之后秒数仍然是两位，分钟从 0 重新数：`59:59` → `1:00:00`
        assertEquals("1:00:00", SleepTimerRules.formatRemaining(3_600_000L))
        assertEquals("1:05:12", SleepTimerRules.formatRemaining(3_912_000L))
    }

    @Test
    fun `负数和非法剩余时间都显示为零`() {
        // 显示 `-1:-3` 会让用户以为播放器坏了，而不是以为定时到期了。
        assertEquals("0:00", SleepTimerRules.formatRemaining(-1L))
        assertEquals("0:00", SleepTimerRules.formatRemaining(Long.MIN_VALUE))
    }

    @Test
    fun `档位文案在三个区间里是三种不同的说法`() {
        val five = SleepTimerOptions.label(5)
        val oneHour = SleepTimerOptions.label(60)
        val oneAndHalf = SleepTimerOptions.label(90)
        assertEquals(MspText.Res(R.string.msp_sleep_timer_minutes, 5), five)
        assertEquals(MspText.Res(R.string.msp_sleep_timer_hours, 1), oneHour)
        assertEquals(MspText.Res(R.string.msp_sleep_timer_hours_minutes, 1, 30), oneAndHalf)
        // 三条文案必须来自**不同**的资源：挤成一条（比如都写「%1$d 分钟」）
        // 会让 90 显示成「90 分钟」——不是错，但和 60 显示成「1 小时」不一致。
        val ids = setOf(
            (five as MspText.Res).id,
            (oneHour as MspText.Res).id,
            (oneAndHalf as MspText.Res).id,
        )
        assertEquals(3, ids.size, "三个区间的文案必须是三条不同的资源")
    }

    @Test
    fun `整小时不带分钟部分`() {
        // 120 分钟不该显示成「2 小时 0 分」。
        assertEquals(MspText.Res(R.string.msp_sleep_timer_hours, 2), SleepTimerOptions.label(120))
    }

    @Test
    fun `本集结束有自己的一条文案`() {
        val untilEnd = SleepTimerOptions.untilItemEndLabel() as MspText.Res
        assertNotEquals(
            untilEnd.id,
            (SleepTimerOptions.label(30) as MspText.Res).id,
            "「本集结束」和「30 分钟」不能共用一条文案",
        )
    }

    // ------------------------------------------------------------ 档位表

    @Test
    fun `档位表升序且毫秒版本与分钟版本一一对应`() {
        assertEquals(
            SleepTimerOptions.PRESETS_MINUTES.sorted(),
            SleepTimerOptions.PRESETS_MINUTES,
            "档位必须升序，面板的顺序就是按它画的",
        )
        assertEquals(
            SleepTimerOptions.PRESETS_MINUTES.map { it * SleepTimerOptions.MINUTE_MS },
            SleepTimerOptions.PRESETS_MS,
            "PRESETS_MS 与 PRESETS_MINUTES 必须一致（分写成两份就是为了不会各乘各的）",
        )
    }

    @Test
    fun `面板高亮的是相等的那一档而不是最近的一档`() {
        // 12 分钟不是档位，但用户有办法设出来（将来加自定义时长）。
        // 用「最近档位」高亮会显示成选中了 10 分钟——用户会以为自己的设置被改了。
        val twelve = SleepTimerRules.startCountdown(t0, 12 * SleepTimerOptions.MINUTE_MS)
        assertNull(SleepTimerOptions.presetFor(twelve))

        val thirty = SleepTimerRules.startCountdown(t0, 30 * SleepTimerOptions.MINUTE_MS)
        assertEquals(30, SleepTimerOptions.presetFor(thirty))
    }

    @Test
    fun `没有倒计时时没有任何档位被选中`() {
        assertNull(SleepTimerOptions.presetFor(SleepTimerState.Off))
        assertNull(SleepTimerOptions.presetFor(SleepTimerState.UntilItemEnd))
    }
}
