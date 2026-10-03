package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.SubtitleStyle

/**
 * 字幕样式的**几何**结果。
 *
 * 刻意全部是 `Float` / `Int`（sp、dp、px 的裸数字），不出现 `TextStyle`、`Dp`、
 * `Color` 这些 Compose 类型：这一层是「档位 → 数字」的换算，是唯一会算错的地方，
 * 而它必须能在 JVM 单测里跑（`TextStyle` 之类的构建只有到真机上才看得见，
 * 见 `SubtitleStyleRulesTest`）。Compose 那一层只负责把数字塞进 `TextStyle(...).copy()`。
 *
 * @param fontSizeSp 字号，sp。
 * @param lineHeightSp 行高，sp。
 * @param strokeWidthDp 画描边时**给 `Stroke` 的宽度**（是可见宽度的两倍，见下）。
 * @param bottomPaddingDp 字幕层自己的底部内边距，dp。
 */
internal data class SubtitleGeometry(
    val fontSizeSp: Float,
    val lineHeightSp: Float,
    val strokeWidthDp: Float,
    val bottomPaddingDp: Int,
)

/**
 * 把用户选的 [SubtitleStyle] 换算成真正要用的数字。
 *
 * @param baseFontSizeSp 基准字号（原文 `titleMedium`、译文 `bodyLarge` 的 `fontSize`）。
 * @param baseLineHeightSp 基准行高（同一个 `TextStyle` 的 `lineHeight`）。
 *
 * ## 为什么要把基准字号传进来，而不是写死一个 sp
 *
 * 字幕用的是主题排版的比例（`titleMedium` / `bodyLarge`），这两者的绝对值会跟着
 * 「系统字体大小」设置变。写死一个 36sp 就等于让那个设置对字幕失效——用户在系统里
 * 把字调大了一圈，字幕却纹丝不动，而他会认为「这个播放器的字幕就是小」。
 * 档位表里存的是**倍率**，乘在这个基准上。
 *
 * ## 为什么到处都在判 `isFinite`
 *
 * 两个基准值都可能不是正常数字：`TextUnit.Unspecified.value` 是 `NaN`（主题里
 * 没有显式给行高时就是这个），`fontSize` 理论上也可以是 0 或负数。一旦 NaN 漏进去，
 * 后面每一步都还是 NaN，**而且不会抛异常**——Compose 会照着 NaN 排版，结果是
 * 字幕整块消失。那种故障在真机上看起来像「字幕功能坏了」，而根因是主题里少了个字段。
 * 所以这里不信任任何输入，每一个数都收敛到它的兜底值。
 *
 * 这几个兜底值本身也是「档位倍率必须能乘」的前提：
 * [SubtitleStyle.DEFAULT] 必须让本函数返回和 v0.5.15 写死的样式完全一致的数字
 * （16sp / 24sp / 无描边 / 12dp）。
 */
internal fun subtitleGeometry(
    style: SubtitleStyle,
    baseFontSizeSp: Float,
    baseLineHeightSp: Float,
): SubtitleGeometry {
    val baseFontSize = baseFontSizeSp.positiveOr(FALLBACK_FONT_SIZE_SP)
    val baseLineHeight = baseLineHeightSp.positiveOr(baseFontSize * FALLBACK_LINE_HEIGHT_RATIO)
    val scale = style.textSize.scale.positiveOr(1f)
    val spacing = style.lineSpacing.multiplier.positiveOr(1f)

    val fontSize = baseFontSize * scale
    // 行高 = 基准行高 × 字号倍率 × 行距倍率。
    //
    // 字号倍率必须同时乘在行高上，否则「特大」会得到一个比字还矮的行高，
    // 上下两行的字直接叠在一起——那看起来像渲染坏了，而不是「字变大了」。
    // 基准行高比（`baseLineHeight / baseFontSize`，`titleMedium` 是 1.5）在这里
    // 被**保留**下来，所以行距档位改的是行与行之间的空隙，不是单行文字的高度。
    val lineHeight = baseLineHeight / baseFontSize * fontSize * spacing

    val visibleOutline = style.outline.widthDp.positiveOr(0f)

    return SubtitleGeometry(
        fontSizeSp = fontSize,
        lineHeightSp = lineHeight,
        // Compose 的 `Stroke` 以字形轮廓为中线、向内向外各画一半，而里面那一半
        // 会被上面那层填色盖住（字幕是「描边层在下、填色层在上」叠出来的）。
        // 想得到 N dp 的**可见**外扩，就得给 Stroke 2N：这不是估算，是几何。
        strokeWidthDp = visibleOutline * STROKE_TO_VISIBLE_RATIO,
        bottomPaddingDp = style.bottomMargin.dp.coerceAtLeast(0),
    )
}

/** 主题里没给行高时的兜底（`titleMedium` / `bodyLarge` 的默认比就是 1.5）。 */
private const val FALLBACK_LINE_HEIGHT_RATIO = 1.5f

/** 兜底基准字号。只有主题里 `fontSize` 是 `NaN`/0/负数时才会用到。 */
private const val FALLBACK_FONT_SIZE_SP = 16f

/** `Stroke(width) = 可见外扩宽度 × 2`。 */
private const val STROKE_TO_VISIBLE_RATIO = 2f

/** 只接受正常且为正的数，其余（`NaN`、`Infinity`、0、负数）一律换成 [fallback]。 */
private fun Float.positiveOr(fallback: Float): Float =
    if (isFinite() && this > 0f) this else fallback
