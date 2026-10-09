package com.multisuperplayer.core.data.update

import com.multisuperplayer.core.common.format.DurationDraft
import com.multisuperplayer.core.common.format.DurationInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自动检查更新的冷却窗口（纯规则）。
 *
 * 这一组盯的是「用户设的那个窗口真的是他设的那个」：设置页上的档位、存下来的毫秒值、
 * 判断要不要跳过的规则、以及自定义框里的两格，都是同一个数的四种样子。任何一处
 * 悄悄退回旧默认值（12 小时），表现都是**用户改了设置却什么都没变**——不崩、不报错，
 * 只是「怎么还查得这么勤 / 这么懒」，几乎不可能靠看日志发现。
 *
 * 默认值那条单独列出来：它必须等于 v1.2.3 之前写死的 12 小时。升级上来的用户没有
 * 做过任何选择，让他「什么都没点但查更新的频率变了」是一种静默改设置。
 */
class UpdateCooldownTest {

    // ------------------------------------------------------------ 档位表

    @Test
    fun `档位表是升序且不重复的`() {
        // 界面按这个顺序摆芯片，选择框靠值反查选中的是哪一格。乱序不会崩，
        // 只会让「12 小时」那一格出现在奇怪的位置，而且看不出是错的。
        assertEquals(UpdateCooldown.PRESETS_MS.sorted(), UpdateCooldown.PRESETS_MS)
        assertEquals(UpdateCooldown.PRESETS_MS.size, UpdateCooldown.PRESETS_MS.toSet().size)
    }

    @Test
    fun `每一档都在上下界之内`() {
        // 档位表里放一档填不出来的值（比如 10 分钟）会得到一个「选中了但保存不了」
        // 的框：芯片是选中的，而确定键是灰的。
        UpdateCooldown.PRESETS_MS.forEach { ms ->
            assertTrue(ms >= UpdateCooldown.MIN_MS, "$ms 比最短的 ${UpdateCooldown.MIN_MS} 还短")
            assertTrue(ms <= UpdateCooldown.MAX_MS, "$ms 比最长的 ${UpdateCooldown.MAX_MS} 还长")
        }
    }

    @Test
    fun `默认窗口还是十二小时`() {
        assertEquals(12 * UpdateCooldown.HOUR_MS, UpdateCooldown.DEFAULT_MS)
        assertEquals(UpdateCooldown.PRESET_12H_MS, UpdateCooldown.DEFAULT_MS)
    }

    @Test
    fun `上下界是十五分钟到七天`() {
        assertEquals(15, UpdateCooldown.MIN_MINUTES)
        assertEquals(7 * 24 * 60, UpdateCooldown.MAX_MINUTES)
        assertEquals(15 * UpdateCooldown.MINUTE_MS, UpdateCooldown.MIN_MS)
        assertEquals(7 * UpdateCooldown.DAY_MS, UpdateCooldown.MAX_MS)
    }

    // ------------------------------------------------------------ normalize

    @Test
    fun `没有值时退回默认而不是最短那一档`() {
        // 「没设置」和「用户要求最短窗口」是两件事。夹成 15 分钟会让应用开始
        // 替用户频繁请求，而这件事在界面上完全看不出来（那一格本来就写着 15 分钟，
        // 只是用户从没选过它）。
        assertEquals(UpdateCooldown.DEFAULT_MS, UpdateCooldown.normalize(null))
        assertEquals(UpdateCooldown.DEFAULT_MS, UpdateCooldown.normalize(0L))
        assertEquals(UpdateCooldown.DEFAULT_MS, UpdateCooldown.normalize(-1L))
    }

    @Test
    fun `越界的值被夹到边界上`() {
        // 不是退回默认值：这里读到的值**是有意义的**（旧版本写的、或者被外部改过），
        // 夹到边界比丢掉它更接近用户的意图。
        assertEquals(UpdateCooldown.MIN_MS, UpdateCooldown.normalize(1L))
        assertEquals(UpdateCooldown.MIN_MS, UpdateCooldown.normalize(UpdateCooldown.MIN_MS - 1))
        assertEquals(UpdateCooldown.MIN_MS, UpdateCooldown.normalize(UpdateCooldown.MIN_MS))
        assertEquals(UpdateCooldown.MAX_MS, UpdateCooldown.normalize(UpdateCooldown.MAX_MS))
        assertEquals(UpdateCooldown.MAX_MS, UpdateCooldown.normalize(UpdateCooldown.MAX_MS + 1))
        assertEquals(UpdateCooldown.MAX_MS, UpdateCooldown.normalize(Long.MAX_VALUE))
    }

