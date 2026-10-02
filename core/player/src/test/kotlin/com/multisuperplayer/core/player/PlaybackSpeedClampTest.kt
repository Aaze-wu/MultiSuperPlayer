package com.multisuperplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 倍速的夹取规则。
 *
 * 这个函数只有一个职责，但它是**两个方向的公共契约**：
 *
 * - 内核用它决定「真正会按多少倍速放」；
 * - 播放页用它决定「往设置里存多少」（存错了的症状是「界面显示 1.5×、重启回到 1×」，
 *   而这个症状看上去像设置没保存，实际上保存了一个**别的**值）。
 *
 * 所以每条测试都对应一个真实会发生的错误方向：
 * 越界值不夹（内核抛异常或在界面上变成点了没反应的档位）、
 * 非有限值不处理（`coerceIn` 对 NaN 是原样返回，直接把它交给 Media3 会崩）、
 * 以及「夹取口径」和 `PlaybackSpeedOptions.format`/`nearestPresetIndex` 不一致
 * （界面上写着 1×、实际按 4×，或者反过来）。
 */
class PlaybackSpeedClampTest {

    @Test
    fun `范围内正常的值原样返回`() {
        listOf(0.5f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { speed ->
            assertEquals("速度 $speed 不该被改动", speed, clampPlaybackSpeed(speed), 0f)
        }
    }

    @Test
    fun `两个端点原样返回`() {
        // 端点是最容易写错的地方（`<` 写成 `<=` 就会把端点也夹走一格）。
        assertEquals(MIN_PLAYBACK_SPEED, clampPlaybackSpeed(MIN_PLAYBACK_SPEED), 0f)
        assertEquals(MAX_PLAYBACK_SPEED, clampPlaybackSpeed(MAX_PLAYBACK_SPEED), 0f)
    }

    @Test
    fun `低于下界时夹到下限`() {
        listOf(0f, -1f, 0.24f, 0.001f, -Float.MAX_VALUE).forEach { speed ->
            assertEquals("速度 $speed", MIN_PLAYBACK_SPEED, clampPlaybackSpeed(speed), 0f)
        }
    }

    @Test
    fun `高于上界时夹到上限`() {
        // `Float.MAX_VALUE` 单列出来：它与上下界的差值都会舍入到同一个量级，
        // 任何「先算距离再挑」的实现都会在这里翻车（见 nearestPresetIndex 的注释）。
        // 夹取是纯比较，不受影响——这一条就是钉住「它没有被改成比较距离」。
        listOf(4.01f, 100f, Float.MAX_VALUE).forEach { speed ->
            assertEquals("速度 $speed", MAX_PLAYBACK_SPEED, clampPlaybackSpeed(speed), 0f)
        }
    }

    @Test
    fun `NaN 退成默认速度`() {
        // `Float.coerceIn` 对 NaN 原样返回（两个比较都是 false），
        // 而 Media3 的 setPlaybackSpeed(NaN) 会抛异常——所以必须显式处理。
        assertEquals(PlaybackSpeedOptions.DEFAULT, clampPlaybackSpeed(Float.NaN), 0f)
    }

    @Test
    fun `正负无穷都退成默认速度`() {
        // 无穷不能被当成「无限快 / 无限慢」夹到端点上：它表达的是「这个值没有意义」，
        // 与 format/nearestPresetIndex 对非有限值的口径保持一致。
        assertEquals(PlaybackSpeedOptions.DEFAULT, clampPlaybackSpeed(Float.POSITIVE_INFINITY), 0f)
        assertEquals(PlaybackSpeedOptions.DEFAULT, clampPlaybackSpeed(Float.NEGATIVE_INFINITY), 0f)
    }

    @Test
    fun `非有限值的口径与格式化一致`() {
        // 界面上的文字来自 format、存盘的值来自 clamp：两者必须对同一个输入给出
        // 同一个结论，否则会出现「文字写着 1×、存的是 4×」。
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { speed ->
            assertEquals(
                "速度 $speed 的显示与存盘口径不一致",
                PlaybackSpeedOptions.format(PlaybackSpeedOptions.DEFAULT),
                PlaybackSpeedOptions.format(clampPlaybackSpeed(speed)),
            )
        }
    }

    @Test
    fun `所有档位夹取后都不变`() {
        // 档位表里的值必须是「夹取不动」的，否则界面上选中的档位与内核实际用的
        // 不是同一个——正是 PlaybackSpeedOptionsTest 第一条要防的那件事。
        PlaybackSpeedOptions.PRESETS.forEach { preset ->
            assertEquals("档位 $preset", preset, clampPlaybackSpeed(preset), 0f)
        }
    }

    @Test
    fun `任何输入的结果都落在内核接受的区间内`() {
        // 这条是对「不许原样放行越界值」的整体保证：内核那一侧不再需要二次设防。
        val inputs = listOf(
            Float.NaN,
            Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            -1f,
            0f,
            0.02f,
            3.5f,
            99f,
            Float.MAX_VALUE,
        )
        inputs.forEach { speed ->
            val clamped = clampPlaybackSpeed(speed)
            assertTrue(
                "速度 $speed 夹取后得到 $clamped，越界了",
                clamped in MIN_PLAYBACK_SPEED..MAX_PLAYBACK_SPEED,
            )
        }
    }
}
