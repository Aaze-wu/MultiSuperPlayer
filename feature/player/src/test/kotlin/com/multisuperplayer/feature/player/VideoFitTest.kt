package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.IntSize
import androidx.media3.ui.AspectRatioFrameLayout
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.player.MspVideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 画面尺寸的计算。
 *
 * 这些分支错了**不会崩**：只会得到一块比例不对的画面，而「比例不对」在手上
 * 很难判断（人眼分不出 16:9 和 1.85:1），所以必须靠断言。
 */
class VideoFitTest {

    private val full = MspVideoSize(widthPx = 1920, heightPx = 1080)

    @Test
    fun `缩放模式与比例模式一一对应`() {
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            VideoFit.resizeModeOf(AspectRatioMode.FIT),
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            VideoFit.resizeModeOf(AspectRatioMode.ORIGINAL),
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            VideoFit.resizeModeOf(AspectRatioMode.CROP),
        )
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            VideoFit.resizeModeOf(AspectRatioMode.STRETCH),
        )
    }

    @Test
    fun `裁剪和拉伸都铺满整块区域`() {
        // 这两个模式的矩形**就是**容器：真正的裁剪/拉伸由 PlayerView 自己的
        // RESIZE_MODE 完成，这里如果也按比例算，ZOOOM 就没有溢出可裁了。
        assertEquals(
            IntSize(800, 600),
            VideoFit.targetSize(AspectRatioMode.CROP, 800, 600, full),
        )
        assertEquals(
            IntSize(800, 600),
            VideoFit.targetSize(AspectRatioMode.STRETCH, 800, 600, full),
        )
    }

    @Test
    fun `适应模式把画面内接到容器里`() {
        // 宽片源：宽度顶满，高度按比例缩。
        assertEquals(
            IntSize(800, 450),
            VideoFit.targetSize(AspectRatioMode.FIT, 800, 800, full),
        )
    }

    @Test
    fun `竖屏片源在横向容器里高度顶满`() {
        // 1080×1920 的竖屏视频放进 800×800：高度顶满，宽度 = 800 × 0.5625。
        val portrait = MspVideoSize(widthPx = 1080, heightPx = 1920)
        assertEquals(
            IntSize(450, 800),
            VideoFit.targetSize(AspectRatioMode.FIT, 800, 800, portrait),
        )
    }

    @Test
    fun `非方形像素要算进去`() {
        // PAL DVD 的 720×576 anamorphic（PAR = 64/45 → 显示比例 16:9）：不做这一步
        // 会显示成 1.25:1，表现是「画面有点扁」，没人看得出来是少乘了一个系数。
        val dvd = MspVideoSize(widthPx = 720, heightPx = 576, pixelWidthHeightRatio = 64f / 45f)
        // 显示比例 16:9 在 1024×1024 的容器里：宽度顶满，高度 = 1024 × 9/16 = 576。
        assertEquals(
            IntSize(1024, 576),
            VideoFit.targetSize(AspectRatioMode.FIT, 1024, 1024, dvd),
        )
    }

    @Test
    fun `原始模式按片源像素显示`() {
        // 低分辨率片源在高分屏上只占一小块，这是「原始」的定义，不是 bug。
        val small = MspVideoSize(widthPx = 320, heightPx = 240)
        assertEquals(
            IntSize(320, 240),
            VideoFit.targetSize(AspectRatioMode.ORIGINAL, 1080, 2400, small),
        )
    }

    @Test
    fun `原始模式也乘像素宽高比`() {
        val dvd = MspVideoSize(widthPx = 720, heightPx = 576, pixelWidthHeightRatio = 64f / 45f)
        // 720 × 64/45 = 1024：「原始」也不是直接把 720 原样摆上去。
        assertEquals(
            IntSize(1024, 576),
            VideoFit.targetSize(AspectRatioMode.ORIGINAL, 1080, 2400, dvd),
        )
    }

    @Test
    fun `原始模式会超出容器`() {
        // 4K 片源在 1080p 屏幕上：不缩小，直接超出（外层裁掉）。
        // 如果这里「顺手」夹到容器大小，「原始」就变成「适应」了。
        val uhd = MspVideoSize(widthPx = 3840, heightPx = 2160)
        assertEquals(
            IntSize(3840, 2160),
            VideoFit.targetSize(AspectRatioMode.ORIGINAL, 1080, 1920, uhd),
        )
    }

    @Test
    fun `拿不到片源尺寸时退化成铺满`() {
        val unknown = MspVideoSize(widthPx = 0, heightPx = 0)
        // 「适应」和「原始」都要退化成铺满：不知道比例时任何内接计算都可能和实际
        // 画面不一致，交给 PlayerView 按它自己拿到的信息显示。
        assertEquals(
            IntSize(800, 600),
            VideoFit.targetSize(AspectRatioMode.FIT, 800, 600, unknown),
        )
        assertEquals(
            IntSize(800, 600),
            VideoFit.targetSize(AspectRatioMode.ORIGINAL, 800, 600, unknown),
        )
    }

    @Test
    fun `像素宽高比是 NaN 时也当成无效尺寸`() {
        // NaN 会一路传染到 Compose 的约束里，最后表现为整块画面消失。
        val broken = MspVideoSize(widthPx = 1920, heightPx = 1080, pixelWidthHeightRatio = Float.NaN)
        assertEquals(
            IntSize(800, 600),
            VideoFit.targetSize(AspectRatioMode.FIT, 800, 600, broken),
        )
    }

    @Test
    fun `容器还没测量出来时返回零尺寸`() {
        // 返回 0 而不是「铺满」：调用方据此跳过这一帧的摆放，
        // 拿一个 0 尺寸去 layout 会让画面整整消失一帧。
        assertEquals(IntSize.Zero, VideoFit.targetSize(AspectRatioMode.FIT, 0, 600, full))
        assertEquals(IntSize.Zero, VideoFit.targetSize(AspectRatioMode.FIT, 800, 0, full))
        assertEquals(IntSize.Zero, VideoFit.targetSize(AspectRatioMode.ORIGINAL, 0, 0, full))
        // 负数是畸形约束，一样当没测量出来。
        assertEquals(IntSize.Zero, VideoFit.targetSize(AspectRatioMode.FIT, -1, 600, full))
    }

    @Test
    fun `极扁的容器不会算出零高度`() {
        // 800×1 的容器配 16:9 画面：800 / 1.7778 = 450 > 1，正常。
        // 但 1×800 配一个极宽的片源时会算出高度 0——那会让画面消失，
        // 所以两条分支都有 `coerceAtLeast(1)`。
        val ultraWide = MspVideoSize(widthPx = 10_000, heightPx = 1)
        assertEquals(
            IntSize(1, 1),
            VideoFit.targetSize(AspectRatioMode.FIT, 1, 800, ultraWide),
        )
    }

    @Test
    fun `片源分辨率文字`() {
        assertEquals("1920×1080", VideoFit.sourceLabel(full))
        assertNull(VideoFit.sourceLabel(MspVideoSize(widthPx = 0, heightPx = 0)))
    }
}
