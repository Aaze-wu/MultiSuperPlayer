package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频页「歌词能拿到多少空间」的规则测试。
 *
 * 这些规则有一个共同点：**错了不会报错**。封面多占一点只是把歌词挤掉两行，左栏门槛
 * 算低一点只是按钮戳出屏幕边缘——而这两件事在高屏的模拟器上很难看出来。所以这里钉的
 * 是不变量，不是具体数字：
 *
 * 1. 封面 + 空隙 + 歌词**永远等于**舞台高度（既不能凭空多出空间，也不能算丢一块）；
 * 2. 歌词只会被让到 [AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT]，除非舞台本身就比它还矮；
 * 3. 说「搬得动」的时候，左栏一定放得下那一排按钮——拿 `TransportLayoutRules` 当场
 *    算一遍，而不是相信文件里的常量（常量抄错了，两处都会一起错）。
 */
class AudioLyricsSpaceRulesTest {

    // -------------------------------------------------------- 竖屏：封面让位

    @Test
    fun `空间充足时封面用上限`() {
        // 常见手机竖屏：舞台（屏高减掉标题/进度条/控制条）大约 400~500dp。
        assertEquals(
            AudioLyricsSpaceRules.PORTRAIT_ARTWORK_MAX,
            AudioLyricsSpaceRules.portraitArtworkSize(500.dp),
        )
        assertEquals(
            AudioLyricsSpaceRules.PORTRAIT_ARTWORK_MAX,
            AudioLyricsSpaceRules.portraitArtworkSize(400.dp),
        )
        // 刚好够的那一档：上限 + 空隙 + 保底 = 324dp，从这里往上都不再缩。
        assertEquals(
            AudioLyricsSpaceRules.PORTRAIT_ARTWORK_MAX,
            AudioLyricsSpaceRules.portraitArtworkSize(324.dp),
        )
    }

    @Test
    fun `空间不足时封面缩到刚好保住歌词`() {
        assertEquals(88.dp, AudioLyricsSpaceRules.portraitArtworkSize(300.dp))
        assertEquals(
            AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT,
            AudioLyricsSpaceRules.portraitLyricsHeight(300.dp),
        )

        // 再矮一点，封面继续缩，歌词仍然守在保底上。
        assertEquals(56.dp, AudioLyricsSpaceRules.portraitArtworkSize(268.dp))
        assertEquals(
            AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT,
            AudioLyricsSpaceRules.portraitLyricsHeight(268.dp),
        )
    }

    @Test
    fun `封面太小时干脆不画把空间还给歌词`() {
        // 243dp 按公式会给 31dp：一个认不出来的圆点，却照旧吃掉空隙和它自己的高度。
        assertEquals(0.dp, AudioLyricsSpaceRules.portraitArtworkSize(243.dp))
        assertEquals(243.dp, AudioLyricsSpaceRules.portraitLyricsHeight(243.dp))

        // 刚好到下限就画，而且要画就画在保底之上（不能出现「画了封面歌词反而更多」）。
        assertEquals(
            AudioLyricsSpaceRules.PORTRAIT_ARTWORK_MIN,
            AudioLyricsSpaceRules.portraitArtworkSize(244.dp),
        )
        assertEquals(
            AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT,
            AudioLyricsSpaceRules.portraitLyricsHeight(244.dp),
        )
    }

    @Test
    fun `封面为零时空隙一起消失`() {
        // 空隙算不算，看的是「封面画了没有」，不是「公式算出来多大」。
        assertEquals(200.dp, AudioLyricsSpaceRules.portraitLyricsHeight(200.dp))
        assertEquals(0.dp, AudioLyricsSpaceRules.portraitArtworkSize(200.dp))
    }

    @Test
    fun `封面加空隙加歌词永远等于舞台高度`() {
        var stage = 0.dp
        while (stage <= 600.dp) {
            val artwork = AudioLyricsSpaceRules.portraitArtworkSize(stage)
            val gap = if (artwork > 0.dp) AudioLyricsSpaceRules.ARTWORK_GAP else 0.dp
            val lyrics = AudioLyricsSpaceRules.portraitLyricsHeight(stage)

            assertEquals(
                "舞台高 ${stage.value}dp 时算丢了空间",
                stage.value,
                (artwork + gap + lyrics).value,
                0.01f,
            )
            assertTrue("舞台高 ${stage.value}dp 时封面是负数", artwork >= 0.dp)
            assertTrue("舞台高 ${stage.value}dp 时歌词是负数", lyrics >= 0.dp)

            // 保底：舞台本身比保底还矮时，歌词当然只能等于舞台高。
            val floor = if (stage < AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT) {
                stage
            } else {
                AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT
            }
            assertTrue(
                "舞台高 ${stage.value}dp 时歌词只剩 ${lyrics.value}dp，低于保底 ${floor.value}dp",
                lyrics >= floor - 0.01.dp,
            )

            stage += 4.dp
        }
    }

