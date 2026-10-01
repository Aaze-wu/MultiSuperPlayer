package com.multisuperplayer.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 配色之外、播放器自己需要的语义色。
 *
 * 为什么不把这些硬编码在播放页里：歌词「已唱 / 未唱」的颜色、视频上方的
 * 遮罩浓度，都要随主题（尤其是纯黑模式和封面取色）一起变。散落在各个
 * composable 里的 `Color.Black.copy(alpha = .4f)` 是全应用里最难改的东西。
 */
@Immutable
data class MspTokens(
    /** 当前正在唱的那句歌词。 */
    val lyricActive: Color,
    /** 还没唱 / 已经唱过的歌词。 */
    val lyricInactive: Color,
    /** 歌词描边（浅色字幕压在亮画面上时唯一的可读性保障）。 */
    val lyricOutline: Color,
    /** 视频控件浮层的压暗遮罩。 */
    val playerScrim: Color,
    /** 视频区域自身的背景（不是 surface，要看不出「卡片感」）。 */
    val playerBackground: Color,
    /** 逐字高亮的已唱部分。 */
    val karaokeActive: Color,
    val isDark: Boolean,
    val isOledBlack: Boolean,
)

/**
 * 歌词排版。
 *
 * 单独抽出来是因为歌词要跟随「用户字号偏好 × 当前歌词的最大行数」
 * 动态缩放，跟 Material 的正文样式不是一回事。
 */
@Immutable
data class MspLyricTypography(
    val active: TextStyle,
    val inactive: TextStyle,
    val translation: TextStyle,
)

val MspLyricTypographyDefaults = MspLyricTypography(
    active = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    inactive = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, lineHeight = 28.sp),
    translation = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
)

/**
 * 用 [staticCompositionLocalOf] 而不是 [androidx.compose.runtime.compositionLocalOf]：
 * 主题在运行期几乎不变，而 static 版本在读不到值时不建立订阅关系、
 * 读取开销更低。这个值会被**每一次绘制**读到（歌词、图标、遮罩），值得省。
 */
val LocalMspTokens = staticCompositionLocalOf<MspTokens> {
    error("LocalMspTokens 未提供：请用 MspTheme { } 包裹内容")
}

val LocalMspLyricTypography = staticCompositionLocalOf { MspLyricTypographyDefaults }

/** 由配色方案推导出令牌，保证「令牌永远和配色同源」，不会两边各改一半。 */
internal fun mspTokensFor(
    isDark: Boolean,
    oledBlack: Boolean,
    primary: Color,
    background: Color,
): MspTokens = MspTokens(
    lyricActive = primary,
    lyricInactive = if (isDark) Color(0xFF9E9E9E) else Color(0xFF6E6E6E),
    lyricOutline = if (isDark) Color(0xCC000000) else Color(0xCCFFFFFF),
    playerScrim = Color.Black.copy(alpha = if (isDark) 0.55f else 0.38f),
    playerBackground = if (oledBlack) Color.Black else background,
    karaokeActive = primary.copy(alpha = 0.92f),
    isDark = isDark,
    isOledBlack = oledBlack,
)
