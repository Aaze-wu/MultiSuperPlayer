package com.multisuperplayer.feature.player

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放控制行的版面规则测试。
 *
 * 锁的是一条**真机上才会犯、而且不会报错**的规则：这一行七个按钮在 360dp 宽的机器上
 * 天然溢出 40dp，`Row` 会把这 40dp 全压在最后一个按钮上，于是「全屏」被压成一条
 * 缝、里面的图标缩成一个点——看上去就是一个空按钮里有个逗号。
 *
 * 所以这里的断言不是「数字对不对」，而是「**放不放得下**」：拿规则算出的结果重新
 * 拼一遍总宽度，再和可用宽度比。数字以后怎么调都行，这条不许破。
 */
class TransportLayoutRulesTest {

    /** 溢出事故的现场：1080×2400 @480dpi ⇒ **360dp** 宽。 */
    private val narrowPhone = 360.dp

    /** 模拟器（1080×2400 @420dpi ⇒ 411dp 宽）。这个宽度一直是对的，用来防回归。 */
    private val emulatorPhone = 411.dp

    /** 横屏可用的横向空间（2400px @480dpi）。 */
    private val landscape = 800.dp

    /** 竖屏摆 7 个按钮 = 1 个播放键 + 6 个侧按钮。 */
    private val portraitSlots = 6

    /** 横屏摆 6 个按钮 = 1 个播放键 + 5 个侧按钮（那两处没有全屏按钮）。 */
    private val landscapeSlots = 5

    /** 按规则算出的结果重新拼一遍，这一行真正要占的宽度。 */
    private fun widthOf(layout: TransportLayoutRules.Layout, sideSlots: Int): Dp =
        layout.playButtonSize +
            TransportLayoutRules.BUTTON_MIN * sideSlots +
            layout.horizontalPadding * 2

    // ------------------------------------------------------------------ 放得下

    @Test
    fun `360dp 的真机上七个按钮都放得下`() {
        val layout = TransportLayoutRules.layoutFor(narrowPhone, portraitSlots, compact = false)

        assertTrue(
            "这一行占 ${widthOf(layout, portraitSlots)}，可用只有 $narrowPhone：" +
                "多出来的部分会被 Row 全部压在最后一个按钮上",
            widthOf(layout, portraitSlots) <= narrowPhone,
        )
    }

    @Test
    fun `宽屏也放得下（模拟器 411dp 与横屏 800dp）`() {
        for (available in listOf(emulatorPhone, landscape, 1280.dp)) {
            val layout = TransportLayoutRules.layoutFor(available, portraitSlots, compact = false)
            assertTrue(
                "$available 下溢出（占用 ${widthOf(layout, portraitSlots)}）",
                widthOf(layout, portraitSlots) <= available,
            )
        }
    }

    @Test
    fun `横屏五个侧按钮更宽松`() {
        val layout = TransportLayoutRules.layoutFor(landscape, landscapeSlots, compact = true)

        assertTrue(widthOf(layout, landscapeSlots) <= landscape)
    }

    // -------------------------------------------------------------- 让步的顺序

    @Test
    fun `窄屏先让内边距，从内边距开始收`() {
        // 360dp 刚好是「按钮 352dp + 内边距 8dp」：内边距被压到 4dp，播放键不动。
        val layout = TransportLayoutRules.layoutFor(narrowPhone, portraitSlots, compact = false)

        assertEquals(TransportLayoutRules.PLAY_MAX, layout.playButtonSize)
        assertTrue("内边距应当收窄但没有归零", layout.horizontalPadding < TransportLayoutRules.PADDING_MAX)
        assertEquals(4.dp, layout.horizontalPadding)
    }

    @Test
    fun `内边距收到 0 之前播放键一个像素都不缩`() {
        // 352dp 是「内边距刚好归零」的临界点。
        val tight = TransportLayoutRules.layoutFor(352.dp, portraitSlots, compact = false)
        assertEquals(0.dp, tight.horizontalPadding)
        assertEquals(TransportLayoutRules.PLAY_MAX, tight.playButtonSize)

        // 比临界点宽一点：内边距开始回来，播放键仍然不动。
        val roomier = TransportLayoutRules.layoutFor(376.dp, portraitSlots, compact = false)
        assertEquals(12.dp, roomier.horizontalPadding)
        assertEquals(TransportLayoutRules.PLAY_MAX, roomier.playButtonSize)
    }

