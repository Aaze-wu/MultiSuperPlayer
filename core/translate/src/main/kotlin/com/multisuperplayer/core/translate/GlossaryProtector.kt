package com.multisuperplayer.core.translate

/**
 * 私有区（U+E000/U+E001）的两个字符，用来圈出「不许翻译」的词。
 *
 * ## 为什么必须用不可能出现在字幕里的码点
 *
 * 换成 `{{0}}`、`[NAME0]` 这种可见占位符，模型会热心地「顺手修正」它们
 * （把 `[[` 补成 `]]`、把 `{0}` 当变量名翻译掉），还原时就成了残缺文本。
 * 私有区的字符在任何真字幕里都不可能自然出现，模型见到它们只会原样搬运。
 */
internal const val PLACEHOLDER_OPEN = '\uE000'
internal const val PLACEHOLDER_CLOSE = '\uE001'

/**
 * 「不翻译」的词用哨兵替换后再送进模型，拿回来还原。
 *
 * ## 为什么不能只在提示词里说「这些词不要翻」
 *
 * 因为那是**请求**，不是**保证**。实测里同一批 prompt，模型第 1 次听话、
 * 第 7 次就把人名音译了——缓存是按原文哈希存的，于是同一集里前后两处
 * 同一个人名译法不一致，而用户根本无从发现是哪一步出的问题。
 * 替换成占位符之后，至少「不翻译」这部分是**确定性**的，与模型的行为无关。
 *
 * ## 只保护「不翻译」的词
 *
 * 「固定译法」不在这里处理：把 `Guild` 硬替换成 `公会` 会造出「公会长」
 * 这种本来该是「会长」的东西，而模型看得懂上下文、知道该用哪个词。
 * 所以固定译法只写进提示词，见 [Glossary.fixedTranslations]。
 */
internal class GlossaryProtector(glossary: Glossary) {

    /**
     * 长词优先（否则 `Kirito` 会先吃掉 `Kirito Swordsman` 的前半截），
     * 且**一张表同时驱动替换与还原**——分成两张表各自排序，序号一旦错开
     * 就会静默换上另一个人的名字，那种 bug 没人看得出来。
     */
    private val table: List<Triple<String, Regex, String>> = glossary.doNotTranslateTerms()
        .sortedByDescending { it.length }
        .mapIndexed { index, term ->
            val placeholder = placeholder(index)
            // 大小写不敏感：专名在句首/全大写里也会出现。
            Triple(placeholder, Regex(Regex.escape(term), RegexOption.IGNORE_CASE), term)
        }

    val isEmpty: Boolean get() = table.isEmpty()

    fun protect(text: String): String {
        if (table.isEmpty()) return text
        var result = text
        for ((placeholder, regex, _) in table) {
            result = regex.replace(result, placeholder)
        }
        return result
    }

    fun restore(text: String): String {
        if (table.isEmpty()) return text
        var result = text
        for ((placeholder, _, term) in table) {
            result = result.replace(placeholder, term)
        }
        return result
    }
}

private fun placeholder(index: Int): String = "$PLACEHOLDER_OPEN$index$PLACEHOLDER_CLOSE"

/**
 * 把提示词里那几条「占位符规则」写进 system prompt。
 *
 * 模型不认识私有区字符，必须明确告诉它「这是占位符，原样搬」，
 * 否则它有一半概率把它们当乱码删掉。
 */
internal fun GlossaryProtector.promptRules(): String {
    if (isEmpty) return ""
    return "- 译文里出现的 ${PLACEHOLDER_OPEN}数字${PLACEHOLDER_CLOSE} 形式（如 ${PLACEHOLDER_OPEN}0${PLACEHOLDER_CLOSE}）是占位符，" +
        "代表一个人名或专有名词。**必须原样保留，不要翻译、不要改写、不要删除、不要移动它的位置。**\n"
}
