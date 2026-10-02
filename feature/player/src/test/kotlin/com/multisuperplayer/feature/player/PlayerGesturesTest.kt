package com.multisuperplayer.feature.player

import com.multisuperplayer.feature.player.PlayerGestures.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页手势的数学部分。
 *
 * 这些分支差一点在手上是感觉不出来的（拖动 3% 和 5% 的差别没人分得出来），
 * 唯一的办法是用断言把它们钉死。
 */
class PlayerGesturesTest {

    // ---- 左右半边 ----

    @Test
    fun `中线上的点算右半边`() {
        // 中线正好是一个像素列，写成 `<=` 还是 `<` 只影响这一列上的触摸。
        // 取右半的理由：`width / 2` 是整数除法，`x < width/2` 时右半比左半多一个
        // 像素，与「右半包含中线」等价。
        assertEquals(Side.RIGHT, PlayerGestures.sideOf(50f, 100f))
    }

    @Test
    fun `中线两侧各归各家`() {
        assertEquals(Side.LEFT, PlayerGestures.sideOf(49.9f, 100f))
        assertEquals(Side.RIGHT, PlayerGestures.sideOf(50.1f, 100f))
        assertEquals(Side.LEFT, PlayerGestures.sideOf(0f, 100f))
        assertEquals(Side.RIGHT, PlayerGestures.sideOf(100f, 100f))
    }

    @Test
    fun `宽度异常时不越界到右半边`() {
        // 布局还没测量出来时 width 会是 0；如果这里不拦，`x < 0` 恒假，
        // 于是每一次触摸都被当成「右半 = 音量」，而用户想调的是亮度。
        assertEquals(Side.LEFT, PlayerGestures.sideOf(0f, 0f))
        assertEquals(Side.LEFT, PlayerGestures.sideOf(500f, 0f))
        assertEquals(Side.LEFT, PlayerGestures.sideOf(500f, -10f))
    }

    // ---- 竖直拖动 ----

    @Test
    fun `往上拖变大`() {
        // 屏幕坐标 y 向下增长，而「往上拖 = 变大」才是直觉。
        // 符号反了的话亮度条会跟着手指反向走，非常难用。
        val up = PlayerGestures.levelAfterDrag(
            start = 0.5f,
            totalDy = -300f,
            height = 1000f,
            range = PlayerGestures.LevelRange.Volume,
        )
        assertTrue("往上拖 30% 应该变大，实际 $up", up > 0.5f)
        assertEquals(0.8f, up, 0.0001f)
    }

    @Test
    fun `往下拖变小`() {
        val down = PlayerGestures.levelAfterDrag(
            start = 0.5f,
            totalDy = 300f,
            height = 1000f,
            range = PlayerGestures.LevelRange.Volume,
        )
        assertEquals(0.2f, down, 0.0001f)
    }

    @Test
    fun `拖满整个高度变化一整段`() {
        // 手感的依据是「拖过屏幕的百分之几」而不是「拖了多少像素」，
        // 后者在高分屏上会变得极其迟钝（拖 200px 才动 5%）。
        assertEquals(
            1f,
            PlayerGestures.levelAfterDrag(0.5f, -500f, 1000f, PlayerGestures.LevelRange.Volume),
            0.0001f,
        )
        assertEquals(
            0f,
            PlayerGestures.levelAfterDrag(0.5f, 500f, 1000f, PlayerGestures.LevelRange.Volume),
            0.0001f,
        )
    }

    @Test
    fun `拖到头会夹住`() {
        val volume = PlayerGestures.LevelRange.Volume
        assertEquals(1f, PlayerGestures.levelAfterDrag(0.9f, -5000f, 1000f, volume), 0.0001f)
        assertEquals(0f, PlayerGestures.levelAfterDrag(0.1f, 5000f, 1000f, volume), 0.0001f)
    }

    @Test
    fun `亮度拖到底也不会全黑`() {
        // `screenBrightness = 0f` 就是 BRIGHTNESS_OVERRIDE_OFF，屏幕会真的黑掉，
        // 用户会以为拖坏了，而且黑屏上他也会怀疑自己还能不能拖回来。
        val brightness = PlayerGestures.LevelRange.Brightness
        assertEquals(0.01f, brightness.min, 0.0001f)
        assertEquals(
            0.01f,
            PlayerGestures.levelAfterDrag(0.5f, 5000f, 1000f, brightness),
            0.0001f,
        )
    }

    @Test
    fun `高度异常时保持原值`() {
        // 高度没测量出来时不能拿它做除法（除零 = Infinity / NaN），
        // 而 NaN 会一路传染到 Window 的亮度设置里。
        val range = PlayerGestures.LevelRange.Volume
        assertEquals(0.4f, PlayerGestures.levelAfterDrag(0.4f, -100f, 0f, range), 0.0001f)
        assertEquals(0.4f, PlayerGestures.levelAfterDrag(0.4f, -100f, -5f, range), 0.0001f)
        assertEquals(
            0.4f,
            PlayerGestures.levelAfterDrag(0.4f, -100f, Float.NaN, range),
            0.0001f,
        )
    }

    @Test
    fun `起始值越界时先夹回范围`() {
        assertEquals(
            1f,
            PlayerGestures.levelAfterDrag(2f, 0f, 1000f, PlayerGestures.LevelRange.Volume),
            0.0001f,
        )
    }

    @Test
    fun `clamp 把 NaN 当成下限`() {
        // 比较运算对 NaN 恒假，`coerceIn` 会把 NaN **原样返回**——面板上就会
        // 出现「NaN%」。取 min 而不是 max：宁可暗一点也不要满亮度刺眼。
        val range = PlayerGestures.LevelRange.Brightness
        assertEquals(0.01f, range.clamp(Float.NaN), 0.0001f)
    }

    // ---- 百分比显示 ----

    @Test
    fun `百分比取整`() {
        assertEquals(0, PlayerGestures.levelPercent(0f))
        assertEquals(50, PlayerGestures.levelPercent(0.5f))
        assertEquals(100, PlayerGestures.levelPercent(1f))
        assertEquals(38, PlayerGestures.levelPercent(0.379f))
    }

    @Test
    fun `百分比显示前夹住并挡掉 NaN`() {
        assertEquals(100, PlayerGestures.levelPercent(1.4f))
        assertEquals(0, PlayerGestures.levelPercent(-0.2f))
        assertEquals(0, PlayerGestures.levelPercent(Float.NaN))
    }

    @Test
    fun `双击步长是 10 秒`() {
        assertEquals(10_000L, PlayerGestures.DOUBLE_TAP_SEEK_MS)
    }
}
