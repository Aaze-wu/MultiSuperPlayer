package com.multisuperplayer.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕**速率**（比例缩放）：换算、格式化、钳位。
 *
 * ## 为什么速率和时间轴偏移是两个旋钮
 *
 * 「字幕对不上声音」有两个成因，数学上一个是加、一个是乘：
 *
 * - 全片每句都早/晚**同样多**（整体延后 0.5 秒）⇒ 偏移，`t' = t − 偏移`。
 * - 越到后面偏得越多（字幕与片源**帧率**不一致）⇒ 速率，`t' = t × 速率 − 偏移`。
 *   典型差值：23.976↔25 = 4.27%、24↔25 = 4.17%、PAL 加速 = 4%。
 *   这种用偏移把开头调准就一定会把结尾调错，两小时的片子末尾能差出上百秒。
 *
 * ## 被钉住的两条不能错的规则
 *
 * 1. **先缩放再平移**。反过来会把用户已经调好的偏移一起放大：偏移 `+2.0 秒`
 *    在 `1.04×` 下会变成 `2.08 秒`，两个旋钮开始互相干扰，用户越调越乱。
 *    这正是 nextlib 的 `syncSpeedMultiplier` 的算法（`pos * mult - offsetMs * 1000`）。
 * 2. **钳位**：率的上下限是 ±10%，见 [clampSubtitleRate]。
 */
class SubtitleRateTest {

    // ------------------------------------------------------------ 换算

    @Test
    fun `原速时换算结果就是原样`() {
        assertEquals(12_345L, subtitleCuePosition(positionMs = 12_345L, timelineOffsetMs = 0L))
        assertEquals(
            12_345L,
            subtitleCuePosition(
                positionMs = 12_345L,
                timelineOffsetMs = 0L,
                ratePermille = SUBTITLE_RATE_BASE_PERMILLE,
            ),
        )
    }

    @Test
    fun `速率是比例缩放`() {
        // 1.04×：第 100 秒要去查第 104 秒的台词（台词被推后了 4%）。
        assertEquals(
            104_000L,
            subtitleCuePosition(100_000L, timelineOffsetMs = 0L, ratePermille = 1_040),
        )
        // 0.96×：反过来往前拉。帧率 25 的字幕配 23.976 的片源就是这个方向。
        assertEquals(
            96_000L,
            subtitleCuePosition(100_000L, timelineOffsetMs = 0L, ratePermille = 960),
        )
    }

    @Test
    fun `先缩放再平移——不能把已经调好的偏移一起放大`() {
        // 一小时的片子里，用户已经把偏移调成 +2.0 秒，速率是 1.04×。
        val positionMs = 3_600_000L
        val offsetMs = 2_000L
        val ratePermille = 1_040

        // 正确的顺序：t × 速率 − 偏移。
        assertEquals(
            3_742_000L,
            subtitleCuePosition(positionMs, offsetMs, ratePermille),
        )
        // 反过来（t − 偏移）× 速率 会把那 2 秒也放大 4%，末尾偏出 80 毫秒：
        // 两个旋钮互相干扰，用户会以为「调了偏移之后速率也跟着变了」。
        assertNotEquals(
            "偏移不该参与缩放",
            (positionMs - offsetMs) * ratePermille / SUBTITLE_RATE_BASE_PERMILLE,
            subtitleCuePosition(positionMs, offsetMs, ratePermille),
        )
    }

    @Test
    fun `偏移很大时算出来是负数——查不到 cue 就是没有字幕`() {
        // 不是错误：`SubtitleDocument.cueAt` 对负时刻返回 null，屏幕上什么都不画。
        assertTrue(subtitleCuePosition(1_000L, 5_000L, SUBTITLE_RATE_BASE_PERMILLE) < 0L)
    }

    @Test
    fun `长时间播放也不会溢出`() {
        // 十小时（36000 秒）× 1.10 = 11 小时，仍在 Long 能表示的毫秒范围内，
        // 而整数乘法只在最后截断，所以中途没有精度损失。
        assertEquals(
            39_600_000L,
            subtitleCuePosition(36_000_000L, timelineOffsetMs = 0L, ratePermille = 1_100),
        )
    }

