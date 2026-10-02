package com.multisuperplayer.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 配色推导测试。
 *
 * 为什么值得测：这套配色有 40+ 个角色，而「漏掉一个角色」的表现不是崩溃，
 * 而是那个角色继续保留 Material 3 的**基线值**——基线的品牌色是紫。
 * 于是「换了强调色，可底部导航栏选中那一下还是紫的」这类问题只能靠肉眼逐屏看，
 * 而这里一次就能把每个角色都扫一遍。
 *
 * 三条断言各管一件事：
 * - 每个角色都必须跟着强调色变（写死的角色会被抓出来）；
 * - 每个角色都不许等于 M3 基线值（「忘了赋值」会被抓出来）；
 * - 前景 / 底色要有对比度（看得出但看不清也算坏配色）。
 */
class MspColorSchemeTest {

    /**
     * 强调色的「纯色」角色：由种子推导，与基底无关，纯黑基底下也必须原样保留。
     *
     * **加角色时这里也要加**——这个名单就是「哪些角色归强调色管」的定义，
     * 少写一个，那个角色漏赋值就不会被发现。
     */
    private val accentColorRoles: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "primary" to { it.primary },
        "primaryContainer" to { it.primaryContainer },
        "inversePrimary" to { it.inversePrimary },
        "primaryFixed" to { it.primaryFixed },
        "primaryFixedDim" to { it.primaryFixedDim },
        "secondary" to { it.secondary },
        "secondaryContainer" to { it.secondaryContainer },
        "secondaryFixed" to { it.secondaryFixed },
        "secondaryFixedDim" to { it.secondaryFixedDim },
        "tertiary" to { it.tertiary },
        "tertiaryContainer" to { it.tertiaryContainer },
        "tertiaryFixed" to { it.tertiaryFixed },
        "tertiaryFixedDim" to { it.tertiaryFixedDim },
    )

    /** 同样归强调色管，但纯黑基底下有自己规则的唯一一个。 */
    private val surfaceTintRole: Pair<String, (ColorScheme) -> Color> =
        "surfaceTint" to { it.surfaceTint }

    private val allAccentRoles = accentColorRoles + surfaceTintRole

    /** 前景色 / 底色对。[minRatio] 是该角色实际用途对应的最低对比度。 */
    private data class ContentPair(
        val name: String,
        val background: (ColorScheme) -> Color,
        val foreground: (ColorScheme) -> Color,
        val minRatio: Float = 4.5f,
    )

    private val contentPairs = listOf(
        ContentPair("primary", { it.primary }, { it.onPrimary }),
        ContentPair("primaryContainer", { it.primaryContainer }, { it.onPrimaryContainer }),
        ContentPair("primaryFixed", { it.primaryFixed }, { it.onPrimaryFixed }),
        ContentPair("secondary", { it.secondary }, { it.onSecondary }),
        ContentPair("secondaryContainer", { it.secondaryContainer }, { it.onSecondaryContainer }),
        ContentPair("secondaryFixed", { it.secondaryFixed }, { it.onSecondaryFixed }),
        ContentPair("tertiary", { it.tertiary }, { it.onTertiary }),
        ContentPair("tertiaryContainer", { it.tertiaryContainer }, { it.onTertiaryContainer }),
        ContentPair("tertiaryFixed", { it.tertiaryFixed }, { it.onTertiaryFixed }),
        // 「Variant」系列是强调色调的前景，只用在固定容器上的强调文字，
        // 门槛按 WCAG 的「大字号 / 非文字」那档（3.0）算。
        ContentPair(
            "primaryFixedVariant",
            { it.primaryFixed },
            { it.onPrimaryFixedVariant },
            minRatio = 3f,
        ),
        ContentPair(
            "secondaryFixedVariant",
            { it.secondaryFixed },
            { it.onSecondaryFixedVariant },
            minRatio = 3f,
        ),
        ContentPair(
            "tertiaryFixedVariant",
            { it.tertiaryFixed },
            { it.onTertiaryFixedVariant },
            minRatio = 3f,
        ),
    )

    /** 中性角色：不许带任何色偏，否则整页看起来「灰得发紫」。 */
    private val neutralRoles: List<Pair<String, (ColorScheme) -> Color>> = listOf(
        "background" to { it.background },
        "onBackground" to { it.onBackground },
        "surface" to { it.surface },
        "onSurface" to { it.onSurface },
        "surfaceVariant" to { it.surfaceVariant },
        "onSurfaceVariant" to { it.onSurfaceVariant },
        "surfaceContainerLowest" to { it.surfaceContainerLowest },
        "surfaceContainerLow" to { it.surfaceContainerLow },
        "surfaceContainer" to { it.surfaceContainer },
        "surfaceContainerHigh" to { it.surfaceContainerHigh },
        "surfaceContainerHighest" to { it.surfaceContainerHighest },
        "surfaceDim" to { it.surfaceDim },
        "surfaceBright" to { it.surfaceBright },
        "outline" to { it.outline },
        "outlineVariant" to { it.outlineVariant },
        "inverseSurface" to { it.inverseSurface },
        "inverseOnSurface" to { it.inverseOnSurface },
    )

    @Test
    fun `强调色族的每个角色都必须随强调色变化`() {
        // 两个不同的强调色给出的同一角色必须不同。这条能同时抓两种错：
        // 角色被写成常量（比如忘了赋值、留成基线紫），以及复制粘贴时
        // 某个角色被赋成了另一个角色的值。
        val accents = MspAccent.entries
        listOf(false, true).forEach { isDark ->
            for (i in accents.indices) {
                for (j in i + 1 until accents.size) {
                    val a = composeColorScheme(accents[i], MspBaseTheme.LIGHT, isDark)
                    val b = composeColorScheme(accents[j], MspBaseTheme.LIGHT, isDark)
                    allAccentRoles.forEach { (name, get) ->
                        assertNotEquals(
                            "$name 在「${accents[i].id}」和「${accents[j].id}」" +
                                "下相同（isDark=$isDark），说明它没跟随强调色",
                            get(a),
                            get(b),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `没有一个角色还停留在 M3 基线的紫色`() {
        // Material 3 基线那套颜色是以紫为种子调出来的，所以「忘了给某个角色赋值」
        // 的可见表现就是那个角色还是紫的（底部导航栏选中指示器、选中的 chip、
        // 只授权部分权限时的提示条都踩过这个坑）。这里把每个角色和基线比一遍。
        MspAccent.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = composeColorScheme(accent, MspBaseTheme.LIGHT, isDark)
                val baseline = if (isDark) darkColorScheme() else lightColorScheme()
                allAccentRoles.forEach { (name, get) ->
                    assertNotEquals(
                        "$name 还是 M3 基线的值（${accent.id}, isDark=$isDark），" +
                            "说明这个角色没有跟随强调色",
                        get(baseline),
                        get(scheme),
                    )
                }
            }
        }
    }

    @Test
    fun `前景色在对应底色上都有足够对比度`() {
        // 「看得出但看不清」也是一种坏配色，而且比漏赋值更难发现：
        // 颜色确实跟着强调色走了，只是浅底配浅字。
        //
        // 4.5 是 WCAG AA 正文的标准。它需要前景色是「白 / 近黑二选一里更清楚的那个」
        // 才稳：光靠亮度阈值挑候选，中等亮度的强调色会被配白字（实测 1.94）。
        MspAccent.entries.forEach { accent ->
            listOf(false, true).forEach { isDark ->
                val scheme = composeColorScheme(accent, MspBaseTheme.LIGHT, isDark)
                contentPairs.forEach { pair ->
                    val ratio = contrastRatio(pair.background(scheme), pair.foreground(scheme))
                    assertTrue(
                        "${pair.name} 的对比度 ${"%.2f".format(ratio)} 低于 ${pair.minRatio}" +
                            "（${accent.id}, isDark=$isDark）",
                        ratio >= pair.minRatio,
                    )
                }
            }
        }
    }

    @Test
    fun `中性角色不带任何色偏`() {
        // M3 基线里连 surface / outline 这类没有语义颜色的角色都带一点紫。
        // 去色偏之后三通道必须完全相等——留着一点色偏，用户看到的就是
        // 「换了强调色，可整页还是灰得发紫」。
        listOf(MspBaseTheme.LIGHT, MspBaseTheme.DARK, MspBaseTheme.BLACK).forEach { base ->
            val scheme = composeColorScheme(MspAccent.INDIGO, base, isDark = base.isDark)
            neutralRoles.forEach { (name, get) ->
                val color = get(scheme)
                val spread = maxOf(color.red, color.green, color.blue) -
                    minOf(color.red, color.green, color.blue)
                assertEquals("$name 在 ${base.id} 下仍有色偏", 0f, spread, 0.0001f)
            }
        }
    }

    @Test
    fun `纯黑基底只压黑中性角色`() {
        // 纯黑基底是为了「像素真的断电」，所以它只该动中性角色：
        // 强调色一旦被一起压黑，整屏就只剩黑白，品牌色全丢。
        val dark = composeColorScheme(MspAccent.INDIGO, MspBaseTheme.DARK, isDark = true)
        val black = composeColorScheme(MspAccent.INDIGO, MspBaseTheme.BLACK, isDark = true)

        accentColorRoles.forEach { (name, get) ->
            assertEquals("$name 在纯黑基底下被改动了", get(dark), get(black))
        }
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)

        // 抬升色调在纯黑基底下必须透明：混进任何颜色都破坏「像素断电」这个承诺。
        // （其余基底相反，它必须跟随强调色，所以单独断言。）
        assertEquals(Color.Transparent, black.surfaceTint)
        assertEquals(dark.primary, dark.surfaceTint)
    }

    /**
     * WCAG 相对亮度。
     *
     * 自己算一遍而不是调 [Color.luminance]：断言就只依赖 [Color] 的通道值，
     * 不依赖 Compose / Android 在单测环境里的任何实现细节。
     */
    private fun Color.relativeLuminance(): Float {
        fun linear(channel: Float): Double {
            val c = channel.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return (
            0.2126 * linear(red) +
                0.7152 * linear(green) +
                0.0722 * linear(blue)
            ).toFloat()
    }

    private fun contrastRatio(background: Color, foreground: Color): Float {
        val a = background.relativeLuminance()
        val b = foreground.relativeLuminance()
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }
}
