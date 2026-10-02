package com.multisuperplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倍速档位表。
 *
 * 三个方向都要钉：档位**越界**（界面上的按钮点了没反应）、最近档位**在距离相同时
 * 取哪个**（界面高亮会跳来跳去）、以及格式化（浮点值显示成 `1.2499999×`）。
 */
class PlaybackSpeedOptionsTest {

    @Test
    fun `每个档位都在内核接受的范围之内`() {
        // 这一条是整张表存在的意义：越界的档位会被内核夹回去，界面上就变成
        // 「点了没反应」的按钮，而这个 bug 在正常档位上完全看不出来。
        PlaybackSpeedOptions.PRESETS.forEach { preset ->
            assertTrue(
                "档位 $preset 小于内核下限 $MIN_PLAYBACK_SPEED",
                preset >= MIN_PLAYBACK_SPEED,
            )
            assertTrue(
                "档位 $preset 超过内核上限 $MAX_PLAYBACK_SPEED",
                preset <= MAX_PLAYBACK_SPEED,
            )
        }
    }

    @Test
    fun `档位表里包含默认速度且没有重复`() {
        assertTrue(PlaybackSpeedOptions.PRESETS.contains(PlaybackSpeedOptions.DEFAULT))
        assertEquals(
            PlaybackSpeedOptions.PRESETS.size,
            PlaybackSpeedOptions.PRESETS.toSet().size,
        )
    }

    @Test
    fun `正好是档位时返回它自己的下标`() {
        PlaybackSpeedOptions.PRESETS.forEachIndexed { index, preset ->
            assertEquals("档位 $preset", index, PlaybackSpeedOptions.nearestPresetIndex(preset))
        }
    }

    @Test
    fun `距离相同时取靠前的那个档位`() {
        // 1.125 到 1 和到 1.25 一样远。取靠前的那个（1×），
        // 这样结果只取决于档位表的顺序，而不是 `Float` 比较的偶然结果。
        assertEquals(2, PlaybackSpeedOptions.nearestPresetIndex(1.125f))
        assertEquals(1f, PlaybackSpeedOptions.nearestPreset(1.125f))
    }

    @Test
    fun `不在档位表里的值取最近的档位`() {
        assertEquals(1f, PlaybackSpeedOptions.nearestPreset(1.03f))
        assertEquals(0.5f, PlaybackSpeedOptions.nearestPreset(0.1f))
        assertEquals(4f, PlaybackSpeedOptions.nearestPreset(9f))
        assertEquals(4f, PlaybackSpeedOptions.nearestPreset(Float.MAX_VALUE))
        assertEquals(0.5f, PlaybackSpeedOptions.nearestPreset(0f))
        assertEquals(0.5f, PlaybackSpeedOptions.nearestPreset(-3f))
    }

    @Test
    fun `极大值不会被算成最慢档位`() {
        // `3.4e38 - 0.5` 和 `3.4e38 - 4` 在任何浮点精度下都舍入成同一个数，
        // 于是「谁更近」根本无法比较、每个候选都在平局，结果是最慢的 0.5×——
        // 方向整个反过来，而且不报错。修法是把输入夹进档位区间再比。
        assertEquals(4f, PlaybackSpeedOptions.nearestPreset(Float.MAX_VALUE))
        assertEquals(
            PlaybackSpeedOptions.PRESETS.last(),
            PlaybackSpeedOptions.nearestPreset(1_000_000f),
        )
    }

    @Test
    fun `非有限值落到默认档位而不是第一个`() {
        // 返回下标 0 的话，界面上高亮的是 0.5×——看起来用户选过它，而且
        // 「调到最慢」是个很容易被当成 bug 的行为。
        val defaultIndex = PlaybackSpeedOptions.PRESETS.indexOf(PlaybackSpeedOptions.DEFAULT)
        assertEquals(defaultIndex, PlaybackSpeedOptions.nearestPresetIndex(Float.NaN))
        assertEquals(defaultIndex, PlaybackSpeedOptions.nearestPresetIndex(Float.POSITIVE_INFINITY))
        assertEquals(defaultIndex, PlaybackSpeedOptions.nearestPresetIndex(Float.NEGATIVE_INFINITY))
        assertEquals(PlaybackSpeedOptions.DEFAULT, PlaybackSpeedOptions.nearestPreset(Float.NaN))
        // 显示文字和档位下标必须给同一个答案：设置页的文字来自 `format`，
        // 高亮来自 `nearestPresetIndex`，两边不一致时会出现
        // 「写着 1×、高亮的却是 0.5×」。
        assertEquals(
            PlaybackSpeedOptions.format(Float.POSITIVE_INFINITY),
            PlaybackSpeedOptions.format(PlaybackSpeedOptions.nearestPreset(Float.POSITIVE_INFINITY)),
        )
    }

    @Test
    fun `isPreset 只对正好相等的值成立`() {
        assertTrue(PlaybackSpeedOptions.isPreset(1.5f))
        assertFalse(PlaybackSpeedOptions.isPreset(1.5000001f))
        assertFalse(PlaybackSpeedOptions.isPreset(1.1f))
    }

    @Test
    fun `整数倍速不写小数部分`() {
        assertEquals("1×", PlaybackSpeedOptions.format(1f))
        assertEquals("2×", PlaybackSpeedOptions.format(2f))
        assertEquals("3×", PlaybackSpeedOptions.format(3f))
        assertEquals("4×", PlaybackSpeedOptions.format(4f))
    }

    @Test
    fun `小数倍速保留有意义的位数`() {
        assertEquals("0.5×", PlaybackSpeedOptions.format(0.5f))
        assertEquals("0.75×", PlaybackSpeedOptions.format(0.75f))
        assertEquals("1.25×", PlaybackSpeedOptions.format(1.25f))
        assertEquals("1.5×", PlaybackSpeedOptions.format(1.5f))
        assertEquals("2.5×", PlaybackSpeedOptions.format(2.5f))
    }

    @Test
    fun `浮点误差不会漏到界面上`() {
        // 档位值和 1.25 之间只要有一次浮点运算就可能变成 1.2499999，
        // 直接 `toString()` 会把它显示成「1.2499999×」。
        assertEquals("1.25×", PlaybackSpeedOptions.format(1.2499999f))
        assertEquals("1.5×", PlaybackSpeedOptions.format(1.4999999f))
    }

    @Test
    fun `非有限值退回默认速度的文字`() {
        // 显示成「NaN×」的话用户完全不知道发生了什么，而它的来源（内核还没报出速度）
        // 是暂时的。退回 1× 至少是一句人话。
        assertEquals("1×", PlaybackSpeedOptions.format(Float.NaN))
        assertEquals("1×", PlaybackSpeedOptions.format(Float.POSITIVE_INFINITY))
        assertEquals("1×", PlaybackSpeedOptions.format(Float.NEGATIVE_INFINITY))
    }
}