    // ------------------------------------------------------------ 钳位

    @Test
    fun `速率钳在正负百分之十之内`() {
        assertEquals(1_100, clampSubtitleRate(1_200))
        assertEquals(1_100, clampSubtitleRate(1_100))
        assertEquals(900, clampSubtitleRate(800))
        assertEquals(900, clampSubtitleRate(900))
        assertEquals(1_043, clampSubtitleRate(1_043))
    }

    @Test
    fun `极限能盖住已知的帧率差`() {
        // 覆盖 ±10% 是因为要把这几档都装进去：4.27%（23.976↔25）、4.17%（24↔25）、4%（PAL）。
        assertTrue(SUBTITLE_RATE_LIMIT_PERMILLE >= 43)
    }

    // ------------------------------------------------------------ 档位与步长

    @Test
    fun `固定档位里必须含原速`() {
        // 档位同时是速率的「归零」入口：界面上没有另一个叫「归零」的按钮，
        // 就是为了避免和上面时间轴的「归零」混起来。
        assertTrue(SUBTITLE_RATE_PRESETS_PERMILLE.contains(SUBTITLE_RATE_BASE_PERMILLE))
        assertEquals(
            "档位必须升序，否则芯片排出来的顺序会莫名其妙",
            SUBTITLE_RATE_PRESETS_PERMILLE.sorted(),
            SUBTITLE_RATE_PRESETS_PERMILLE,
        )
        assertTrue(SUBTITLE_RATE_PRESETS_PERMILLE.all { clampSubtitleRate(it) == it })
    }

    @Test
    fun `档位加上细调能凑到帧率差`() {
        // 23.976 与 25 之间差 4.27%，不是整百分点，所以固定档位到不了：档位只到 1.04×。
        // 从 1.04× 再按 3 下「+0.1%」就是 1.043×，误差 0.03%——两小时的片子末尾偏不到 2 秒。
        // 这就是「固定档位 + 累加细调」两排按钮都要存在的理由。
        val reached = 1_040 + 3 * 1
        assertEquals(1_043, clampSubtitleRate(reached))
        assertEquals("1.043", formatSubtitleRate(reached))
    }

    @Test
    fun `步长有粗有细且两边对称`() {
        // 只给粗步长，「差 0.4% 但按钮只能一次跳 1%」就永远对不准；
        // 只给细步长，从 1.00× 到 1.04× 要按四十次。
        assertEquals(listOf(-10, -1, 1, 10), SUBTITLE_RATE_STEPS_PERMILLE)
        val steps = SUBTITLE_RATE_STEPS_PERMILLE.toSet()
        assertTrue("每一步都要有反方向的那一步", steps.all { -it in steps })
    }

    // ------------------------------------------------------------ 显示文本

    @Test
    fun `速率显示两位小数`() {
        assertEquals("1.00", formatSubtitleRate(1_000))
        assertEquals("1.02", formatSubtitleRate(1_020))
        assertEquals("1.04", formatSubtitleRate(1_040))
        assertEquals("0.96", formatSubtitleRate(960))
        assertEquals("0.99", formatSubtitleRate(990))
        assertEquals("1.10", formatSubtitleRate(1_100))
    }

    @Test
    fun `细调出来的值显示第三位——否则按了按钮看起来没反应`() {
        // 只有两位的话 `1.001×` 会显示成 `1.00`，用户按了「+0.1%」看到的数字没变，
        // 会一直按下去直到冲出上限。
        assertEquals("1.001", formatSubtitleRate(1_001))
        assertEquals("1.005", formatSubtitleRate(1_005))
    }

    @Test
    fun `步长文本是百分比而不是千分比`() {
        // 步长内部是千分比（10‰ = 1%），按钮上必须写百分比，否则用户看到「+10%」。
        assertEquals("+1", formatSubtitleRateStep(10))
        assertEquals("-1", formatSubtitleRateStep(-10))
        assertEquals("+0.1", formatSubtitleRateStep(1))
        assertEquals("-0.1", formatSubtitleRateStep(-1))
        assertEquals("+0.4", formatSubtitleRateStep(4))
    }
}
