package com.multisuperplayer.core.translate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 解析模型返回的译文。纯函数。
 *
 * ## 为什么不用 `json.loads(text[第一个 { : 最后一个 }])`
 *
 * 那种写法在真实返回上会**整体丢掉一批**：
 * - `{...}, {...}`（返回了多个值）→ 报「Extra data」，整批作废；
 * - `{...}` 后面跟一句「以上是译文。」→ 同样整批作废。
 *
 * 所以这里的做法是「**扫描一段值序列**」：从左到右找配对完整的 JSON 值，
 * 每个都试一次，只要有一个的形状对得上就成。多出来的解释文字、被截断的最后一个值，
 * 都不影响前面已经完整的部分。
 *
 * ## 形状错误与截断必须报成两句不同的话
 *
 * 因为处置方向相反：形状不对要拆小批次/改提示词，被截断要加预算。
 * 如果两者共用一句「解析失败」，重试永远撞同一堵墙，而且看日志的人
 * 会一直去查提示词。
 */
internal sealed interface ParsedTranslations {
    data class Ok(val texts: List<String>) : ParsedTranslations
    data class Err(val failure: TranslationFailure) : ParsedTranslations
}

/** 最多往下钻几层。防的是退化/恶意嵌套，不是正常返回。 */
internal const val MAX_JSON_DESCENT = 3

/**
 * @param finishReason HTTP 层拿到的 `finish_reason`，用于判断是不是被截断。
 * @param maxTokens 本次请求的 `max_tokens`，用于「估算长度接近上限」这条独立判据。
 */
internal fun parseTranslationPayload(
    rawContent: String,
    expectedCount: Int,
    finishReason: String? = null,
    maxTokens: Int = 0,
): ParsedTranslations {
    val text = stripCodeFences(rawContent).trim()
    if (text.isEmpty()) {
        return ParsedTranslations.Err(
            TranslationFailure.BadResponse("模型返回了空白内容"),
        )
    }

    val notes = mutableListOf<String>()
    val candidates = mutableListOf<List<String>>()

    val values = scanTopLevelJsonValues(text)
    if (values.isEmpty()) {
        notes += "没有任何完整的 JSON 值"
    } else {
        values.forEach { collectCandidates(it, 0, candidates, notes) }
    }

    candidates.firstOrNull { it.size == expectedCount }?.let {
        return ParsedTranslations.Ok(it)
    }

    // 兜底：模型完全没给 JSON，就交了一段纯文本。只在正文里没有任何 JSON 括号时才用，
    // 否则「被截断的 JSON」会被当成纯文本凑出一堆残句，那比直接失败糟得多。
    if (values.isEmpty() && text.none { it == '{' || it == '[' }) {
        val lines = text.lines().map { stripLinePrefix(it) }.filter { it.isNotBlank() }
        if (lines.size == expectedCount) return ParsedTranslations.Ok(lines)
        if (lines.isNotEmpty()) notes += "纯文本按行拆出 ${lines.size} 行，与期望的 $expectedCount 行不符"
    }

    if (candidates.isNotEmpty()) {
        val sizes = candidates.map { it.size }.distinct().sorted().joinToString("/")
        notes += "找到了译文数组，但条数是 $sizes，期望 $expectedCount"
    }

    val unclosed = hasUnclosedJson(text)
    val estimated = estimateTokens(text)
    val looksTruncated = chatFinish(finishReason) == ChatFinish.LENGTH ||
        (unclosed && maxTokens > 0 && estimated * 10 >= maxTokens * 9)

    val suffix = if (notes.isEmpty()) "" else "（" + notes.joinToString("；") + "）"

    return if (looksTruncated) {
        ParsedTranslations.Err(
            TranslationFailure.Truncated(
                finishReason = finishReason,
                estimatedTokens = estimated,
                maxTokens = maxTokens,
                detail = "返回被截断了：估算 $estimated tokens，上限 $maxTokens，" +
                    "finish_reason=$finishReason，JSON 是否未闭合=$unclosed$suffix",
            ),
        )
    } else {
        ParsedTranslations.Err(
            TranslationFailure.BadResponse(
                "模型没有按「$expectedCount 条译文」的结构返回。" +
                    "估算长度 $estimated tokens（上限 $maxTokens），finish_reason=$finishReason$suffix。" +
                    "返回正文前 120 字：" + text.take(120),
            ),
        )
    }
}

/**
 * 去掉 markdown 代码块围栏。
 *
 * 提示词里明确说了「不要代码块」，但模型有一半概率还是加上——
 * 那三个反引号会把 `scanTopLevelJsonValues` 的括号配对搞乱，
 * 所以这是**必须**做的一步，不是顺手做的美化。
 */
internal fun stripCodeFences(text: String): String {
    if (!text.contains("```")) return text
    return text.lines().filterNot { it.trimStart().startsWith("```") }.joinToString("\n")
}

/**
 * 扫描一段文本里所有**配对完整**的顶层 JSON 值。
 *
 * 逐字符走，维护「是否在字符串里 / 是否转义 / 括号深度」。
 * 括号没闭合的尾部会被丢掉（那正是被截断的部分），但前面完整的值都留下来了。
 */