    @Test
    fun `内边距必须让完才轮到播放键`() {
        // 340dp：内边距已是 0，播放键只缩到 52dp（还没到 48dp 的下限）。
        val shrinking = TransportLayoutRules.layoutFor(340.dp, portraitSlots, compact = false)
        assertEquals("内边距先让完", 0.dp, shrinking.horizontalPadding)
        assertEquals("播放键这时候才开始缩", 52.dp, shrinking.playButtonSize)
        assertTrue(widthOf(shrinking, portraitSlots) <= 340.dp)

        // 336dp = 48 × 7，这一行的真下限：再窄就没有东西可让了。
        val floor = TransportLayoutRules.layoutFor(336.dp, portraitSlots, compact = false)
        assertEquals(0.dp, floor.horizontalPadding)
        assertEquals(TransportLayoutRules.BUTTON_MIN, floor.playButtonSize)
        assertEquals(336.dp, widthOf(floor, portraitSlots))
    }

    // ------------------------------------------------------------ 不许再往下缩

    @Test
    fun `任何宽度下可点区域都不小于 48dp`() {
        // 压可点区域比溢出更难发现：按钮看着在，手指点不中。
        for (available in listOf(narrowPhone, 360.dp, 352.dp, 340.dp, 320.dp, 240.dp, 0.dp, (-10).dp)) {
            for (compact in listOf(false, true)) {
                val layout = TransportLayoutRules.layoutFor(available, portraitSlots, compact = compact)
                assertTrue(
                    "$available / compact=$compact 把播放键缩到 ${layout.playButtonSize}，低于可点区域下限",
                    layout.playButtonSize >= TransportLayoutRules.BUTTON_MIN,
                )
                assertTrue("内边距不该是负数", layout.horizontalPadding >= 0.dp)
            }
        }
    }

    @Test
    fun `比硬下限还窄的窗口如实溢出，而不是假装放得下`() {
        // 分屏里只有 320dp 时就到这里了。规则**不**用 coerceAtLeast 把数字凑好看：
        // 那种「算出来放得下、实际还是溢出」的假象正是这个 bug 一开始藏了三周的原因。
        val layout = TransportLayoutRules.layoutFor(320.dp, portraitSlots, compact = false)

        assertEquals(TransportLayoutRules.BUTTON_MIN, layout.playButtonSize)
        assertTrue(
            "这里就该溢出，改坏了这条说明有人在凑数字",
            widthOf(layout, portraitSlots) > 320.dp,
        )
    }

    // -------------------------------------------------------------- 退化与边界

    @Test
    fun `退化输入不会算出负数`() {
        // 负的 Dp 交给 Modifier.size() 会直接崩。
        for (available in listOf(0.dp, (-1).dp, (-100).dp)) {
            val layout = TransportLayoutRules.layoutFor(available, portraitSlots, compact = false)
            assertTrue(layout.playButtonSize > 0.dp)
            assertTrue(layout.horizontalPadding >= 0.dp)
        }
    }

    @Test
    fun `无界约束按宽屏处理`() {
        // 放进横向滚动容器时 maxWidth 是 Infinity：不该算出 NaN 或负数。
        val layout = TransportLayoutRules.layoutFor(Dp.Infinity, portraitSlots, compact = false)

        assertEquals(TransportLayoutRules.PADDING_MAX, layout.horizontalPadding)
        assertEquals(TransportLayoutRules.PLAY_MAX, layout.playButtonSize)
    }

    @Test
    fun `宽屏保持原来的版面`() {
        // 这次改动不该动宽屏的观感：24dp 内边距 + 64dp 播放键。
        val layout = TransportLayoutRules.layoutFor(emulatorPhone, portraitSlots, compact = false)

        assertEquals(TransportLayoutRules.PADDING_MAX, layout.horizontalPadding)
        assertEquals(TransportLayoutRules.PLAY_MAX, layout.playButtonSize)
    }

    @Test
    fun `横屏播放键上限比竖屏小一档`() {
        val portrait = TransportLayoutRules.layoutFor(landscape, portraitSlots, compact = false)
        val compactLayout = TransportLayoutRules.layoutFor(landscape, landscapeSlots, compact = true)

        assertEquals(TransportLayoutRules.PLAY_MAX, portrait.playButtonSize)
        assertEquals(TransportLayoutRules.PLAY_MAX_COMPACT, compactLayout.playButtonSize)
        assertTrue(compactLayout.playButtonSize < portrait.playButtonSize)
    }
}
