package com.multisuperplayer.core.player

import com.multisuperplayer.core.model.text.MspText
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

    // ------------------------------------------------------------ 自定义时长

    @Test
    fun `自定义时长的上下界都是闭区间`() {
        // 界内必须放行、界外必须拦住，这两条各自独立：`<= max` 写成 `< max` 的差别
        // 只有边界值那一个能发现，而它恰好是「24 小时整」这种会被真去填的值。
        assertEquals(
            SleepTimerCustomInput.Valid(SleepTimerOptions.CUSTOM_MAX_MINUTES),
            SleepTimerOptions.parseCustomInput("24", "0"),
            "正好到上限必须能开出来",
        )
        assertEquals(
            SleepTimerCustomInput.Valid(SleepTimerOptions.CUSTOM_MIN_MINUTES),
            SleepTimerOptions.parseCustomInput("", "1"),
            "正好到下限必须能开出来",
        )
        assertEquals(
            SleepTimerCustomInput.TooLong,
            SleepTimerOptions.parseCustomInput("24", "1"),
            "超出一分钟也算超",
        )
        assertEquals(
            SleepTimerCustomInput.TooShort,
            SleepTimerOptions.parseCustomInput("0", "0"),
            "0 分钟是非法，不是「不设定时」",
        )
    }

    @Test
    fun `自定义时长小时那一格可以空着`() {
        // 只填分钟是最常见的用法：手机上「30」两下就打完了，没人愿意多打一个
        //「0 小时」。把空当成 0 和不把空当成 0，差别就只在这一格上。
        assertEquals(SleepTimerCustomInput.Valid(30), SleepTimerOptions.parseCustomInput("", "30"))
        assertEquals(SleepTimerCustomInput.Valid(120), SleepTimerOptions.parseCustomInput("2", ""))
        assertEquals(SleepTimerCustomInput.Valid(90), SleepTimerOptions.parseCustomInput("1", "30"))
    }

    @Test
    fun `自定义输入先把全角数字折成半角`() {
        // 中文输入法在全角状态下打出来的「１」和半角「1」长得几乎一样。
        // 不折的话，用户会对着一个明明填了「１０」的框读「只填数字」，
        // 然后反复检查自己填的到底是不是数字。
        assertEquals(
            SleepTimerCustomInput.Valid(70),
            SleepTimerOptions.parseCustomInput("１", "１０"),
        )
    }

    @Test
    fun `两格都空着不算出错而只是还没填`() {
        // 对话框刚打开时就是这个状态。把它归进「只填数字」那一类，用户一打开
        // 就被一句红字骂，而他根本还没动手。
        assertEquals(SleepTimerCustomInput.Blank, SleepTimerOptions.parseCustomInput("", ""))
        assertEquals(SleepTimerCustomInput.Blank, SleepTimerOptions.parseCustomInput("  ", " "))
    }

    @Test
    fun `填了非数字和填了负数各有各的说法`() {
        // 这两件事的下一步动作相反：一个是「改成数字」，一个是「改成正数」。
        // 塌成同一个结果，用户就会按着错的提示去改。
        assertEquals(
            SleepTimerCustomInput.NotANumber,
            SleepTimerOptions.parseCustomInput("1 小时", "0"),
        )
        assertEquals(SleepTimerCustomInput.NotANumber, SleepTimerOptions.parseCustomInput("", "3.5"))
        assertEquals(SleepTimerCustomInput.TooShort, SleepTimerOptions.parseCustomInput("-1", "30"))
    }

    @Test
    fun `超出上限的输入不会被悄悄夹到上限`() {
        // 「2400」被静默改成 24 小时比报错更糟：用户填的是一个数，生效的是另一个，
        // 而这两者看起来都很「正常」。这条保住的是「填的值要么原样生效、要么明确被拒」。
        assertEquals(SleepTimerCustomInput.TooLong, SleepTimerOptions.parseCustomInput("2400", "0"))
        assertEquals(SleepTimerCustomInput.TooLong, SleepTimerOptions.parseCustomInput("999999", "0"))
    }

    @Test
    fun `自定义出来的时长认不出档位但认得出自己`() {
        // 200 分钟不是档位。芯片应该亮在「自定义…」上，而不是亮在 90 分钟那档
        // ——后者会让用户以为自己设的是 90 分钟。
        val custom = SleepTimerRules.startCountdown(t0, 200 * SleepTimerOptions.MINUTE_MS)
        assertNull(SleepTimerOptions.presetFor(custom))
        assertTrue(SleepTimerOptions.isCustom(custom))

        // 手填 30 分钟**就是** 30 分钟那一档：亮两个芯片会让「我到底设的是哪个」
        // 变成一个需要回答的问题，而它本来有唯一答案。
        val preset = SleepTimerRules.startCountdown(t0, 30 * SleepTimerOptions.MINUTE_MS)
        assertTrue(!SleepTimerOptions.isCustom(preset))

        assertTrue(!SleepTimerOptions.isCustom(SleepTimerState.Off))
        assertTrue(!SleepTimerOptions.isCustom(SleepTimerState.UntilItemEnd))
    }

    @Test
    fun `认不出档位的时长也要能写出具体多久`() {
        // 芯片上写「已开启」恰好没回答用户想问的那个问题（「我设了多久」）。
        // 这条守住的是：任意时长都有一句话能说出来。
        assertEquals(
            MspText.Res(R.string.msp_sleep_timer_hours_minutes, 3, 20),
            SleepTimerOptions.durationLabel(200 * SleepTimerOptions.MINUTE_MS),
        )
        // 档位内的值走同一条路：分开写会慢慢长出两种风格，比如一个「1 小时」
        // 一个「60 分钟」。
        assertEquals(
            SleepTimerOptions.label(5),
            SleepTimerOptions.durationLabel(5 * SleepTimerOptions.MINUTE_MS),
        )
    }

    @Test
    fun `分钟数不足一分钟时向上取整而不是写成零`() {
        // 「0 分钟」看起来像没设定时。这个换算只用来写字，多出来的几秒没人在意；
        // 写成 0 却会让人以为设置丢了。
        assertEquals(1, SleepTimerOptions.minutesOf(SleepTimerOptions.MINUTE_MS))
        assertEquals(2, SleepTimerOptions.minutesOf(90_000L))
        assertEquals(3, SleepTimerOptions.minutesOf(121_000L))
        assertEquals(0, SleepTimerOptions.minutesOf(0L))
        assertEquals(0, SleepTimerOptions.minutesOf(-1L))
        // 极值不溢出：先加后除会在 Long 顶端翻成负数，那样一个「特别长」的时长
        // 会被读成「没有定时」。
        assertTrue(SleepTimerOptions.minutesOf(Long.MAX_VALUE) > 0)
    }

    @Test
    fun `自定义输入框的初值就是当前定时`() {
        // 预填是为了「改一下刚才那个数」这条路：不预填就得把两格重新打一遍。
        val custom = SleepTimerRules.startCountdown(t0, 200 * SleepTimerOptions.MINUTE_MS)
        assertEquals(SleepTimerDraft(hours = 3, minutes = 20), SleepTimerOptions.draftOf(custom))

        val preset = SleepTimerRules.startCountdown(t0, 30 * SleepTimerOptions.MINUTE_MS)
        assertEquals(SleepTimerDraft(hours = 0, minutes = 30), SleepTimerOptions.draftOf(preset))

        // 没有定时时两格都空着：填一个 0 进去会让人以为自己填错过什么。
        assertNull(SleepTimerOptions.draftOf(SleepTimerState.Off))
        assertNull(SleepTimerOptions.draftOf(SleepTimerState.UntilItemEnd))
    }

    @Test
    fun `解析出来的分钟数乘上毫秒才是内核要的时长`() {
        // 面板说分钟、内核说毫秒，换算只发生在接线的那一行（`PlayerScreen` 里
        // `minutes * MINUTE_MS`）。这条盯的是「解析结果真的是分钟」：若单位写成秒，
        // 90 分钟会被设成 90 秒，而界面上从头到尾看不出区别。
        val parsed = SleepTimerOptions.parseCustomInput("1", "30")
        assertEquals(SleepTimerCustomInput.Valid(90), parsed)
        val minutes = (parsed as SleepTimerCustomInput.Valid).minutes
        assertEquals(
            SleepTimerState.Countdown(
                deadlineMs = t0 + 90 * SleepTimerOptions.MINUTE_MS,
                totalMs = 90 * SleepTimerOptions.MINUTE_MS,
            ),
            SleepTimerRules.startCountdown(t0, minutes * SleepTimerOptions.MINUTE_MS),
        )
    }

    @Test
    fun `自定义的上界比最大的档位宽`() {
        // 「自定义」如果只能填比档位更小的值，它就没有存在的意义。
        // 这条守的是两边的数量级关系，不是某个具体数字。
        assertTrue(SleepTimerOptions.CUSTOM_MAX_MINUTES > SleepTimerOptions.PRESETS_MINUTES.max())
        assertTrue(SleepTimerOptions.CUSTOM_MIN_MINUTES >= 1)
    }
}
