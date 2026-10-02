package com.multisuperplayer.core.translate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 提示词版本。
 *
 * **改了本文件里任何一个影响输出的字，都要把这个数字 +1**，否则旧缓存会被
 * 当成新提示词的结果复用，用户点了「重新翻译」却一条都没变——那看起来像按钮坏了。
 */
internal const val TRANSLATION_PROMPT_VERSION = 1

/**
 * 系统提示词。
 *
 * ## 为什么 JSON Schema 写在**提示词里**而不是靠 `response_format`
 *
 * `response_format: {"type": "json_object"}` 只保证「返回的是合法 JSON」，
 * **不保证字段名**——schema 根本没发给模型。实测：请求里写 `{premise, themes}`，
 * 模型返回 `{title, logline, worldRules…}`，一个字段都对不上。
 * 所以这里把期望的**具体结构**写成自然语言 + 示例（也是唯一在各种厂商都生效的做法），
 * 解析器那边再兜底。
 */
internal fun buildSystemPrompt(target: TranslationTarget, glossary: Glossary): String {
    val protector = GlossaryProtector(glossary)
    val fixed = glossary.fixedTranslations()

    return buildString {
        append("你是专业的影视字幕翻译。把用户给出的字幕逐行翻译成")
        append(target.promptName)
        append("。\n\n")

        append("## 输出格式（最重要）\n")
        append("- 只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字。\n")
        append("- 形状固定为：{\"translations\": [\"第 1 行\", \"第 2 行\", ...]}\n")
        append("- translations 数组的长度必须**正好等于**输入的行数，顺序一一对应。\n")
        append("- 输入有 N 行就输出 N 条，**不许合并、不许拆开、不许省略、不许补空行**。\n")
        append("- 每一条只放译文本身，不要带行号、不要带引号、不要带时间轴。\n\n")

        append("## 翻译要求\n")
        append("- 口语化、自然，像官译字幕；不要逐字硬译，不要机翻腔。\n")
        append("- 保持原有的语气与人物说话习惯（敬语、粗口、口癖都要留住）。\n")
        append("- 歌词行按歌词处理，可以意译。\n")
        append("- 保留原文里的数字、时间、符号、以及说话人标记（如「- 」）。\n")
        append("- 一行里如果是两个人在对话（原文以「- 」分段），译文也要保持同样的分段。\n")

        protector.promptRules().takeIf { it.isNotEmpty() }?.let {
            append("\n## 占位符\n")
            append(it)
        }

        if (fixed.isNotEmpty()) {
            append("\n## 术语表（必须遵守）\n")
            for ((source, target2) in fixed) {
                append("- `").append(source).append("` 一律译作 `").append(target2).append("`\n")
            }
        }

        append("\n## 示例\n")
        append("输入：{\"lines\": [\"Where are you going?\", \"- Home.\\n- Wait!\"]}\n")
        append("输出：{\"translations\": [\"你要去哪儿？\", \"- 回家。\\n- 等等！\"]}\n")
    }
}

/**
 * 用户提示词。
 *
 * 输入也发 JSON 而不是裸文本：裸文本靠换行分行，模型常常把某行「自然地」接进上一行，
 * 返回条数就对不上了；而 `{"lines": [...]}` 里每一行都有明确的边界，
 * 再加上输出也是数组，两边结构对称，对齐关系一目了然。
 */
internal fun buildUserPrompt(batch: TranslationBatch, protectedTexts: List<String>): String {
    val payload = JsonObject(
        mapOf(
            "context_before" to JsonArray(batch.contextBefore.map { JsonPrimitive(it) }),
            "lines" to JsonArray(protectedTexts.map { JsonPrimitive(it) }),
        ),
    )
    return buildString {
        if (batch.contextBefore.isNotEmpty()) {
            append("context_before 是上文，**不要翻译它**，只用它来理解指代。\n")
        }
        append("lines 共 ")
        append(protectedTexts.size)
        append(" 行，请输出 ")
        append(protectedTexts.size)
        append(" 条译文。\n")
        append(payload)
    }
}