internal fun scanTopLevelJsonValues(text: String): List<JsonElement> {
    val results = mutableListOf<JsonElement>()
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        if (ch != '{' && ch != '[') {
            index++
            continue
        }
        val end = findBalancedEnd(text, index) ?: break
        TranslationJson.parseElementOrNull(text.substring(index, end))?.let { results += it }
        index = end
    }
    return results
}

/** [start] 处是一个 `{`/`[`，返回它配对结束后的下标；没闭合返回 null。 */
internal fun findBalancedEnd(text: String, start: Int): Int? {
    var depth = 0
    var inString = false
    var escaped = false
    var index = start
    while (index < text.length) {
        val ch = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
        } else {
            when (ch) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        index++
    }
    return null
}

/**
 * 正文里存在一个没闭合的括号吗（= 被截断的迹象）。
 *
 * 必须**走完所有顶层括号**，不能看到第一个就下结论：模型给出
 * `{...} {...}` 这种多个顶层值时（提示词里明确禁止，但它有一半概率会这么干），
 * 被截断的多半是**后面那个**，只看第一个就会报"JSON 是闭合的"，
 * 于是明明是预算不够，却被归成形状错误——处置方向完全反了。
 */
internal fun hasUnclosedJson(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        if (ch != '{' && ch != '[') {
            index++
            continue
        }
        val end = findBalancedEnd(text, index) ?: return true
        index = end
    }
    return false
}

/**
 * 逐层往下找出「一串译文」。
 *
 * ## 单元素数组要钻进去，多元素数组绝不钻
 *
 * 模型有时会多包一层：要 `{"translations":[...]}`，它给 `[{"translations":[...]}]`。
 * 这只是**多了一层形状**，不是内容错，值得顺着钻（[MAX_JSON_DESCENT] 有上限）。
 * 但 `[第一卷译文, 第二卷译文]` 这种**多**元素数组绝不能钻——钻进去就等于
 * 把「半个答案」当成整个答案提交了，而且提交得悄无声息。
 */
private fun collectCandidates(
    element: JsonElement,
    depth: Int,
    out: MutableList<List<String>>,
    notes: MutableList<String>,
) {
    if (depth > MAX_JSON_DESCENT) {
        notes += "嵌套超过 $MAX_JSON_DESCENT 层"
        return
    }
    when (element) {
        is JsonArray -> {
            if (element.isEmpty()) {
                notes += "译文数组是空的"
                return
            }
            if (element.all { it.rawStringOrNull() != null }) {
                out += element.strings()
                return
            }
            if (element.size == 1) {
                collectCandidates(element[0], depth + 1, out, notes)
                return
            }
            val lines = element.map { objectToLine(it) }
            val missing = lines.count { it == null }
            if (missing == 0) {
                out += lines.map { it!! }
            } else {
                val keys = element.firstNotNullOfOrNull { it.objectOrNull()?.keys }
                notes += "数组有 ${element.size} 项，其中 $missing 项取不到译文" +
                    (keys?.let { "（字段名是 [${it.joinToString(",")}]）" } ?: "")
            }
        }

        is JsonObject -> {
            val key = TRANSLATION_KEYS.firstOrNull { element.containsKey(it) }
            if (key != null) {
                collectCandidates(element.getValue(key), depth + 1, out, notes)
                return
            }
            if (element.size == 1) {
                collectCandidates(element.values.first(), depth + 1, out, notes)
                return
            }
            objectToLine(element)?.let { line ->
                out += listOf(line)
                return
            }
            // 这一句就是「模型自己发明了字段名」那类失败的指纹：
            // 报错里列出的是**它**的键名，而不是我们期望的键名。
            notes += "对象的字段名是 [${element.keys.joinToString(",")}]，没有可识别的译文数组"
        }

        else -> {
            val single = element.stringOrNull().orEmpty()
            when {
                single.isBlank() -> notes += "内容是空字符串"
                single.contains('\n') ->
                    out += single.lines().map { stripLinePrefix(it) }.filter { it.isNotBlank() }

                else -> out += listOf(single)
            }
        }
    }
}

/**
 * 一个对象里「哪一段是译文」。
 *
 * 先按已知键名找；找不到再退回「整个对象里只有一个字符串值」——
 * 这能救回 `{"id":3,"zh":"你好"}` 这种自创键名的返回。
 */
private fun objectToLine(element: JsonElement): String? {
    val obj = element.objectOrNull() ?: return element.rawStringOrNull()
    for (key in TRANSLATION_KEYS) {
        obj[key].rawStringOrNull()?.let { return it }
    }
    val strings = obj.entries.filter { it.value.rawStringOrNull() != null }
    return if (strings.size == 1) strings.first().value.rawStringOrNull() else null
}

/**
 * 去掉行首的列表符号。
 *
 * ⚠️ 只在**纯文本兜底**路径上用。JSON 路径绝不能用它：
 * 字幕里 `- ` 是两个人在对话的标记（「- 回家。」），
 * 那个连字符是内容的一部分，去掉就把对白结构弄丢了。
 */
private val LINE_PREFIX = Regex("""^\s*(?:[-*•·]|\d{1,4}\s*[.、)）:：])\s*""")

internal fun stripLinePrefix(line: String): String = line.replace(LINE_PREFIX, "").trim()
