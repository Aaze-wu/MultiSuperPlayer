package com.multisuperplayer.core.data.artwork

/**
 * 从封面提取出的一组颜色，全部是 **ARGB 的 `Int`**，不是 Compose 的 `Color`。
 *
 * 这么定是为了让 [ArtworkColorRules] 能在纯 JVM 单测里跑：一个 `Color`
 * 虽然是内联值类、理论上是纯数据，但一旦涉及 `luminance()` 之类的扩展，
 * 测试就跑在 Compose 的图形栈上，颜色计算这种最需要精确断言的东西
 * 反而变得难测。整条链路只用 Int 进 Int 出，UI 层最后一步再转成 `Color`。
 */
data class ArtworkColors(
    val lightPrimary: Int,
    val lightContainer: Int,
    val darkPrimary: Int,
    val darkContainer: Int,
)

/**
 * 由「一个种子色」推导整套强调色。
 *
 * ## 为什么不引 material-color-utilities
 *
 * 那套库做的是完整的 HCT 调色板（几十个色角色 + 对比度保证），代价是
 * 一个额外的依赖和一套需要理解的色彩空间。这里需要的其实只有四件事：
 * 浅色下能当实心按钮的主色、浅色下的容器色、深色下的主色、深色下的容器色。
 * 用 HSL 做「保色相、调明度」就够了，而且**每一步都能写断言**。
 *
 * ## 为什么灰色封面直接返回 null
 *
 * 一张黑白封面的饱和度接近 0，色相是没有意义的。如果这里硬把饱和度抬到
 * 一个下限，等于凭空给用户造了一个色相（而且大概率落在红色或紫色上），
 * 界面会变成「这张黑白专辑的主题色是紫的」——那不是取色，是编造。
 * 返回 null 让调用方回退到用户自己选的强调色，是唯一诚实的做法。
 */
internal object ArtworkColorRules {

    /** 饱和度低于这个值就当作「没有色相」，直接放弃取色。 */
    private const val MIN_SEED_SATURATION = 0.10f

    /** 对比度需要：浅色模式下当主色用的颜色不能太亮，否则白字压不住。 */
    private const val LIGHT_PRIMARY_MIN_L = 0.28f
    private const val LIGHT_PRIMARY_MAX_L = 0.42f

    /** 容器色：大面积铺在浅色背景上，必须够浅才不像一块补丁。 */
    private const val LIGHT_CONTAINER_L = 0.90f

    /** 深色模式的主色：亮一点才在深底上跳得出来。 */
    private const val DARK_PRIMARY_L = 0.76f
    private const val DARK_CONTAINER_L = 0.30f

    /**
     * @return 推导出的四色，若种子色没有可用的色相则返回 null。
     */
    fun colorsFor(seed: Int): ArtworkColors? {
        val hsl = toHsl(seed)
        val hue = hsl[0]
        val saturation = hsl[1]
        val lightness = hsl[2]

        // 完全不透明的灰 / 黑 / 白都在这里被挡掉。
        if (saturation < MIN_SEED_SATURATION) return null

        // 明度也要筛：一张几乎纯黑的封面（饱和度靠 JPEG 噪声撑到 0.11）
        // 推不出任何有意义的东西，如果强行抬明度会得到一个和封面毫无关系的颜色。
        if (lightness < 0.06f || lightness > 0.96f) return null

        val lightPrimary = fromHsl(
            hue = hue,
            saturation = saturation.coerceIn(0.25f, 0.90f),
            lightness = lightness.coerceIn(LIGHT_PRIMARY_MIN_L, LIGHT_PRIMARY_MAX_L),
        )
        val lightContainer = fromHsl(
            hue = hue,
            saturation = (saturation * 0.60f).coerceIn(0.15f, 0.60f),
            lightness = LIGHT_CONTAINER_L,
        )
        val darkPrimary = fromHsl(
            hue = hue,
            saturation = (saturation * 0.85f).coerceIn(0.20f, 0.90f),
            lightness = DARK_PRIMARY_L,
        )
        val darkContainer = fromHsl(
            hue = hue,
            saturation = (saturation * 0.60f).coerceIn(0.15f, 0.60f),
            lightness = DARK_CONTAINER_L,
        )

        return ArtworkColors(
            lightPrimary = lightPrimary,
            lightContainer = lightContainer,
            darkPrimary = darkPrimary,
            darkContainer = darkContainer,
        )
    }

    /** @return `[hue(0..360), saturation(0..1), lightness(0..1)]`，alpha 被忽略。 */
    internal fun toHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val lightness = (max + min) / 2f

        // max == min 就是灰：此时 max - min = 0，下面每一次除法都会得到 NaN。
        // 这不是理论情况——黑白封面走的正是这条路径。
        if (max == min) return floatArrayOf(0f, 0f, lightness)

        val delta = max - min
        val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
        val hue = when (max) {
            r -> ((g - b) / delta + if (g < b) 6f else 0f)
            g -> ((b - r) / delta + 2f)
            else -> ((r - g) / delta + 4f)
        } * 60f

        return floatArrayOf(hue, saturation, lightness)
    }

    /** @return 不透明的 ARGB。 */
    internal fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f / 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)

        if (s == 0f) {
            val grey = (l * 255f).toInt().coerceIn(0, 255)
            return 0xFF shl 24 or (grey shl 16) or (grey shl 8) or grey
        }

        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q

        val r = hueToChannel(p, q, h + 1f / 3f)
        val g = hueToChannel(p, q, h)
        val b = hueToChannel(p, q, h - 1f / 3f)

        return (0xFF shl 24) or
            (channel(r) shl 16) or
            (channel(g) shl 8) or
            channel(b)
    }

    /** 每通道的加减超出 1/3 要绕到另一端，这正是色环的环绕。 */
    private fun hueToChannel(p: Float, q: Float, t: Float): Float {
        var x = t
        if (x < 0f) x += 1f
        if (x > 1f) x -= 1f
        return when {
            x < 1f / 6f -> p + (q - p) * 6f * x
            x < 1f / 2f -> q
            x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
            else -> p
        }
    }

    private fun channel(value: Float): Int = (value * 255f).toInt().coerceIn(0, 255)
}
