package com.multisuperplayer.core.data.settings

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.R

/**
 * 字幕外观偏好。
 *
 * ## 为什么是「档位枚举」而不是「一个 Float」
 *
 * 字号、行距、底部距离都能用一个数字表示，看起来直接存数字更省事。但那样会出现
 * 三件事，每一件都比省下的几个枚举难收拾：
 *
 * 1. **滑块/输入框要么不存在，要么没人敢拖。** 用户不知道「0.72 倍行距」长什么样，
 *    只能拖两下看画面——而他正被字幕挡着的那段台词已经过去了。
 * 2. **没有「默认值」这个点。** 「恢复默认」得靠一个写死的数字，而那个数字和
 *    渲染层用的默认值迟早会不一样（一个改了，另一个不知道）。
 * 3. **存进去的值无法解释。** 从用户设备上读回 1.37 时，没人说得清这是哪个版本的
 *    界面写进去的，也没法判断它是不是还合法。
 *
 * 所以每一维都是一张**固定的档位表**，存的是枚举名。档位表将来可以加档
 * （认不出来的值回退到默认，见各自的 [fromKey]），但已发布的档位名不能改。
 *
 * ## 为什么文案（`label`）也放在这里
 *
 * 播放页的字幕面板和设置页的「播放设置」都要画这套档位，两边必须是同一套话。
 * 分头写两份必然漂移——同一个模式在两个页面上叫不同的名字，用户会以为它们是两件事。
 * 这与 [AspectRatioMode] 的处理一致。
 */
data class SubtitleStyle(
    val textSize: SubtitleTextSize = SubtitleTextSize.DEFAULT,
    val lineSpacing: SubtitleLineSpacing = SubtitleLineSpacing.DEFAULT,
    val outline: SubtitleOutline = SubtitleOutline.DEFAULT,
    val bottomMargin: SubtitleBottomMargin = SubtitleBottomMargin.DEFAULT,
) {
    companion object {
        /**
         * 出厂样式。
         *
         * ⚠️ 这些默认值**同时**是渲染层的基线：`SubtitleTextSize.NORMAL` 的倍率
         * 必须是 1.0、`SubtitleLineSpacing.NORMAL` 必须是 1.0、`SubtitleOutline.NONE`
         * 必须是 0、`SubtitleBottomMargin.NEAR` 必须是 12dp（v0.5.15 写死的那个值），
         * 否则「没设置过」的字幕会和 v0.5.15 看起来不一样——一次没人要求的外观变化。
         * 这条约束由 `SubtitleStyleRulesTest` 的「默认样式换算出来的就是 v0_5_15
         * 写死的数字」用例守着。
         */
        val DEFAULT = SubtitleStyle()
    }
}

/**
 * 字幕字号。
 *
 * 倍率是**相对基准字号**（原文用 `titleMedium`、译文用 `bodyLarge`）的倍数，
 * 不是绝对值：把 sp 写死在这里，等于让「跟随系统字体大小」这个设置对字幕失效。
 *
 * @param scale 基准字号的倍数。
 */
enum class SubtitleTextSize(val scale: Float, val label: MspText) {
    SMALL(0.8f, MspText.Res(R.string.msp_subtitle_style_size_small)),

    NORMAL(1.0f, MspText.Res(R.string.msp_subtitle_style_normal)),

    LARGE(1.25f, MspText.Res(R.string.msp_subtitle_style_size_large)),

    /** 投影/小屏远看用。再大就会把画面下半截全盖住。 */
    HUGE(1.5f, MspText.Res(R.string.msp_subtitle_style_size_huge)),
    ;

    companion object {
        val DEFAULT = NORMAL

        /** 认不出来的值回退到 [DEFAULT]，不抛异常（同 [SubtitleDisplayMode.fromKey]）。 */
        fun fromKey(key: String?): SubtitleTextSize =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * 字幕行距。
 *
 * 倍率乘在**基准行高比**上（`titleMedium` 是 24sp/16sp = 1.5）。所以 1.0 就是
 * 现在的样子：行距档位只改行与行之间的空隙，不改单行文字本身的高度。
 *
 * @param multiplier 基准行高比的倍数。
 */
enum class SubtitleLineSpacing(val multiplier: Float, val label: MspText) {
    TIGHT(0.85f, MspText.Res(R.string.msp_subtitle_style_line_tight)),

    NORMAL(1.0f, MspText.Res(R.string.msp_subtitle_style_normal)),

    LOOSE(1.3f, MspText.Res(R.string.msp_subtitle_style_line_loose)),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun fromKey(key: String?): SubtitleLineSpacing =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * 字幕描边。
 *
 * 字幕盖在画面上，画面本身可能是任何颜色，所以「白字 + 黑边」比任何主题配色都可靠
 * （这也是 [SubtitleSettings] 那一层刻意不看主题的原因）。这里调的是那圈黑边的粗细。
 *
 * `NONE` 是默认值，**不是**「没有可读性保障」：底下还有一层柔和的黑色阴影
 * （`SubtitleOverlay` 的 `SUBTITLE_SHADOW`，v0.5.15 起一直在用）。描边是
 * 「阴影还不够、想要更硬朗的轮廓」时才加上去的。默认开描边会把所有老用户看到的
 * 字幕一起变样，那不是这个功能该做的事。
 *
 * @param widthDp **可见的**外扩宽度。真正的 `Stroke` 宽度是它的两倍——Compose 的
 *   描边以字形轮廓为中心向外画，被填色层盖住的里圈那一半算白画（见
 *   `SubtitleGeometry.strokeWidthDp`）。
 */
enum class SubtitleOutline(val widthDp: Float, val label: MspText) {
    NONE(0f, MspText.Res(R.string.msp_subtitle_style_outline_none)),

    THIN(1f, MspText.Res(R.string.msp_subtitle_style_outline_thin)),

    NORMAL(1.8f, MspText.Res(R.string.msp_subtitle_style_normal)),

    THICK(3f, MspText.Res(R.string.msp_subtitle_style_outline_thick)),
    ;

    companion object {
        val DEFAULT = NONE

        fun fromKey(key: String?): SubtitleOutline =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * 字幕离画面底边的距离。
 *
 * 单位是 dp，直接就是字幕层自己的底部内边距（水平的 16dp 不在这里，它和样式无关）。
 *
 * 注意这个距离是相对**画面矩形**的底边，不是屏幕底边：字幕挂在视频画面上，
 * 底部黑边（画面比例不是 16:9 时）里本来就不该有字幕。所以「抬高」在黑边很宽的
 * 片子上看起来会比数字上更靠上——这是对的，它抬的是「画面里的字」。
 */
enum class SubtitleBottomMargin(val dp: Int, val label: MspText) {
    /** 贴边。只留一点点，够让描边/阴影不贴着画面边缘被切掉。 */
    EDGE(4, MspText.Res(R.string.msp_subtitle_style_margin_edge)),

    /** v0.5.15 的位置（12dp），保持老用户看到的字幕不动。 */
    NEAR(12, MspText.Res(R.string.msp_subtitle_style_normal)),

    RAISED(48, MspText.Res(R.string.msp_subtitle_style_margin_raised)),

    HIGH(96, MspText.Res(R.string.msp_subtitle_style_margin_high)),
    ;

    companion object {
        val DEFAULT = NEAR

        fun fromKey(key: String?): SubtitleBottomMargin =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}