    @Test
    fun `界内的值原样保留`() {
        // 自定义档必须真的能存下来：任何一个整数值都不该在路上被改掉。
        UpdateCooldown.PRESETS_MS.forEach { ms ->
            assertEquals(ms, UpdateCooldown.normalize(ms))
        }
        assertEquals(90 * UpdateCooldown.MINUTE_MS, UpdateCooldown.normalize(90 * UpdateCooldown.MINUTE_MS))
    }

    // ------------------------------------------------------------ 档位判定

    @Test
    fun `认档看的是值而不是「当初从哪一格设进来的」`() {
        // 记「来源」会得到「停在自定义那一格、值却恰好等于 12 小时」这种双份真相：
        // 界面把自定义选中，而右边那行写着 12 小时。
        assertNull(UpdateCooldown.presetFor(UpdateCooldown.MIN_MS))
        assertEquals(UpdateCooldown.PRESET_12H_MS, UpdateCooldown.presetFor(UpdateCooldown.PRESET_12H_MS))
        assertFalse(UpdateCooldown.isCustom(UpdateCooldown.PRESET_12H_MS))
        assertTrue(UpdateCooldown.isCustom(UpdateCooldown.PRESET_12H_MS + UpdateCooldown.MINUTE_MS))
    }

    @Test
    fun `每一档都认得出来`() {
        UpdateCooldown.PRESETS_MS.forEach { ms ->
            assertEquals(ms, UpdateCooldown.presetFor(ms))
            assertFalse(UpdateCooldown.isCustom(ms), "$ms 被当成了自定义档")
        }
    }

    // ------------------------------------------------------------ 分钟数与初值

    @Test
    fun `不足一分钟算一分钟`() {
        assertEquals(1, UpdateCooldown.minutesOf(1L))
        assertEquals(1, UpdateCooldown.minutesOf(UpdateCooldown.MINUTE_MS))
        assertEquals(2, UpdateCooldown.minutesOf(UpdateCooldown.MINUTE_MS + 1))
    }

    @Test
    fun `非正值是零分钟`() {
        assertEquals(0, UpdateCooldown.minutesOf(0L))
        assertEquals(0, UpdateCooldown.minutesOf(-1L))
    }

    @Test
    fun `极大值不会溢出成负数`() {
        // 先除再取整：先做加法（`+ MINUTE_MS - 1`）在 `Long.MAX_VALUE` 附近会绕回
        // 负数，于是「最多」变成「最少」。
        assertEquals(Int.MAX_VALUE, UpdateCooldown.minutesOf(Long.MAX_VALUE))
    }

    @Test
    fun `三小时二十分拆成两格`() {
        assertEquals(
            DurationDraft(hours = 3, minutes = 20),
            UpdateCooldown.draftOf(200 * UpdateCooldown.MINUTE_MS),
        )
    }

    @Test
    fun `整小时那一档的分钟格是空的`() {
        // `DurationDraft` 把 0 的那一格留空：一份「12 小时 0 分」的初值会让人以为
        // 自己填错过什么。
        assertEquals(
            DurationDraft(hours = 12, minutes = 0),
            UpdateCooldown.draftOf(UpdateCooldown.PRESET_12H_MS),
        )
        assertEquals("12", UpdateCooldown.draftOf(UpdateCooldown.PRESET_12H_MS).hoursText())
        assertEquals("", UpdateCooldown.draftOf(UpdateCooldown.PRESET_12H_MS).minutesText())
    }

    // ------------------------------------------------------------ 自定义输入

    @Test
    fun `拆出来的两格能原样解析回去`() {
        // 「点开自定义只是想看一眼」这条路必须无损：拆开再拼不能把 90 分钟
        // 变成 91 分钟（那会让用户以为自己设过的东西被改了）。
        UpdateCooldown.PRESETS_MS.forEach { ms ->
            val draft = UpdateCooldown.draftOf(ms)
            assertEquals(
                DurationInput.Valid(UpdateCooldown.minutesOf(ms)),
                UpdateCooldown.parseInput(draft.hoursText(), draft.minutesText()),
            )
        }
    }

    @Test
    fun `解析用的是这一档自己的上下界`() {
        // 和睡眠定时共用同一条解析（1 分钟 ~ 24 小时），界限却不同：
        // 15 分钟在那边合法，在这里不合法。传错上下界不会编译报错，只会
        // 让用户填出一个永远查不了更新的窗口。
        assertEquals(DurationInput.Blank, UpdateCooldown.parseInput("", ""))
        assertEquals(DurationInput.TooShort, UpdateCooldown.parseInput("", "14"))
        assertEquals(DurationInput.Valid(UpdateCooldown.MIN_MINUTES), UpdateCooldown.parseInput("", "15"))
        assertEquals(
            DurationInput.Valid(UpdateCooldown.MAX_MINUTES),
            UpdateCooldown.parseInput("168", ""),
        )
        assertEquals(DurationInput.TooLong, UpdateCooldown.parseInput("168", "1"))
    }
}
