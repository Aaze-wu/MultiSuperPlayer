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
internal const val TRANSLATION_PROMPT_VERSION = 2

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
        val example = exampleFor(target)
        append("输入：").append(example.input).append('\n')
        append("输出：").append(example.output).append('\n')
    }
}

/**
 * 一组示例：`输入：` 与 `输出：` 后面跟什么。
 *
 * 写成两个字段而不是一对 `Pair`，是因为它在提示词里出现两次且必须成对：
 * 两个无名参数很容易在改动时把顺序写反，而“输入/输出对调”的提示词依旧是一段
 * 语法完美的文本，只是把模型往反方向带。
 */
private class PromptExample(val input: String, val output: String)

/**
 * 默认的示例输入：两行英语，第二行是两个人在对话（带「- 」分段）。
 *
 * 抽成常量而不是在各个分支里各写一遍：这串东西在源码里的写法（`\"` `\\n`）
 * 和它在提示词里真正的样子差得远，复制十几次早晚会有一处不一样。
 */
private const val EXAMPLE_INPUT_ENGLISH = "{\"lines\": [\"Where are you going?\", \"- Home.\\n- Wait!\"]}"

/** 目标语言是英语时用的源文（中文），道理见下。 */
private const val EXAMPLE_INPUT_CHINESE = "{\"lines\": [\"你去哪儿？\", \"- 回家。\\n- 等等！\"]}"

/**
 * 取该目标语言的示例。
 *
 * ## 为什么示例必须和 `target` 一致
 *
 * 原来这里只有一组「英文→简体中文」的硬编码示例。选日语时，提示词里会同时出现
 * 「把字幕逐行翻译成日本語」和一条输出中文的示例——**自相矛盾**。
 * 模型会跟着示例走（它比要求那句话更具体、更可模仿），于是日语目标得到中文译文，
 * 而且因为语法上一切正常，没有任何错误可查。
 *
 * ## 为什么用 `when` 穷举而不是查表
 *
 * `when` 过了枚举全部分支就是个编译期检查：以后新增一门目标语言时，编译器会直接在
 * 这里报错，逼着补一条示例；查表（`mapOf(...).getOrDefault(默认)`）只会静默地
 * 沿用别人的语言，又一次把矛盾写进提示词。
 *
 * 输入那侧默认用**英语**（模型对“英语作源”最稳），只有目标语言就是英语时才反过来
 * 用中文——否则会出现「英文翻成英文」这种什么也没示范的示例。
 */
private fun exampleFor(target: TranslationTarget): PromptExample = when (target) {
    TranslationTarget.SIMPLIFIED_CHINESE -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        output = "{\"translations\": [\"你要去哪儿？\", \"- 回家。\\n- 等等！\"]}",
    )

    TranslationTarget.TRADITIONAL_CHINESE -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        output = "{\"translations\": [\"你要去哪裡？\", \"- 回家。\\n- 等等！\"]}",
    )

    // 目标语言是英语，所以源语言换成中文：拿英文当输入去示范「翻成英文」等于什么都没示范。
    TranslationTarget.ENGLISH -> PromptExample(
        input = EXAMPLE_INPUT_CHINESE,
        output = "{\"translations\": [\"Where are you going?\", \"- Home.\\n- Wait!\"]}",
    )

    // 日语目标。例子里故意带上「- 」双人对话与句末「。」，
    // 因为日语字幕的这两处习惯（说话人标记、句号）是模型最容易跟着英文改掉的地方。
    TranslationTarget.JAPANESE -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        output = "{\"translations\": [\"どこへ行くの？\", \"- 家に帰る。\\n- ちょっと待って！\"]}",
    )

    TranslationTarget.KOREAN -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        output = "{\"translations\": [\"어디 가는 거야?\", \"- 집에 가.\\n- 잠깐만!\"]}",
    )

    // 以下 10 门是 v0.6.6 补上的。每一门都刻意保留一个**该语言特有的书写习惯**，
    // 而不是把英文示例逐字换成目标语言：这些细节（法语问号前的空格、阿拉伯语的 ？、
    // 泰语不用句号也不分词、西语的倒问号）是模型最容易「顺手改成英文习惯」的地方，
    // 而那一改不报错、不崩溃，只是字幕看起来像机翻。
    TranslationTarget.RUSSIAN -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        // 西里尔字母：源文是拉丁字母，译文必须是西里尔——分词用空格，保持英文的句号。
        output = "{\"translations\": [\"Куда ты идёшь?\", \"- Домой.\\n- Подожди!\"]}",
    )

    TranslationTarget.SPANISH -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        // 西语的问句要带**倒问号**开头的 ¿，这是最容易被漏掉的一处。
        output = "{\"translations\": [\"¿A dónde vas?\", \"- A casa.\\n- ¡Espera!\"]}",
    )

    TranslationTarget.FRENCH -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        // 法语在 ? ! 前要留一个空格（英文不留）——这一格是对齐错误时最好认的信号。
        output = "{\"translations\": [\"Où vas-tu ?\", \"- À la maison.\\n- Attends !\"]}",
    )

    TranslationTarget.GERMAN -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        // 德语名词首字母大写，句子结构与英语相反（疑问句不倒装）。
        output = "{\"translations\": [\"Wohin gehst du?\", \"- Nach Hause.\\n- Warte!\"]}",
    )

    TranslationTarget.PORTUGUESE -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        output = "{\"translations\": [\"Aonde você vai?\", \"- Para casa.\\n- Espera!\"]}",
    )

    TranslationTarget.ITALIAN -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        output = "{\"translations\": [\"Dove vai?\", \"- A casa.\\n- Aspetta!\"]}",
    )

    TranslationTarget.ARABIC -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        // 阿拉伯语用 ؟（U+061F）当问号，而且整句从右往左。
        // 字幕渲染那一层不管方向（Compose 自己按字符集判断），所以示例里
        // 写成逻辑顺序即可，切勿为了看着顺手把词序反过来。
        output = "{\"translations\": [\"إلى أين تذهب؟\", \"- إلى المنزل.\\n- انتظر!\"]}",
    )

    TranslationTarget.THAI -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        // 泰语不用句号，词与词之间也不加空格：译文里出现「。」或「 .」
        // 就是模型在把英文标点搬过来。
        output = "{\"translations\": [\"คุณจะไปไหน\", \"- กลับบ้าน\\n- เดี๋ยวก่อน\"]}",
    )

    TranslationTarget.VIETNAMESE -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        // 越南语用拉丁字母加声调符：没有声调符的译文看着像英语，
        // 而那种「缺声调」的错法在所有语言里都是最普遍的一种。
        output = "{\"translations\": [\"Bạn đi đâu đấy?\", \"- Về nhà.\\n- Đợi đã!\"]}",
    )

    TranslationTarget.INDONESIAN -> PromptExample(
        input = EXAMPLE_INPUT_ENGLISH,
        output = "{\"translations\": [\"Kamu mau ke mana?\", \"- Pulang.\\n- Tunggu!\"]}",
    )
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
