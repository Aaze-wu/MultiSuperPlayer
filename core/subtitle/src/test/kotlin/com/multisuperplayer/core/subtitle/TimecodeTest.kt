package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.subtitle.internal.Timecode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 时间码是字幕解析里最容易被「看起来对」骗过去的地方：
 * 一个毫秒级的偏差不会报错，只会让整部片子的字幕慢慢漂移。
 */
class TimecodeTest {

    @Test
    fun `SRT 逗号毫秒`() {
        assertEquals(1_500L, Timecode.toMillis("00:00:01,500"))
    }

    @Test
    fun `VTT 点分毫秒`() {
        assertEquals(1_500L, Timecode.toMillis("00:00:01.500"))
    }

    @Test
    fun `ASS 小时只有一位且两位小数`() {
        // 0:00:01.00 —— 小数位 2 位代表 1/100 秒，不是 00 毫秒。
        assertEquals(1_000L, Timecode.toMillis("0:00:01.00"))
        assertEquals(1_230L, Timecode.toMillis("0:00:01.23"))
    }

    @Test
    fun `可以省略小时位`() {
        assertEquals(1_000L, Timecode.toMillis("00:01,000"))
    }

    @Test
    fun `一位小数表示十分之一秒`() {
        assertEquals(100L, Timecode.toMillis("00:00:00.1"))
    }

    @Test
    fun `超过 59 的秒字段判为解析失败`() {
        // 「00:75」如果当成 75 秒，说明这个字段根本不是秒——宁可判失败也不要算错。
        assertNull(Timecode.toMillis("00:75"))
    }

    @Test
    fun `无法解析返回 null 而不是 0`() {
        assertNull(Timecode.toMillis(""))
        assertNull(Timecode.toMillis(null))
        assertNull(Timecode.toMillis("不是时间"))
    }

    @Test
    fun `parseRange 取前两个时间码`() {
        val range = Timecode.parseRange("00:00:01,000 --> 00:00:02,500 X1:100 X2:200")
        assertEquals(1_000L to 2_500L, range)
    }

    @Test
    fun `TTML 偏移式时间表达式`() {
        assertEquals(1_500L, Timecode.parseClockToken("1.5s"))
        assertEquals(100L, Timecode.parseClockToken("100ms"))
        assertEquals(60_000L, Timecode.parseClockToken("1m"))
        assertEquals(3_600_000L, Timecode.parseClockToken("1h"))
    }

    @Test
    fun `tick 与帧按默认时基换算`() {
        // 默认 tickRate = 10000 → 300 tick = 30ms
        assertEquals(30L, Timecode.parseClockToken("300t"))
        // 默认 25fps → 25 帧 = 1 秒
        assertEquals(1_000L, Timecode.parseClockToken("25f"))
    }

    @Test
    fun `时钟写法优先于单位后缀解析`() {
        // "00:00:01.500" 里的冒号不能被当成单位后缀处理。
        assertEquals(1_500L, Timecode.parseClockToken("00:00:01.500"))
    }
}
