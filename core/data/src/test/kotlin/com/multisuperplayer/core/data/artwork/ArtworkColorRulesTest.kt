package com.multisuperplayer.core.data.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面取色的推导规则。
 *
 * 这一类计算的失败方式很安静：不会抛异常，只会算出一个**看起来是对的**
 * 颜色。所以断言全部落在可验证的性质上（明度关系、色相保留、不透明度、
 * 灰色必须放弃），而不是把某个整数结果抄进测试——抄下来的数字只能证明
 * 「和上次一样」，不能证明「是对的」。
 */
class ArtworkColorRulesTest {

    // 纯红 hsL(0°, 100%, 50%)：色相在 0 度，不会碰到环绕问题。
    private val red = 0xFFFF0000.toInt()

    // 青色 hue ≈ 180°，用来验证大色相不会被算成别的颜色。
    private val cyan = 0xFF00CCCC.toInt()

    @Test
    fun `饱和的种子色能推导出四色`() {
        val colors = ArtworkColorRules.colorsFor(red)
        assertNotNull(colors)
    }

    @Test
    fun `灰色封面取不出颜色`() {
        // 黑白封面、纯灰 logo——色相没有意义。硬抬饱和度等于给用户凭空
        // 造一个色相，界面会变成「这张黑白专辑的主题色是红的」。
        assertNull(ArtworkColorRules.colorsFor(0xFF808080.toInt()))
        assertNull(ArtworkColorRules.colorsFor(0xFF000000.toInt()))
        assertNull(ArtworkColorRules.colorsFor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `饱和度低于阈值就放弃`() {
        // 0xFF8C7E7E：s ≈ 0.07，比阈值低；0xFF8C6A6A：s ≈ 0.19，比阈值高。
        assertNull(ArtworkColorRules.colorsFor(0xFF8C7E7E.toInt()))
        assertNotNull(ArtworkColorRules.colorsFor(0xFF8C6A6A.toInt()))
    }

    @Test
    fun `几乎全黑或全白的封面也放弃`() {
        // JPEG 噪声可以把一张纯黑封面的饱和度撑过阈值，此时色相是随机的，
        // 推出来的主题色和封面毫无关系。
        assertNull(ArtworkColorRules.colorsFor(0xFF020203.toInt()))
        assertNull(ArtworkColorRules.colorsFor(0xFFFDFDFC.toInt()))
    }

    @Test
    fun `浅色模式容器色比主色亮`() {
        val colors = ArtworkColorRules.colorsFor(red)!!
        val primary = ArtworkColorRules.toHsl(colors.lightPrimary)[2]
        val container = ArtworkColorRules.toHsl(colors.lightContainer)[2]

        // 容器色是铺在浅色背景上的大面积色，主色是要压白字的小面积色。
        // 顺序反了的话按钮会变成一块浅色板子上看不清的字。
        assertTrue("container($container) 应当比 primary($primary) 亮", container > primary)
    }

    @Test
    fun `深色模式主色比容器色亮`() {
        val colors = ArtworkColorRules.colorsFor(red)!!
        val primary = ArtworkColorRules.toHsl(colors.darkPrimary)[2]
        val container = ArtworkColorRules.toHsl(colors.darkContainer)[2]

        assertTrue("primary($primary) 应当比 container($container) 亮", primary > container)
    }

    @Test
    fun `浅色模式主色足够暗以承载白字`() {
        listOf(red, cyan, 0xFFFFD700.toInt(), 0xFF7A00FF.toInt()).forEach { seed ->
            val colors = ArtworkColorRules.colorsFor(seed)!!
            val lightness = ArtworkColorRules.toHsl(colors.lightPrimary)[2]
            assertTrue("seed=$seed 推出的主色明度 $lightness 太高", lightness <= 0.45f)
        }
    }

    @Test
    fun `色相被保留`() {
        // 青色（180°）绝不能被算成红色（0°）——那说明 HSL 往返有符号或
        // 分支错误，但结果仍然是一个「合法的颜色」，单看数值发现不了。
        val colors = ArtworkColorRules.colorsFor(cyan)!!
        val lightHue = ArtworkColorRules.toHsl(colors.lightPrimary)[0]
        val darkHue = ArtworkColorRules.toHsl(colors.darkPrimary)[0]

        assertTrue("lightHue=$lightHue 应当接近 180", kotlin.math.abs(lightHue - 180f) < 12f)
        assertTrue("darkHue=$darkHue 应当接近 180", kotlin.math.abs(darkHue - 180f) < 12f)
    }

    @Test
    fun `HSV 往返在灰阶上不产生 NaN`() {
        // max == min 时 delta 为 0，标准实现里每一次除法都会得到 NaN，
        // 然后 NaN 一路穿过 coerceIn 变成 0，最后打印出一个黑色。
        // 这条测试盯的是「不崩也不假」，不是「值好看」。
        val grey = ArtworkColorRules.toHsl(0xFF808080.toInt())
        assertEquals(0f, grey[0], 0.0001f)
        assertEquals(0f, grey[1], 0.0001f)
        assertTrue(grey[2].isFinite())
    }

    @Test
    fun `推导出的颜色全部不透明`() {
        // Palette 给的 swatch rgb 是不含 alpha 的（高 8 位为 0）。如果这里
        // 原样透传，Compose 会把它当成**完全透明**的颜色，界面上表现为
        // 「按钮消失了」——而不是报错。
        listOf(red, cyan).forEach { seed ->
            val colors = ArtworkColorRules.colorsFor(seed)!!
            listOf(
                colors.lightPrimary,
                colors.lightContainer,
                colors.darkPrimary,
                colors.darkContainer,
            ).forEach { argb ->
                assertEquals("$seed -> ${argb.toUInt().toString(16)}", 0xFF, (argb ushr 24) and 0xFF)
            }
        }
    }

    @Test
    fun `色环环绕处不越界`() {
        // 0xFFFF0080 h≈330°，0xFF80FF00 h≈90°：一个在环绕点左边、一个在右边。
        // hueToChannel 里那三次 ±1/3 的加减只要漏一次，通道值就会跑出 0..255。
        listOf(0xFFFF0080.toInt(), 0xFF80FF00.toInt(), 0xFF00FF80.toInt(), 0xFFFF8000.toInt())
            .forEach { seed ->
                val colors = ArtworkColorRules.colorsFor(seed)!!
                listOf(colors.lightPrimary, colors.darkPrimary).forEach { argb ->
                    listOf(16, 8, 0).forEach { shift ->
                        val channel = (argb shr shift) and 0xFF
                        assertTrue("seed=$seed 通道值 $channel 越界", channel in 0..255)
                    }
                }
            }
    }
}
