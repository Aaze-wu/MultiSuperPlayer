package com.multisuperplayer.core.translate

/**
 * 术语表的文本形式（界面上那一栏多行输入框）。
 *
 * ## 为什么不让用户直接编辑 JSON
 *
 * 存储用的是 JSON（[encodeGlossary]），但 JSON 拿来手写太容易坏：少一个引号、
 * 多一个尾逗号都会让**整张表**失效，而用户看到的只是「术语好像没生效」。
 * 这里换成一行一条的写法，坏一行只丢一行：
 *
 * ```
 * # 井号开头的行是注释
 * 桐人
 * 亚丝娜 = 亚丝娜
 * Guild = 公会
 * ```
 *
 * - 只有左边（`Guild`）⇒ **不翻译**，保持原文；
 * - 左边 = 右边 ⇒ **固定译法**。
 *
 * 这个约定和 [doNotTranslateTerms] / [fixedTranslations] 的定义是同一套，
 * 所以界面上不用出现「哨兵」「保护」这类内部词汇。
 */
internal const val GLOSSARY_SEPARATOR = "="

/** 全角等号也当成等号：中文输入法下 `=` 常常打出 `＝`，而这个字符肉眼几乎看不出区别。 */
private const val GLOSSARY_SEPARATOR_WIDE = "＝"

/**
 * 解析术语表文本。
 *
 * 三条「宽容」规则，每一条都对应一种真实的输入：
 * 1. 空行、`#`/`//` 开头的行跳过 ⇒ 用户可以把注释留在里面；
 * 2. 分隔符取**第一个**等号 ⇒ 译文里本身带等号（`x = a=b`）不会把译文截断；
 * 3. 同一个左边出现多次 ⇒ **后写的赢** ⇒ 用户在末尾补一条纠正即可，不用回去删旧的。
 *
 * 左边为空的条目直接丢掉：那种行只会是用户误敲了一个等号。
 */
fun parseGlossary(text: String): Glossary {
    val result = LinkedHashMap<String, String>()
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEach

        val separatorAt = line.indexOf(GLOSSARY_SEPARATOR)
        val wideSeparatorAt = line.indexOf(GLOSSARY_SEPARATOR_WIDE)
        // 取最先出现的那个分隔符（半角/全角都算）。
        val at = listOf(separatorAt, wideSeparatorAt).filter { it >= 0 }.minOrNull()

        if (at == null) {
            // 没有等号：整行就是「不翻译」的词。
            result[line] = ""
            return@forEach
        }

        val key = line.substring(0, at).trim()
        if (key.isEmpty()) return@forEach
        result[key] = line.substring(at + 1).trim()
    }
    return result
}

/**
 * 把术语表写回文本。
 *
 * 「不翻译」的条目输出成单独一行（不带等号），而不是 `桐人 = 桐人`：
 * 后者虽然等价，但用户下次看到会以为它是固定译法。
 */
fun formatGlossary(glossary: Glossary): String = glossary.entries
    .filter { it.key.isNotBlank() }
    .joinToString("\n") { (key, value) ->
        val trimmed = key.trim()
        if (value.isBlank() || value.trim() == trimmed) trimmed else "$trimmed = ${value.trim()}"
    }
