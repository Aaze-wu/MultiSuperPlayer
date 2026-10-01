package com.multisuperplayer.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 应用主题入口。**整个应用只能通过它取色**，不要在业务代码里写死颜色。
 *
 * ## 强调色的优先级
 *
 * 封面取色 > 系统取色（莫奈）> 用户选的预设。
 *
 * 「封面取色」压过系统取色是**故意**的：默认情况下系统取色是开着的，
 * 如果让系统取色赢，那 Android 12+ 的用户打开「封面取色」会看不到任何
 * 变化，只能得出结论「这个功能坏了」。两个开关里必须有一个明确地赢，
 * 而且要在设置页里把这件事写出来。
 *
 * @param baseTheme 浅色 / 深色 / 纯黑 / 跟随系统。
 * @param accent 强调色预设；被 [useDynamicColor] 或 [colorFromArtwork] 覆盖。
 * @param useDynamicColor 是否启用「莫奈取色」。默认开——用户的系统主题色
 *   是他对整台设备的一致选择，播放器没理由唱反调。纯黑模式除外：
 *   系统取色会给出一堆深灰，正好破坏纯黑省的像素。
 * @param colorFromArtwork 是否用当前封面（[LocalArtworkAccentState] 里的值）取色。
 * @param content 内容。
 */
@Composable
fun MspTheme(
    baseTheme: MspBaseTheme = MspBaseTheme.FOLLOW_SYSTEM,
    accent: MspAccent = MspAccent.DEFAULT,
    useDynamicColor: Boolean = true,
    colorFromArtwork: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isDark = when (baseTheme) {
        MspBaseTheme.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        else -> baseTheme.isDark
    }
    val oledBlack = baseTheme == MspBaseTheme.BLACK

    // 只有真正读这一行，「换封面 → 换主题」才是细粒度重组：读的是 snapshot state，
    // 而不是一个每次取值都换身份的 CompositionLocal。
    val artwork = if (colorFromArtwork) LocalArtworkAccentState.current.value else null

    val context = LocalContext.current
    val androidDynamic = useDynamicColor && artwork == null && !oledBlack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = remember(baseTheme, accent, isDark, androidDynamic, artwork) {
        when {
            androidDynamic ->
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            artwork != null -> composeColorScheme(artwork, baseTheme, isDark)

            else -> composeColorScheme(accent, baseTheme, isDark)
        }
    }

    val tokens = remember(colorScheme, isDark, oledBlack) {
        mspTokensFor(
            isDark = isDark,
            oledBlack = oledBlack,
            primary = colorScheme.primary,
            background = colorScheme.background,
        )
    }

    CompositionLocalProvider(
        LocalMspTokens provides tokens,
        LocalMspLyricTypography provides MspLyricTypographyDefaults,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MspTypography,
            shapes = MspShapes,
            content = content,
        )
    }
}

/**
 * 字体阶梯暂时沿用 Material 3 基线。
 *
 * 刻意不在这里「顺手调大一点」：中文界面在 Material 默认字号下本来就偏挤，
 * 真正该做的是引入思源黑体/鸿蒙字体并单独调中文字号，那属于字体层的事，
 * 混在主题里改会让「主题」和「字体」两件事纠缠不清。
 */
val MspTypography: Typography = Typography()

/** 圆角偏大一点，跟「播放器」这类内容型应用的观感更配。 */
val MspShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