    @Test
    fun `退化输入不返回负数`() {
        // 负的 `Modifier.size()` 会直接崩，而「还没量出来」的那一帧就是 0。
        assertEquals(0.dp, AudioLyricsSpaceRules.portraitArtworkSize(0.dp))
        assertEquals(0.dp, AudioLyricsSpaceRules.portraitArtworkSize(-50.dp))
        assertEquals(0.dp, AudioLyricsSpaceRules.portraitLyricsHeight(0.dp))
        assertEquals(0.dp, AudioLyricsSpaceRules.portraitLyricsHeight(-50.dp))
    }

    @Test
    fun `高度未知时按上限处理`() {
        // 无界约束（被放进了可滚动容器）不该把它算成「空间无限」或「没有空间」。
        assertEquals(
            AudioLyricsSpaceRules.PORTRAIT_ARTWORK_MAX,
            AudioLyricsSpaceRules.portraitArtworkSize(Dp.Infinity),
        )
        assertTrue(AudioLyricsSpaceRules.portraitLyricsHeight(Dp.Infinity) > 0.dp)
    }

    // ------------------------------------------------------------ 歌词视口紧不紧

    @Test
    fun `视口紧到阈值以下就收内边距`() {
        assertTrue(AudioLyricsSpaceRules.isLyricsViewportTight(200.dp))
        assertTrue(AudioLyricsSpaceRules.isLyricsViewportTight(259.dp))
        // 阈值本身不算紧：边界取闭区间会在「刚好合适」的那一档也把边距收掉。
        assertFalse(AudioLyricsSpaceRules.isLyricsViewportTight(260.dp))
        assertFalse(AudioLyricsSpaceRules.isLyricsViewportTight(400.dp))
    }

    @Test
    fun `保底高度必须落在紧凑档里`() {
        // 两个常量是分别取的。保底一旦高过阈值，「让到保底」的那一档就成了最挤的一档，
        // 而它偏偏还不收内边距——上下各 24dp 白吃掉一行歌词。
        assertTrue(
            "保底 ${AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT.value}dp 高过阈值 " +
                "${AudioLyricsSpaceRules.LYRICS_TIGHT_THRESHOLD.value}dp",
            AudioLyricsSpaceRules.MIN_LYRICS_HEIGHT < AudioLyricsSpaceRules.LYRICS_TIGHT_THRESHOLD,
        )
    }

    @Test
    fun `视口高度未知时不算紧`() {
        // 「不算紧」= 保持原样。第一帧量到 0 时先按正常间距画，比先按紧凑画再跳回来好。
        assertFalse(AudioLyricsSpaceRules.isLyricsViewportTight(0.dp))
        assertFalse(AudioLyricsSpaceRules.isLyricsViewportTight(-1.dp))
        assertFalse(AudioLyricsSpaceRules.isLyricsViewportTight(Dp.Infinity))
    }

    // ------------------------------------------------------------ 横屏：控件放哪一栏

    /** 411dp 宽的竖屏手机横过来：914×411dp。 */
    private val phoneLandscape = 914.dp

    @Test
    fun `常见手机横屏把控件搬到左栏`() {
        val placement = AudioLyricsSpaceRules.landscapeControlsPlacement(phoneLandscape)

        assertTrue("914dp 宽的横屏应该搬得动控件", placement is LandscapeControlsPlacement.InLeftColumn)
        val weight = (placement as LandscapeControlsPlacement.InLeftColumn).leftWeight
        assertTrue(
            "左栏要放得下那两排控件",
            phoneLandscape * weight >= AudioLyricsSpaceRules.LEFT_COLUMN_MIN_WIDTH - 0.5.dp,
        )
        assertTrue("左栏不能超过屏宽的 45%", weight <= AudioLyricsSpaceRules.LEFT_WEIGHT_MAX)
        assertTrue("歌词那一栏必须拿到大部分宽度", weight < 0.5f)
    }

