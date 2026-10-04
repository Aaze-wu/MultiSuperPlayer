package com.multisuperplayer.core.data.settings

/**
 * 用户自定义的强调色，载体是三个滑块的位置而不是一个色值。
 *
 * ## 为什么存 H/S/L 而不是 `#RRGGBB`
 *
 * 因为界面给的就是这三根滑块，而「颜色 → HSL → 滑块位置」需要一条逆向换算，
 * 那条逆向换算在明度被夹取过之后**不一定是可逆的**（夹回范围、深色主色取反都会丢信息）。
 * 直接存滑块位置，写进去再读出来就是同一组数值，滑块不会自己跳。
 *
 * ## 合法性由 [CustomAccentCodec] 负责
 *
 * 这个类只是一组数，不做任何夹取（夹取范围在 UI 层，见 `CustomAccentRanges`）：
 * 数据层去判断「明度多少才算好看」会把它和界面层绑死。
 */
data class CustomAccent(
    val hueDegrees: Float,
    val saturation: Float,
    val lightness: Float,
)

/**
 * [CustomAccent] 的存储编解码：`"250.0,0.55,0.45"`。
 *
 * ## 为什么不用 `String.format("%.3f,%.3f,%.3f", ...)`
 *
 * `String.format` 不指定 `Locale` 时用的是**系统区域**，而中文、法语、德语区域里
 * 小数点是逗号——那会写出 `"0,550"` 这种自己都解析不回来的值，而且只在切换了
 * 系统语言的机器上出现。所以这里用 `Float.toString` / `toFloatOrNull`：
 * 它们与区域无关，且对 `Float` 是精确往返（写进去什么，读出来就是同一个 `Float`）。
 *
 * ## 坏数据一律当成「没设置过」
 *
 * 部分数不够、解析失败、NaN/无穷 —— 全部返回 `null`，界面退回预设强调色。
 * 这比抛异常好：一份手改坏的设置不该让「外观」页打不开。
 *
 * 越界的**有限值**则夹回范围而不是丢掉（色相折算回一圈之内、饱和度/明度夹回 `0~1`）：
 * 手改过设置文件的用户看到的是「颜色被夹到了边界」，而不是「我的设置莫名其妙没了」。
 * 注意这里只夹到 HSL 的**自然范围**，滑块的实际范围（明度 0.10~0.80）属于界面层，
 * 由 `CustomAccentRanges` 在真正取用时再夹一次——数据层不该知道界面层的偏好。
 */
object CustomAccentCodec {

    /** 三个数之间的分隔符。它出现在**用户设备上已存的数据**里，改它等于清空所有人的自定义色。 */
    private const val SEPARATOR = ","

    fun encode(accent: CustomAccent): String =
        "${accent.hueDegrees},${accent.saturation},${accent.lightness}"

    fun decode(raw: String?): CustomAccent? {
        val parts = raw?.split(SEPARATOR) ?: return null
        if (parts.size != 3) return null

        val hue = parts[0].trim().toFloatOrNull() ?: return null
        val saturation = parts[1].trim().toFloatOrNull() ?: return null
        val lightness = parts[2].trim().toFloatOrNull() ?: return null
        if (!hue.isFinite() || !saturation.isFinite() || !lightness.isFinite()) return null

        return CustomAccent(
            hueDegrees = normalizeHue(hue),
            saturation = saturation.coerceIn(0f, 1f),
            lightness = lightness.coerceIn(0f, 1f),
        )
    }

    /** 色相是环：负角度和超过一圈的角度都折算回 `[0, 360)`（360° 与 0° 同色）。 */
    private fun normalizeHue(hue: Float): Float {
        val wrapped = hue % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }
}
