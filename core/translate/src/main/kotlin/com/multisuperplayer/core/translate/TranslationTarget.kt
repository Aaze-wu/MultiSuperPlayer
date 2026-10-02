package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.text.MspText

/**
 * 译文的目标语言。
 *
 * ## 为什么 [code] / [label] / [promptName] 要分成三个
 *
 * - [code] 是**持久化契约**：写进 DataStore，改名就等于清空所有人已选的语言。
 *   所以它用 BCP-47 风格、且永远不要动。
 * - [label] 给界面看，是**跟着界面语言走**的名称（中文界面里写「英语」，
 *   英文界面里写 `English`），所以是 [MspText] 而不是 `String`。
 * - [promptName] 写进系统提示词。模型对「简体中文」这种自称很稳，
 *   对 `zh-Hans` 这种代码却常常答非所问，所以提示词里**只用自然语言名字**，
 *   而且它必须固定为中文——它描述的是「要翻成什么」，跟界面语言无关。
 */
enum class TranslationTarget(
    val code: String,
    val label: MspText,
    val promptName: String,
) {
    SIMPLIFIED_CHINESE("zh-Hans", MspText.Res(R.string.msp_translate_lang_zh_hans), "简体中文"),
    TRADITIONAL_CHINESE("zh-Hant", MspText.Res(R.string.msp_translate_lang_zh_hant), "繁体中文"),
    ENGLISH("en", MspText.Res(R.string.msp_translate_lang_en), "English"),
    JAPANESE("ja", MspText.Res(R.string.msp_translate_lang_ja), "日本語"),
    KOREAN("ko", MspText.Res(R.string.msp_translate_lang_ko), "한국어"),
    ;

    companion object {
        val DEFAULT: TranslationTarget = SIMPLIFIED_CHINESE

        /**
         * 认不出来就回默认值。
         *
         * 刻意不抛异常：设置文件里的脏值（用户从旧版本升级、手改了备份）
         * 不该让播放页在合成阶段崩掉——那是最难定位的一类崩溃。
         */
        fun fromCode(code: String?): TranslationTarget =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
