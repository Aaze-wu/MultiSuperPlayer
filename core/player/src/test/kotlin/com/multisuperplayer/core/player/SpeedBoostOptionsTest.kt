package com.multisuperplayer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按加速用的倍速档位。
 *
 * 三件事在这里是致命的，而且在手上都试不出来：
 * - **档位越界**：内核会把 8× 夹成 4×，于是「设置里选 8×、按住只有 4×」——
 *   只有按着秒表试才发现；
 * - **`normalize` 抛异常**：这条路径在按住画面的那一刻执行，抛出去就是「按住就崩」；
 * - **平局取大**：2.5× 这种值取 2 还是取 3 都「能跑」，但它决定了用户在
 *   设置里改档位表时第一眼看到的是哪一档。
 */
class SpeedBoostOptionsTest {

    @Test
    fun `每个档位都在内核接受的范围之内`() {
        SpeedBoostOptions.PRESETS.forEach { preset ->
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
    fun `最快档就等于内核上限`() {
        // 按**字面量**钉：这条断言要能拦住「把 4f 改成 8f」这种编辑，
        // 而写成 `MAX == MAX_PLAYBACK_SPEED` 的话两边一起改也能过——那时
        // 内核会把 8× 夹成 4×，用户看到的是「选 8× 没用」。
        assertEquals(4f, SpeedBoostOptions.MAX, 0f)
    }

    @Test
    fun `默认值是 2 倍`() {
        assertEquals(2f, SpeedBoostOptions.DEFAULT, 0f)
        // 默认值本身必须是一个能选中的档位，否则设置页会出现「值是 2×、
        // 却没有任何一项被高亮」的情况。
        assertTrue(SpeedBoostOptions.PRESETS.contains(SpeedBoostOptions.DEFAULT))
    }

    @Test
    fun `没设置过时用默认值`() {
        assertEquals(SpeedBoostOptions.DEFAULT, SpeedBoostOptions.normalize(null), 0f)
    }

    @Test
    fun `坏值一律退回默认值而不是抛异常`() {
        // 这条路径每一次长按都会走。抛出去 = 按住画面就崩，所以除了「不抛」
        // 之外不要求任何别的行为。
        assertEquals(SpeedBoostOptions.DEFAULT, SpeedBoostOptions.normalize(Float.NaN), 0f)
        assertEquals(
            SpeedBoostOptions.DEFAULT,
            SpeedBoostOptions.normalize(Float.POSITIVE_INFINITY),
            0f,
        )
        assertEquals(SpeedBoostOptions.DEFAULT, SpeedBoostOptions.normalize(0f), 0f)
        assertEquals(SpeedBoostOptions.DEFAULT, SpeedBoostOptions.normalize(-2f), 0f)
    }

    @Test
    fun `小于等于零不会被收敛成最慢档`() {
        // 0 是「缺键」在 DataStore 里的形状，负数只能是被写坏的值。两者都该回到
        // 默认的 2×；如果被 `minBy` 悄悄映射到 1.5×，界面会显示 1.5× 且看起来
        // 完全正常——用户永远不知道自己的设置被改过。
        assertFalse(SpeedBoostOptions.normalize(0f) == 1.5f)
        assertFalse(SpeedBoostOptions.normalize(-1f) == 1.5f)
    }

    @Test
    fun `不在档位上的值取最近的档位`() {
        assertEquals(1.5f, SpeedBoostOptions.normalize(1.4f), 0f)
        assertEquals(2f, SpeedBoostOptions.normalize(1.9f), 0f)
        assertEquals(4f, SpeedBoostOptions.normalize(3.7f), 0f)
    }

    @Test
    fun `超出范围的巨大值取最快档`() {
        // `Float.MAX_VALUE` 和每个档位的差都等于它自己，朴素的比较循环会
        // 一个都不更新、返回**最慢**的档位——方向整个反过来，而且不报错。
        assertEquals(SpeedBoostOptions.MAX, SpeedBoostOptions.normalize(Float.MAX_VALUE), 0f)
        assertEquals(SpeedBoostOptions.MAX, SpeedBoostOptions.normalize(1000f), 0f)
    }

    @Test
    fun `恰好卡在两档中间时取小的那一档`() {
        // 2.5 离 2 和 3 一样远。取小的理由：加速这种事「不够快」比「太快听不清」
        // 好纠正——不够快时用户会再按一次或去设置里调，听不清则要先怀疑播放器坏了。
        assertEquals(2f, SpeedBoostOptions.normalize(2.5f), 0f)
        // 3.5 同理，用来排除「只是碰巧 2 在列表里靠前」。
        assertEquals(3f, SpeedBoostOptions.normalize(3.5f), 0f)
    }

    @Test
    fun `档位表是升序的`() {
        // `normalize` 的平局规则直接依赖这一点：`minBy` 返回第一个最小值，
        // 只有升序时「第一个」才等于「最小的那个」。
        val presets = SpeedBoostOptions.PRESETS
        assertEquals(presets.sorted(), presets)
    }

    @Test
    fun `格式化和常态倍速的口径一致`() {
        assertEquals("2×", SpeedBoostOptions.format(2f))
        assertEquals("1.5×", SpeedBoostOptions.format(1.5f))
        assertEquals("2×", SpeedBoostOptions.format(2.0000001f))
    }

    @Test
    fun `格式化也走收敛`() {
        // 设置页的字面值来自这里。不收敛的话，一个被写坏的值会在界面上显示
        // 「8×」，而按住时实际是 4×——文字和事实不符，且只在真机上按着才发现。
        assertEquals("2×", SpeedBoostOptions.format(null))
        assertEquals("2×", SpeedBoostOptions.format(Float.NaN))
        assertEquals("4×", SpeedBoostOptions.format(8f))
    }
}
