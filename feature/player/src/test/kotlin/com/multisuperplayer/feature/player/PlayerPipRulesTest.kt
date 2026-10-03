package com.multisuperplayer.feature.player

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.player.MspVideoSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画中画窗口的形状规则。
 *
 * 这些数字在真机上是**看不见对错**的：窗口形状不对只会表现为「窗口有点怪」，
 * 而没有任何东西会报错；`NaN` 更是要到系统侧抛异常时才发现。
 */
class PlayerPipRulesTest {

    @Test
    fun `有效尺寸就按片源的真实宽高比算`() {
        val video = MspVideoSize(widthPx = 1920, heightPx = 1080)
        assertEquals(16f / 9f, PlayerPipRules.aspectRatio(video), 1e-4f)
    }

    @Test
    fun `尺寸还没解析出来时用十六比九兜底`() {
        // 未知尺寸如果直接用 MspVideoSize.aspectRatio，拿到的是 1（正方形）。
        // 那会在启动的一瞬间画出一个四周黑边的方窗口。
        assertEquals(
            PlayerPipRules.DEFAULT_ASPECT_RATIO,
            PlayerPipRules.aspectRatio(MspVideoSize.Unknown),
            1e-4f,
        )
    }

    @Test
    fun `畸形像素宽高比也走兜底`() {
        val broken = MspVideoSize(widthPx = 1920, heightPx = 1080, pixelWidthHeightRatio = Float.NaN)
        assertEquals(PlayerPipRules.DEFAULT_ASPECT_RATIO, PlayerPipRules.aspectRatio(broken), 1e-4f)
    }

    @Test
    fun `宽画幅按显示宽高比算`() {
        // 21:9 超宽片源在 2.39 的上限之内，应该原样保留。
        val ultraWide = MspVideoSize(widthPx = 2560, heightPx = 1080)
        assertEquals(2560f / 1080f, PlayerPipRules.aspectRatio(ultraWide), 1e-4f)
    }

    @Test
    fun `宽到离谱的片源被夹到上限`() {
        val insane = MspVideoSize(widthPx = 10_000, heightPx = 100)
        assertEquals(PlayerPipRules.MAX_ASPECT_RATIO, PlayerPipRules.aspectRatio(insane), 1e-4f)
    }

    @Test
    fun `竖屏视频保留竖着的窗口`() {
        val portrait = MspVideoSize(widthPx = 1080, heightPx = 1920)
        assertEquals(1080f / 1920f, PlayerPipRules.aspectRatio(portrait), 1e-4f)
    }

    @Test
    fun `窄到离谱的片源被夹到下限`() {
        val insane = MspVideoSize(widthPx = 100, heightPx = 10_000)
        assertEquals(PlayerPipRules.MIN_ASPECT_RATIO, PlayerPipRules.aspectRatio(insane), 1e-4f)
    }

    @Test
    fun `变形像素的 DVD 片源按显示宽高比算`() {
        // 720×576 的 PAL DVD 是变形存储：像素宽高比 64/45，显示出来是 16:9。
        // 少乘这个系数就会得到一个偏方的窗口，而画面上完全看不出「少了什么」。
        val dvd = MspVideoSize(widthPx = 720, heightPx = 576, pixelWidthHeightRatio = 64f / 45f)
        assertEquals(16f / 9f, PlayerPipRules.aspectRatio(dvd), 1e-3f)
    }

    @Test
    fun `宽高比是 NaN 时走兜底`() {
        assertEquals(PlayerPipRules.DEFAULT_ASPECT_RATIO, PlayerPipRules.clampAspectRatio(Float.NaN), 1e-4f)
    }

    @Test
    fun `宽高比是无穷时走兜底`() {
        assertEquals(
            PlayerPipRules.DEFAULT_ASPECT_RATIO,
            PlayerPipRules.clampAspectRatio(Float.POSITIVE_INFINITY),
            1e-4f,
        )
        assertEquals(
            PlayerPipRules.DEFAULT_ASPECT_RATIO,
            PlayerPipRules.clampAspectRatio(Float.NEGATIVE_INFINITY),
            1e-4f,
        )
    }

    @Test
    fun `宽高比是零或负数时走兜底`() {
        assertEquals(PlayerPipRules.DEFAULT_ASPECT_RATIO, PlayerPipRules.clampAspectRatio(0f), 1e-4f)
        assertEquals(PlayerPipRules.DEFAULT_ASPECT_RATIO, PlayerPipRules.clampAspectRatio(-1.5f), 1e-4f)
    }

    @Test
    fun `正好落在上下限上的值不被改动`() {
        assertEquals(PlayerPipRules.MAX_ASPECT_RATIO, PlayerPipRules.clampAspectRatio(PlayerPipRules.MAX_ASPECT_RATIO), 1e-6f)
        assertEquals(PlayerPipRules.MIN_ASPECT_RATIO, PlayerPipRules.clampAspectRatio(PlayerPipRules.MIN_ASPECT_RATIO), 1e-6f)
    }

    @Test
    fun `上下限之内的小数比例原样保留`() {
        assertEquals(4f / 3f, PlayerPipRules.clampAspectRatio(4f / 3f), 1e-4f)
    }

    @Test
    fun `只有视频才给画中画入口`() {
        assertTrue(PlayerPipRules.shouldOfferEntry(MediaKind.VIDEO, supported = true))
        assertFalse(PlayerPipRules.shouldOfferEntry(MediaKind.AUDIO, supported = true))
    }

    @Test
    fun `设备不支持时连视频也不给入口`() {
        assertFalse(PlayerPipRules.shouldOfferEntry(MediaKind.VIDEO, supported = false))
    }

    @Test
    fun `还没取到当前条目时不给入口`() {
        assertFalse(PlayerPipRules.shouldOfferEntry(null, supported = true))
    }
}
