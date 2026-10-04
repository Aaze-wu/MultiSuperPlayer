package com.multisuperplayer.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义强调色的推导测试。
 *
 * 这里的断言全部是围绕「滑块能拖出来的每一个值」的：用户能拖到的地方，配色就不能是
 * 一团糊或者干脆看不见。三件事分开管：
 * - **越界与非有限值**：手改过设置文件的用户不该看到崩溃或透明色；
 * - **色相是环**：370° 和 10° 必须是同一个颜色，否则滑块一拖过 360 会突然跳色；
 * - **深色主色自动反相**：这是「自定义颜色在深色基底上糊掉」唯一的一处防线。
 */
class CustomAccentColorsTest {

    @Test
    fun `明度被夹进滑块范围`() {
        // 拖不到 0.99 也拖不到负数，但设置文件可以手改。夹到边界而不是丢弃，
        // 用户看到的是「颜色停在最亮/最暗」，而不是「我的设置莫名其妙没了」。
        assertEquals(
            customAccentColors(120f, 0.5f, CustomAccentRanges.LIGHTNESS_MAX),
            customAccentColors(120f, 0.5f, 0.99f),
        )
        assertEquals(
            customAccentColors(120f, 0.5f, CustomAccentRanges.LIGHTNESS_MIN),
            customAccentColors(120f, 0.5f, -0.5f),
        )
    }

    @Test
    fun `饱和度被夹进自然范围`() {
        assertEquals(
            customAccentColors(120f, 1f, 0.5f),
            customAccentColors(120f, 4f, 0.5f),
        )
        assertEquals(
            customAccentColors(120f, 0f, 0.5f),
            customAccentColors(120f, -3f, 0.5f),
        )
    }

    @Test
    fun `色相是环`() {
        // 370° 就是 10°。不是环的话，用户把色相滑块从 359 拖到 360 会瞬间从红跳到青。
        assertEquals(
            customAccentColors(10f, 0.5f, 0.5f),
            customAccentColors(370f, 0.5f, 0.5f),
        )
        assertEquals(
            customAccentColors(350f, 0.5f, 0.5f),
            customAccentColors(-10f, 0.5f, 0.5f),
        )
    }

    @Test
    fun `非有限的色相回落到默认色相`() {
        // NaN 和任何数比较都是 false，夹取也夹不住它；放进去会得到算不出来的颜色。
        val fallback = customAccentColors(CustomAccentRanges.DEFAULT_HUE, 0.5f, 0.5f)
        assertEquals(fallback, customAccentColors(Float.NaN, 0.5f, 0.5f))
        assertEquals(fallback, customAccentColors(Float.POSITIVE_INFINITY, 0.5f, 0.5f))
    }

    @Test
    fun `饱和度为 0 时浅色主色是纯灰`() {
        // 这是「用户把饱和度拖到底」的合法状态，不是错误：结果必须是灰，而不是某个带色调的色。
        val grey = customAccentColors(120f, 0f, 0.5f).lightPrimary
        assertEquals(grey.red, grey.green, 1e-4f)
        assertEquals(grey.green, grey.blue, 1e-4f)
    }

    @Test
    fun `四个角色互不相同`() {
        // 防的是复制粘贴写错行（例如容器色直接用了主色）：那不会崩，只是界面上
        // 「主色」和「容器色」变成同一个颜色，卡片和正文糊在一起，肉眼很难归因到这里。
        val colors = customAccentColors(210f, 0.55f, 0.45f)

        val roles = listOf(
            "lightPrimary" to colors.lightPrimary,
            "lightContainer" to colors.lightContainer,
            "darkPrimary" to colors.darkPrimary,
            "darkContainer" to colors.darkContainer,
        )
        roles.forEach { (nameA, colorA) ->
            roles.forEach { (nameB, colorB) ->
                if (nameA != nameB) {
                    assertNotEquals("$nameA 与 $nameB 撞色了", colorA, colorB)
                }
            }
        }
    }

    @Test
    fun `浅色容器始终比深色容器亮`() {
        // 明度滑块拖到哪都不影响这一条：容器色固定 0.90 / 0.30，
        // 否则低明度时浅色容器会变成一块深色，和白底直接打架。
        for (lightness in listOf(0.10f, 0.45f, 0.80f)) {
            val colors = customAccentColors(210f, 0.55f, lightness)
            assertTrue(
                "明度 $lightness 时浅色容器没有比深色容器亮",
                colors.lightContainer.luminance() > colors.darkContainer.luminance(),
            )
        }
    }

    @Test
    fun `明度低时深色主色会自动变亮`() {
        // 这是整个推导里唯一一处「对抗」用户输入的地方，也是自定义颜色最容易翻车的地方：
        // 用户在白底上挑了一个深色，而深色基底上的强调色要的是**亮**的那个。
        // 不反相的话，深色模式下按钮和背景糊成一片，用户只会以为「深色模式坏了」。
        // 蓝色是这里最难的一档（同明度下它的相对亮度最低），所以拿它当代表。
        for (hue in listOf(210f, 240f, 0f)) {
            for (lightness in listOf(0.10f, 0.20f, 0.40f)) {
                val colors = customAccentColors(hue, 0.8f, lightness)
                assertTrue(
                    "色相 $hue 明度 $lightness 时深色主色没有比浅色主色亮",
                    colors.darkPrimary.luminance() > colors.lightPrimary.luminance(),
                )
            }
        }
    }

    @Test
    fun `深色主色在任何输入下都亮到能看见`() {
        // 上限 0.92 / 下限 0.60 的窗口就是为这一条存在的：明度拖到 1 时
        // 深色主色若按「取反」算会得到接近纯黑，那在深色基底上等于消失。
        // 蓝色是亮度最低的色相，所以用它的下限做门槛（实测约 0.10）。
        for (hue in listOf(0f, 120f, 210f, 240f, 300f)) {
            for (lightness in listOf(0.10f, 0.45f, 0.80f)) {
                val colors = customAccentColors(hue, 0.8f, lightness)
                assertTrue(
                    "色相 $hue 明度 $lightness 时深色主色太暗",
                    colors.darkPrimary.luminance() > 0.08f,
                )
            }
        }
    }
}
