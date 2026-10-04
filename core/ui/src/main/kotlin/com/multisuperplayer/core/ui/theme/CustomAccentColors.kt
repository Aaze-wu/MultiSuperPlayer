package com.multisuperplayer.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 自定义强调色的取值范围。滑块的范围和推导时夹取的范围**必须是这一份**：
 * 写两遍迟早会漂移成「滑块能拖到 85%，推导却按 80% 算」，
 * 而用户看到的只是「拖到头颜色就不动了」——既不像坏了，也无从下手。
 */
object CustomAccentRanges {

    const val HUE_MIN = 0f
    const val HUE_MAX = 360f

    const val SATURATION_MIN = 0f
    const val SATURATION_MAX = 1f

    /**
     * 明度**刻意不开放 0~1**。
     *
     * - `0` = 纯黑的强调色：在深色基底上和正文一个颜色，「强调」这件事就没了。
     * - `1` = 纯白的强调色：在白底上等于把按钮藏起来，看起来像界面坏了。
     *
     * 两者都不是「用户想要的效果」，而是「用户把界面调坏了」；而调坏之后
     * 他既看不出错在哪，也不知道怎么回去（滑块本身没有刻度提示）。
     * 所以上下各留一段余量：拖到头仍然是一个能看的颜色。
     */
    const val LIGHTNESS_MIN = 0.10f
    const val LIGHTNESS_MAX = 0.80f

    /**
     * 没有自定义色时滑块的起点。
     *
     * 刻意**不**从当前预设反推滑块位置：那需要一条 `Color → HSL` 的逆向换算，
     * 而且预设的浅色容器并不是「从主色推出来的固定明度淡色」，反推出来是
     * 「一半对一半错」的位置，比一个明确的起点更让人困惑。
     * 这个值接近默认强调色靛蓝的色相，让人一眼知道该往哪边拖。
     */
    const val DEFAULT_HUE = 250f
    const val DEFAULT_SATURATION = 0.55f
    const val DEFAULT_LIGHTNESS = 0.45f

    fun clampSaturation(value: Float): Float = value.coerceIn(SATURATION_MIN, SATURATION_MAX)

    fun clampLightness(value: Float): Float = value.coerceIn(LIGHTNESS_MIN, LIGHTNESS_MAX)

    /**
     * 色相是一个**环**：把任意角度折算回 `[0, 360)`（360° 和 0° 是同一个颜色）。
     *
     * 非有限值（NaN / 无穷）回落到 [DEFAULT_HUE]：它只可能来自手改过的设置文件，
     * 而一个 NaN 的色相会让配色整块变成算不出来的结果。
     */
    fun normalizeHue(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_HUE
        val wrapped = value % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }
}

/**
 * 浅色容器固定 0.90、深色容器固定 0.30：它们在白底/深底上要一直是一块**淡色/深色**。
 * 跟着用户拖的明度走的话，明度低时浅色容器会变成一块深色，和白底直接打架。
 */
private const val LIGHT_CONTAINER_LIGHTNESS = 0.90f
private const val DARK_CONTAINER_LIGHTNESS = 0.30f

/** 容器色统一降饱和：一块太艳的淡色会盖过它上面的正文。 */
private const val CONTAINER_SATURATION = 0.60f

/** 深色主色升饱和：亮色在深底上本来就需要更艳一点才看得出来。 */
private const val DARK_PRIMARY_SATURATION = 1.30f

/**
 * 深色主色的明度窗口。
 *
 * 它的来源是「用户明度的反面」（明度低 ⇒ 深色主色亮），但必须夹在这个窗口里：
 * 不夹的话，用户把明度拖到 0.80 会得到一个明度 0.20 的「深色强调色」——
 * 在深色基底上它和背景糊成一片，那正是自定义颜色最容易翻车的地方。
 */
private const val DARK_PRIMARY_MIN_LIGHTNESS = 0.60f
private const val DARK_PRIMARY_MAX_LIGHTNESS = 0.92f

/**
 * 用户自定义强调色：三个滑块（色相 / 饱和度 / 明度）→ 四个种子色。
 *
 * ## 为什么不复用 `core:data` 的 `ArtworkColorRules`
 *
 * 那个对象解决的是另一个问题：从一个**不透明的种子色**（专辑封面量化出来的一坨像素）
 * 猜出一套能用的配色，所以它的输入是 `Int`、要先挡掉灰/黑/白、明度窗口由它自己定。
 * 这里的输入本来就是用户手拖的 H/S/L——明度就是要用的那个值，不该再被一套为封面
 * 设计的窗口改写；何况它是 `internal`，而 `:core:ui` 依赖不到 `:core:data`。
 *
 * **真正需要「只有一份」的是拿到种子之后那一步**：整套配色方案怎么拼（对比度怎么选、
 * 中性灰怎么去色、纯黑怎么覆盖）全部在 [composeColorScheme] 里，预设、封面取色、
 * 自定义三条路走的是同一个函数。所以三者的按钮文字对比度、纯黑基底行为完全一致。
 *
 * ## 四个色值分别由什么决定
 *
 * | 角色 | 明度 | 为什么 |
 * |---|---|---|
 * | 浅色主色 | 用户拖的 L | 这是用户真正在调的那一个颜色 |
 * | 浅色容器 | 固定 0.90 | 它在白底上要一直是一块淡色 |
 * | 深色主色 | `1 - L`，夹在 0.60~0.92 | 深色基底上要的是**亮**的那个：L 低时必须自动变亮，否则强调色在深底上糊掉 |
 * | 深色容器 | 固定 0.30 | 同浅色容器，深底上要一直是一块深色 |
 *
 * @param hueDegrees 色相角度，任意值都会折算回 `[0, 360)`。
 * @param saturation 饱和度，越界会被夹回 `[0, 1]`。
 * @param lightness 明度，越界会被夹回滑块范围（见 [CustomAccentRanges.LIGHTNESS_MIN]）。
 */
fun customAccentColors(hueDegrees: Float, saturation: Float, lightness: Float): ArtworkAccent {
    val hue = CustomAccentRanges.normalizeHue(hueDegrees)
    val saturation = CustomAccentRanges.clampSaturation(saturation)
    val lightness = CustomAccentRanges.clampLightness(lightness)
    val containerSaturation = saturation * CONTAINER_SATURATION
    val darkLightness = (1f - lightness)
        .coerceIn(DARK_PRIMARY_MIN_LIGHTNESS, DARK_PRIMARY_MAX_LIGHTNESS)

    return ArtworkAccent(
        lightPrimary = Color.hsl(hue, saturation, lightness),
        lightContainer = Color.hsl(hue, containerSaturation, LIGHT_CONTAINER_LIGHTNESS),
        darkPrimary = Color.hsl(
            hue,
            (saturation * DARK_PRIMARY_SATURATION).coerceAtMost(CustomAccentRanges.SATURATION_MAX),
            darkLightness,
        ),
        darkContainer = Color.hsl(hue, containerSaturation, DARK_CONTAINER_LIGHTNESS),
    )
}