    @Test
    fun `窄窗口退回原版面`() {
        // 左栏至少要 320dp 才装得下那两排，而它最多只能占 45%：分界线在 711dp 附近。
        assertTrue(AudioLyricsSpaceRules.landscapeControlsPlacement(700.dp) is LandscapeControlsPlacement.UnderLyrics)
        assertTrue(AudioLyricsSpaceRules.landscapeControlsPlacement(600.dp) is LandscapeControlsPlacement.UnderLyrics)
        assertTrue(
            "屏宽只有左栏最小宽度时当然搬不动",
            AudioLyricsSpaceRules.landscapeControlsPlacement(AudioLyricsSpaceRules.LEFT_COLUMN_MIN_WIDTH) is
                LandscapeControlsPlacement.UnderLyrics,
        )
    }

    @Test
    fun `屏再宽左栏也不加宽`() {
        // 左栏只需要装下那一排按钮，多出来的宽度全归歌词。反过来（让左栏按比例长大）
        // 会出现「平板上的歌词比手机上还窄」。
        for (width in listOf(phoneLandscape, 1280.dp, 1600.dp)) {
            val placement = AudioLyricsSpaceRules.landscapeControlsPlacement(width)
            assertTrue("屏宽 ${width.value}dp 应该搬得动", placement is LandscapeControlsPlacement.InLeftColumn)
            val left = width * (placement as LandscapeControlsPlacement.InLeftColumn).leftWeight
            assertEquals(
                "屏宽 ${width.value}dp 时左栏宽变了",
                AudioLyricsSpaceRules.LEFT_COLUMN_MIN_WIDTH.value,
                left.value,
                0.5f,
            )
        }
    }

    @Test
    fun `一旦能搬更宽就一定也能搬`() {
        // 判据必须单调，否则用户拉一下窗口就会看到控件在两栏之间来回跳。
        var width = 300.dp
        var widened = false
        while (width <= 1600.dp) {
            val inLeftColumn =
                AudioLyricsSpaceRules.landscapeControlsPlacement(width) is LandscapeControlsPlacement.InLeftColumn
            if (widened) {
                assertTrue("屏宽变到 ${width.value}dp 之后反而不搬了", inLeftColumn)
            }
            widened = widened || inLeftColumn
            width += 10.dp
        }
        assertTrue("宽到 1600dp 还没搬动过，门槛一定算错了", widened)
    }

    @Test
    fun `宽度未知时先按能搬处理`() {
        // 第一帧量到 0：先按宽屏画，比先按窄窗口画一遍再跳过去好。
        assertTrue(
            AudioLyricsSpaceRules.landscapeControlsPlacement(0.dp) is LandscapeControlsPlacement.InLeftColumn,
        )
        assertTrue(
            AudioLyricsSpaceRules.landscapeControlsPlacement(-1.dp) is LandscapeControlsPlacement.InLeftColumn,
        )
    }

    @Test
    fun `左栏最小宽度真的放得下那一排按钮`() {
        // 5 个侧位 = `PlayerTransportControls(fullscreen = null)` 的那一排：
        // 随机 / 上一首 / 下一首 / 循环 / 字幕。多数一个会把门槛抬高 48dp，
        // 少算一个就是按钮真的戳出屏幕。
        val layout = TransportLayoutRules.layoutFor(
            availableWidth = AudioLyricsSpaceRules.LEFT_COLUMN_CONTENT_MIN,
            sideSlots = 5,
            compact = true,
        )

        assertTrue(
            "播放键缩到比侧按钮还小就不成其为主操作了",
            layout.playButtonSize >= TransportLayoutRules.BUTTON_MIN,
        )
        assertTrue(
            "在左栏最小宽度上装不下：48 × 5 + 播放键 + 两侧内边距 > " +
                "${AudioLyricsSpaceRules.LEFT_COLUMN_CONTENT_MIN.value}dp",
            TransportLayoutRules.BUTTON_MIN * 5 + layout.playButtonSize + layout.horizontalPadding * 2 <=
                AudioLyricsSpaceRules.LEFT_COLUMN_CONTENT_MIN,
        )
        // 这一档上内边距已经被让光了（先让装饰、再让播放键的顺序）。
        assertEquals(0.dp, layout.horizontalPadding)
    }
}
