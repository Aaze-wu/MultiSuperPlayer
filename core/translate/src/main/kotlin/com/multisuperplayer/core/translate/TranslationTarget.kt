package com.multisuperplayer.core.translate

/**
 * 译文的目标语言。
 *
 * ## 为什么 [code] / [label] / [promptName] 要分成三个
 *
 * - [code] 是**持久化契约**：写进 DataStore，改名就等于清空所有人已选的语言。
 *   所以它用 BCP-47 风格、且永远不要动。
 * - [label] 给界面看，可以随文案调整。
 * - [promptName] 写进系统提示词。模型对「简体中文」这种自称很稳，
 *   对 `zh-Hans` 这种代码却常常答非所问，所以提示词里**只用自然语言名字**。
 */
enum class TranslationTarget(
    val code: String,
    val label: String,
    val promptName: String,
) {
    SIMPLIFIED_CHINESE("zh-Hans", "简体中文", "简体中文"),
    TRADITIONAL_CHINESE("zh-Hant", "繁体中文", "繁体中文"),
    ENGLISH("en", "英语", "English"),
    JAPANESE("ja", "日语", "日本語"),
    KOREAN("ko", "韩语", "한국어"),
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
