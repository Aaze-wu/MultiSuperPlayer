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
 *
 * ## 为什么要 15 门，以及为什么不是 33 门
 *
 * 设备上那条混元模型支持 33 种语言，但**界面上的可选项不等于模型的能力上限**：
 * 每多一门语言就要多三份文案、一条示例（见 `TranslationPrompt.exampleFor` 的
 * `when`），而一门一个月才用一次的语言，那份示例没人会发现它写错了。
 *
 * 所以取的是「使用频率 × 可验证性」的交集：已有 5 门 + 10 门最常见的外语。
 * 不在这份清单里并不是做不到——选「自定义」服务商、在术语表里说明需要什么语言，
 * 任何一个云端模型都会照做。真正的限制只有两个：提示词里的示例覆盖不到，
 * 以及这条清单的持久化契约（[code]）不能反复改。
 */
enum class TranslationTarget(
    val code: String,
    val label: MspText,
    val promptName: String,
) {
    // ===== 前 5 门是 v0.6.4 就有的，**顺序与 code 都不能动**：它们是持久化契约，
    // 而且 `TargetPicker` 按声明顺序渲染，改顺序会让用户已选的语言看起来变了。
    SIMPLIFIED_CHINESE("zh-Hans", MspText.Res(R.string.msp_translate_lang_zh_hans), "简体中文"),
    TRADITIONAL_CHINESE("zh-Hant", MspText.Res(R.string.msp_translate_lang_zh_hant), "繁体中文"),
    ENGLISH("en", MspText.Res(R.string.msp_translate_lang_en), "English"),
    JAPANESE("ja", MspText.Res(R.string.msp_translate_lang_ja), "日本語"),
    KOREAN("ko", MspText.Res(R.string.msp_translate_lang_ko), "한국어"),

    // ===== 以下是 15 门那份清单里补上的，一律**追加在末尾**。
    RUSSIAN("ru", MspText.Res(R.string.msp_translate_lang_ru), "俄语"),
    SPANISH("es", MspText.Res(R.string.msp_translate_lang_es), "西班牙语"),
    FRENCH("fr", MspText.Res(R.string.msp_translate_lang_fr), "法语"),
    GERMAN("de", MspText.Res(R.string.msp_translate_lang_de), "德语"),
    PORTUGUESE("pt", MspText.Res(R.string.msp_translate_lang_pt), "葡萄牙语"),
    ITALIAN("it", MspText.Res(R.string.msp_translate_lang_it), "意大利语"),
    ARABIC("ar", MspText.Res(R.string.msp_translate_lang_ar), "阿拉伯语"),
    THAI("th", MspText.Res(R.string.msp_translate_lang_th), "泰语"),
    VIETNAMESE("vi", MspText.Res(R.string.msp_translate_lang_vi), "越南语"),
    INDONESIAN("id", MspText.Res(R.string.msp_translate_lang_id), "印尼语"),
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
