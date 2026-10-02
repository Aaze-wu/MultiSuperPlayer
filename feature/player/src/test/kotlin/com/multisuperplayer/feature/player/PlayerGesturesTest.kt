package com.multisuperplayer.feature.player

import com.multisuperplayer.feature.player.PlayerGestures.DragAxis
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

    // ---- 拖动方向判定 ----

    @Test
    fun `没超过触摸阈值时方向未定`() {
        // 手指还没动够就先锁方向的话，一次竖直拖动的前几帧会被当成水平——
        // 用户会看到「想调亮度，进度却跳了一下」。这也是为什么方向只能在
        // **越过阈值之后**判一次。
        assertEquals(DragAxis.NONE, PlayerGestures.axisFor(0f, 0f, SLOP))
        assertEquals(DragAxis.NONE, PlayerGestures.axisFor(SLOP - 1f, 0f, SLOP))
        assertEquals(DragAxis.NONE, PlayerGestures.axisFor(0f, SLOP - 1f, SLOP))
        assertEquals(DragAxis.NONE, PlayerGestures.axisFor(SLOP * 0.7f, SLOP * 0.7f, SLOP))
    }

    @Test
    fun `水平位移更大时判为水平`() {
        assertEquals(DragAxis.HORIZONTAL, PlayerGestures.axisFor(SLOP * 2f, SLOP, SLOP))
        assertEquals(DragAxis.HORIZONTAL, PlayerGestures.axisFor(-SLOP * 2f, SLOP, SLOP))
    }

    @Test
    fun `竖直位移更大时判为竖直`() {
        assertEquals(DragAxis.VERTICAL, PlayerGestures.axisFor(SLOP, SLOP * 2f, SLOP))
        assertEquals(DragAxis.VERTICAL, PlayerGestures.axisFor(SLOP, -SLOP * 2f, SLOP))
    }

    @Test
    fun `正对角线判为竖直`() {
        // 45° 是唯一必须选一边的地方。选竖直：水平拖动会**在松手时跳转**，
        // 而竖直是连续可逆的——判错方向时，「本来想调音量却跳了进度」比
        // 「本来想拖进度却调了音量」难受得多（后者滑回来就恢复原样）。
        assertEquals(DragAxis.VERTICAL, PlayerGestures.axisFor(SLOP * 3f, SLOP * 3f, SLOP))
    }

    @Test
    fun `非有限位移判为未定`() {
        // NaN 会让所有比较恒假，`if (ax > ay)` 落到 else 分支，于是「不知道
        // 在往哪拖」被当成竖直拖动——音量会莫名其妙地跳。必须先挡掉。
        assertEquals(DragAxis.NONE, PlayerGestures.axisFor(Float.NaN, 10f, SLOP))
        assertEquals(DragAxis.NONE, PlayerGestures.axisFor(10f, Float.NaN, SLOP))
        assertEquals(
            DragAxis.NONE,
            PlayerGestures.axisFor(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, SLOP),
        )
    }

    // ---- 拖动进度换算 ----

    @Test
    fun `拖过整屏等于整段时长`() {
        // 这条是「跟手」的定义：手指走完屏幕宽度，进度正好走完整个片子。
        // 比例错了的话拖动会越拖越偏，而拖动是唯一能精确定位的操作。
        //
        // 从 0 开始算，否则会撞上结尾的夹取（起点 10 秒 + 整段 60 秒 = 70 秒
        // 会被夹回 60 秒，看起来像「算错了」，其实是夹对了）。
        assertEquals(
            60_000L,
            PlayerGestures.seekTarget(0L, SCREEN_WIDTH, SCREEN_WIDTH, 60_000L),
        )
        assertEquals(
            30_000L,
            PlayerGestures.seekTarget(0L, SCREEN_WIDTH / 2f, SCREEN_WIDTH, 60_000L),
        )
    }

    @Test
    fun `往回拖是往回跳`() {
        val target = PlayerGestures.seekTarget(
            startPositionMs = 30_000L,
            totalDx = -SCREEN_WIDTH / 2f,
            width = SCREEN_WIDTH,
            durationMs = 60_000L,
        )
        assertEquals(0L, target)
    }

    @Test
    fun `拖出两头会被夹住`() {
        // 不夹的话 `seekTo` 收到负数或超出时长的位置，内核的行为依实现而定
        // （有的从头开始播、有的干脆失败），用户看到的是「拖过头就黑屏」。
        assertEquals(
            0L,
            PlayerGestures.seekTarget(5_000L, -SCREEN_WIDTH * 10f, SCREEN_WIDTH, 60_000L),
        )
        assertEquals(
            60_000L,
            PlayerGestures.seekTarget(5_000L, SCREEN_WIDTH * 10f, SCREEN_WIDTH, 60_000L),
        )
    }

    @Test
    fun `时长未知时不拖动进度`() {
        // 时长未知时**返回原位置**而不是 0：跳到片头看起来像播放器重新开始了，
        // 而「拖了没反应」至少还是个能理解的现象。
        assertEquals(
            12_345L,
            PlayerGestures.seekTarget(12_345L, SCREEN_WIDTH, SCREEN_WIDTH, durationMs = 0L),
        )
        assertEquals(
            12_345L,
            PlayerGestures.seekTarget(12_345L, SCREEN_WIDTH, SCREEN_WIDTH, durationMs = -1L),
        )
    }

    @Test
    fun `宽度未知时返回原位置`() {
        // 布局还没测量出来时 width 是 0，除法会得到 Infinity，再夹一次就成了
        // 「拖一下就跳到片尾」——而这一帧恰好是用户刚进播放页的那一帧。
        assertEquals(12_345L, PlayerGestures.seekTarget(12_345L, 100f, 0f, 60_000L))
        assertEquals(12_345L, PlayerGestures.seekTarget(12_345L, 100f, -5f, 60_000L))
    }

    @Test
    fun `位移非有限时返回原位置`() {
        assertEquals(12_345L, PlayerGestures.seekTarget(12_345L, Float.NaN, SCREEN_WIDTH, 60_000L))
        assertEquals(
            12_345L,
            PlayerGestures.seekTarget(12_345L, Float.POSITIVE_INFINITY, SCREEN_WIDTH, 60_000L),
        )
    }

    @Test
    fun `起点本身就超界时先夹回区间`() {
        // 起点来自播放位置，理论上总在区间内；但「之前记住的位置」可能比这次
        // 的时长还大（换了文件、或者时长是猜的）。不先夹，偏移量会从一个
        // 不存在的起点开始算，结果偏差等于那段多出来的长度。
        assertEquals(60_000L, PlayerGestures.seekTarget(90_000L, 0f, SCREEN_WIDTH, 60_000L))
        assertEquals(0L, PlayerGestures.seekTarget(-5_000L, 0f, SCREEN_WIDTH, 60_000L))
    }

    @Test
    fun `长片子的换算不会因为浮点精度而丢秒`() {
        // 3 小时 = 1.08e7 毫秒。用 `Float` 算的话有效位不够，实测偏差能到几百毫秒
        // ——拖动条上看着「差不多」，但用户是在拿它找某一句台词。这里按
        // 1e-3 的相对量级卡住。
        val duration = 3 * 60 * 60 * 1000L
        val target = PlayerGestures.seekTarget(0L, SCREEN_WIDTH / 4f, SCREEN_WIDTH, duration)
        assertEquals(2_700_000L, target)
    }

    private companion object {
        /** 一个具体的屏幕宽度，避免每条断言都要先想「用哪个数」。 */
        const val SCREEN_WIDTH = 1080f

        const val SLOP = 12f
    }
}
