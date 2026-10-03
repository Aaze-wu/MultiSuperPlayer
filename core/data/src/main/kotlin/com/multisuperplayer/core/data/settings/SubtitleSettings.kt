package com.multisuperplayer.core.data.settings

/**
 * 字幕显示模式。
 *
 * ## 为什么 [DEFAULT] 不是「关」
 *
 * 默认关闭的理由是「一条意外挂上的字幕会让用户困惑」。但这条理由只在**匹配不可靠**
 * 的时候成立——而自动关联的门槛已经被收到「字幕名与媒体名互为前缀」这一档
 * （见 `AUTO_MATCH_SCORE`），一部片子旁边放着同名字幕基本就是它的字幕。
 *
 * 反过来，默认关闭的代价是：**这个功能默认等于不存在**。用户不会去翻「字幕」按钮
 * 找一个他不知道自己需要的开关，所以最后得到的是「播放器不支持字幕」的印象。
 *
 * 结论：默认显示原文；拿不准的匹配不自动应用，而不是整体默认关闭。
 *
 * ## 为什么「关」也在这里，而不是单独一个「不使用字幕」状态
 *
 * 「关」和「这条文件不用字幕」看起来是两个东西，但用户看到的结果完全一样
 * （屏幕上没有字幕）。做成两个状态就会出现「我明明关了它还是显示」这类问题——
 * 这正是「一个布尔同时表示两件事」的翻版：显示判定看的是模式，
 * 而用户以为自己操作的是「这条文件」。
 */
enum class SubtitleDisplayMode {
    /** 不显示字幕。 */
    OFF,

    /** 只显示原文。 */
    ORIGINAL_ONLY,

    /** 只显示译文。 */
    TRANSLATION_ONLY,

    /** 原文 + 译文。 */
    BILINGUAL,
    ;

    companion object {
        val DEFAULT = ORIGINAL_ONLY

        /**
         * 把存储里的字符串还原成枚举。
         *
         * 认不出来时回退到 [DEFAULT] 而不是抛异常：用户在旧版本里存过某个值、
         * 或者被别的东西写脏了，都不该让播放页打不开。字幕偏好不值得用崩溃来保护。
         */
        fun fromKey(key: String?): SubtitleDisplayMode {
            if (key.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.name == key } ?: DEFAULT
        }
    }
}

/**
 * 字幕相关偏好。
 *
 * 只有一个字段也做成 data class：它要跟着 DataStore 的键一起长大（字号、位置、
 * 背景不透明度…），而现在就定型成 `Flow<SubtitleDisplayMode>` 的话，
 * 后面每加一个字段都要改一遍调用点。
 */
data class SubtitleSettings(
    val displayMode: SubtitleDisplayMode = SubtitleDisplayMode.DEFAULT,
    /**
     * 字幕外观（字号 / 行距 / 描边 / 底部距离）。
     *
     * 做成**一个**字段而不是四个平铺字段：[SubtitleStyle] 是一组必须一起取默认值的
     * 参数，分开摆会让「恢复默认样式」变成四处写入，而其中最容易被漏掉的那一处
     * 只在用户重启应用后才看得出来。
     *
     * 它是**全局**的、跨文件保留的：字号小是「我看不清」这件事的属性，不是某一部片子的。
     * 与「字幕时间轴微调」（`SubtitleUiState.timelineOffsetMs`）那种「本次播放」状态刻意相反。
     */
    val style: SubtitleStyle = SubtitleStyle.DEFAULT,
)
