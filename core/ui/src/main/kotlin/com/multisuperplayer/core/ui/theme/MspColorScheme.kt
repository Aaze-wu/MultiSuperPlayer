package com.multisuperplayer.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 主题基底。
 *
 * [BLACK] 不是 [DARK] 的换皮：AMOLED 屏上纯黑能让像素真正断电，
 * 省电且没有「灰蒙蒙的黑」；但它也让阴影和分割线失效，
 * 所以两套 surface 系列必须分别给值，不能共用。
 */
enum class MspBaseTheme(val id: String, val displayName: String) {
    FOLLOW_SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    BLACK("black", "纯黑（OLED）"),
    ;

    val isDark: Boolean get() = this != LIGHT

    companion object {
        /** 默认值的**唯一来源**：数据层只存 id，不认识这个值。 */
        val DEFAULT: MspBaseTheme = FOLLOW_SYSTEM

        /**
         * 按 id 解析。
         *
         * [id] 为空（用户从未设置过）或认不出来（降级安装、手改过配置文件）
         * 都回退到 [DEFAULT]——主题这种东西**绝不能因为一个字符串对不上就崩**，
         * 最差也就是回到默认主题。
         */
        fun fromId(id: String?): MspBaseTheme =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 强调色预设。
 *
 * 每种只声明**两个**「种子」（主色 + 主色容器），其余角色由
 * [composeColorScheme] 按对比度推导。
 *
 * 为什么不引 material-color-utilities 做完整的 HCT 调色板：
 * 那套算法的价值在于「从任意一张封面图生成和谐的整套配色」，
 * 属于「从封面取色」这条路；固定预设用不着它，而多引一个库
 * 就要为它承担版本冲突和 100KB 的体积。等做封面取色时再引。
 */
enum class MspAccent(
    val id: String,
    val displayName: String,
    val lightPrimary: Color,
    val lightContainer: Color,
    val darkPrimary: Color,
    val darkContainer: Color,
) {
    INDIGO("indigo", "靛蓝", Color(0xFF4A54C8), Color(0xFFE0E0FF), Color(0xFFB9C0FF), Color(0xFF303A8C)),
    VIOLET("violet", "紫罗兰", Color(0xFF6A3FCB), Color(0xFFE9DDFF), Color(0xFFCDBDFF), Color(0xFF4B2A96)),
    TEAL("teal", "青碧", Color(0xFF00695C), Color(0xFFA7F2E4), Color(0xFF64D8C4), Color(0xFF005044)),
    GREEN("green", "森绿", Color(0xFF2E6B2F), Color(0xFFB2F2AC), Color(0xFF97D78F), Color(0xFF17501A)),
    AMBER("amber", "琥珀", Color(0xFF8A5300), Color(0xFFFFDDB3), Color(0xFFFFB951), Color(0xFF663D00)),
    ROSE("rose", "绯红", Color(0xFFB3245B), Color(0xFFFFD9E2), Color(0xFFFFB0C8), Color(0xFF8E0F45)),
    ;

    companion object {
        val DEFAULT: MspAccent = INDIGO

        /** @see MspBaseTheme.Companion.fromId */
        fun fromId(id: String?): MspAccent =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * 生成完整配色方案。
 *
 * 只覆盖主色族（primary / secondary / tertiary）+ 纯黑基底需要的 surface 系列，
 * 其余角色交给 Material 3 的基线值：它们本来就是整套调好的中性灰，
 * 混进任何强调色都不会难看。
 *
 * @param oledBlack 是否把中性色压到纯黑。true 时只对**中性**角色生效，
 *   强调色不受影响（否则整屏只剩黑白，品牌色全丢）。
 */
fun composeColorScheme(
    accent: MspAccent,
    baseTheme: MspBaseTheme,
    isDark: Boolean,
): ColorScheme = composeColorSchemeFromSeeds(
    lightPrimary = accent.lightPrimary,
    lightContainer = accent.lightContainer,
    darkPrimary = accent.darkPrimary,
    darkContainer = accent.darkContainer,
    baseTheme = baseTheme,
    isDark = isDark,
)

/**
 * 同 [composeColorScheme]，但种子来自封面取色（[ArtworkAccent]）。
 *
 * 两条路都必须走同一个实现：配色规则（哪个角色压哪个角色、纯黑怎么处理）
 * 只应该有一份，否则「预设好看、封面取色难看」这种问题会永远修不干净。
 */
fun composeColorScheme(
    artwork: ArtworkAccent,
    baseTheme: MspBaseTheme,
    isDark: Boolean,
): ColorScheme = composeColorSchemeFromSeeds(
    lightPrimary = artwork.lightPrimary,
    lightContainer = artwork.lightContainer,
    darkPrimary = artwork.darkPrimary,
    darkContainer = artwork.darkContainer,
    baseTheme = baseTheme,
    isDark = isDark,
)

/**
 * 生成完整配色方案。
 *
 * 只覆盖主色族（primary / secondary / tertiary）+ 纯黑基底需要的 surface 系列，
 * 其余角色交给 Material 3 的基线值：它们本来就是整套调好的中性灰，
 * 混进任何强调色都不会难看。
 *
 * @param oledBlack 是否把中性色压到纯黑。true 时只对**中性**角色生效，
 *   强调色不受影响（否则整屏只剩黑白，品牌色全丢）。
 */
private fun composeColorSchemeFromSeeds(
    lightPrimary: Color,
    lightContainer: Color,
    darkPrimary: Color,
    darkContainer: Color,
    baseTheme: MspBaseTheme,
    isDark: Boolean,
): ColorScheme {
    val primary = if (isDark) darkPrimary else lightPrimary
    val container = if (isDark) darkContainer else lightContainer
    val onPrimary = onColorFor(primary)
    val onContainer = onColorFor(container)

    // 次要/第三色直接用主色的低饱和版本，保证整套配色同源。
    val secondary = if (isDark) darkPrimary.copy(alpha = 0.86f) else lightPrimary.copy(alpha = 0.86f)
    val tertiary = if (isDark) darkContainer else lightContainer

    val oledBlack = baseTheme == MspBaseTheme.BLACK

    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = secondary,
            tertiary = tertiary,
            background = if (oledBlack) Color.Black else darkColorScheme().background,
            onBackground = if (oledBlack) Color(0xFFEDEDED) else darkColorScheme().onBackground,
            surface = if (oledBlack) Color.Black else darkColorScheme().surface,
            onSurface = if (oledBlack) Color(0xFFEDEDED) else darkColorScheme().onSurface,
            surfaceVariant = if (oledBlack) Color(0xFF141414) else darkColorScheme().surfaceVariant,
            onSurfaceVariant = if (oledBlack) Color(0xFFB8B8B8) else darkColorScheme().onSurfaceVariant,
            surfaceContainer = if (oledBlack) Color(0xFF0A0A0A) else darkColorScheme().surfaceContainer,
            surfaceContainerHigh = if (oledBlack) Color(0xFF151515) else darkColorScheme().surfaceContainerHigh,
            surfaceContainerHighest = if (oledBlack) Color(0xFF1E1E1E) else darkColorScheme().surfaceContainerHighest,
            surfaceContainerLow = if (oledBlack) Color(0xFF050505) else darkColorScheme().surfaceContainerLow,
            outline = if (oledBlack) Color(0xFF3A3A3A) else darkColorScheme().outline,
            outlineVariant = if (oledBlack) Color(0xFF262626) else darkColorScheme().outlineVariant,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = container,
            onPrimaryContainer = onContainer,
            secondary = secondary,
            tertiary = tertiary,
        )
    }
}

/**
 * 取一个在该背景上可读的前景色。
 *
 * 阈值 0.5 是按 WCAG 的相对亮度定的：亮背景配近黑，暗背景配白。
 * 用 [Color.luminance]（已做 sRGB 线性化）而不是简单取 RGB 平均，
 * 否则纯黄这类高感知亮度色会被判成「暗色」而配出黑字。
 */
private fun onColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF191919) else Color(0xFFFFFFFF)
