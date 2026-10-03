package com.multisuperplayer.core.translate

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词组装的测试。
 *
 * 这里只钉一件事：**示例必须跟着目标语言走**。
 *
 * 为什么值得单开一个文件：示例是提示词里**唯一具体的**部分，而要求那部分（「翻译成
 * 日本語」）是抽象的。模型跟的是示例——它是照着抄的。所以「要求说日语、示例写中文」
 * 不会报错、不会解析失败，只会让日语目标稳定地得到中文译文，看起来像**模型能力不行**。
 * 这种失败没有任何症状可查，只能靠断言把它钉住。
 */
class TranslationPromptTest {

    /**
     * 一条目标语言的期望：示例输出里**必须**出现的词，和绝不能出现的别的语言。
     *
     * [forbidden] 才是重点：只断言「日语输出里有日语」的话，一条同时含中文的示例
     * 也能通过。
     */
    private class Expectations(val required: String, val forbidden: List<String>)

    /**
     * 各语言的招牌词：示例输出里**必须**出现的那一串。
     *
     * 15 门语言意味着「不许出现别的语言」有 15×14 种组合。手写这份清单的话，
     * 每加一门语言就要回头改 14 条既有条目——而漏掉的那些组合恰好是没人会去
     * 手动验证的（谁会在选了俄语之后逐字确认示例里没有意大利语）。
     * 所以只维护这一列招牌词，禁忌表由它自己推出来。
     *
     * 招牌词也必须**两两不同**，否则「示例用了别的语言」这个检查会自相矛盾。
     *
     * 注意英语那一条：它的**输入**示例是中文（拿英语当源去示范「翻成英语」等于什么
     * 都没示范），而下面的检查只看输出行，所以输入串不会撞上禁忌表。
     */
    private val signatures = mapOf(
        TranslationTarget.SIMPLIFIED_CHINESE to "你要去哪儿？",
        TranslationTarget.TRADITIONAL_CHINESE to "你要去哪裡？",
        TranslationTarget.ENGLISH to "Where are you going?",
        TranslationTarget.JAPANESE to "どこへ行くの？",
        TranslationTarget.KOREAN to "어디 가는 거야?",
        TranslationTarget.RUSSIAN to "Куда ты идёшь?",
        TranslationTarget.SPANISH to "¿A dónde vas?",
        TranslationTarget.FRENCH to "Où vas-tu ?",
        TranslationTarget.GERMAN to "Wohin gehst du?",
        TranslationTarget.PORTUGUESE to "Aonde você vai?",
        TranslationTarget.ITALIAN to "Dove vai?",
        TranslationTarget.ARABIC to "إلى أين تذهب؟",
        TranslationTarget.THAI to "คุณจะไปไหน",
        TranslationTarget.VIETNAMESE to "Bạn đi đâu đấy?",
        TranslationTarget.INDONESIAN to "Kamu mau ke mana?",
    )

    /** 见 [signatures]：禁忌 = 其余所有语言的招牌词。 */
    private val expectations = signatures.entries.associate { (target, required) ->
        target to Expectations(
            required = required,
            forbidden = signatures.filterKeys { it != target }.values.toList(),
        )
    }

    /** 取提示词里「输入：」或「输出：」那一行的**内容**（不含前缀）。 */
    private fun exampleLine(prompt: String, prefix: String): String =
        prompt.lineSequence().first { it.startsWith(prefix) }.removePrefix(prefix)

    @Test
    fun `这个清单覆盖了全部目标语言`() {
        // 加了新语言却没人在这里加一条的话，上面那组断言就会对它静默通过——
        // 「示例和目标语言打架」这个 bug 会原封不动回到提示词里。
        assertEquals(
            "新增目标语言时必须在这里补一条示例期望",
            TranslationTarget.entries.toSet(),
            expectations.keys,
        )
    }

    @Test
    fun `招牌词两两不同`() {
        // 禁忌表是由招牌词推出来的，所以两个语言共用同一串招牌词会让检查自我矛盾：
        // 无论示例写哪一种，它都必然「含另一种语言的词」，而报出来的错看起来像
        // 示例写错了。这条先把这个前提钉住，免得以后的报错指错方向。
        val all = signatures.values

        assertEquals("有两门语言的招牌词一模一样", all.size, all.toSet().size)
    }

    @Test
    fun `示例的输出用的一定是那种语言`() {
        expectations.forEach { (target, expectation) ->
            val output = exampleLine(buildSystemPrompt(target, emptyMap()), "输出：")

            assertTrue(
                "$target 的示例输出里找不到「${expectation.required}」：$output",
                output.contains(expectation.required),
            )
            expectation.forbidden.forEach { other ->
                assertFalse(
                    "$target 的示例里写出了另一种语言（$other）。模型会跟着示例走，" +
                        "于是「翻译成 X」这句话说了等于没说：$output",
                    output.contains(other),
                )
            }
        }
    }

    @Test
    fun `示例本身是合法 JSON，而且形状就是要求模型输出的那个形状`() {
        // 示例是模型唯一能照着抄的东西。里面少一个引号、多一个逗号，模型就会学成
        // 「不带引号的数组」「带尾逗号的对象」——而解析那边会因此一条都取不到。
        // 用我们自己那个宽松解析器来查：模型照抄的产物正是由它来读的。
        TranslationTarget.entries.forEach { target ->
            val prompt = buildSystemPrompt(target, emptyMap())

            val input = TranslationJson.parseObjectOrNull(exampleLine(prompt, "输入："))
            assertNotNull("$target 的示例输入不是合法 JSON", input)
            val output = TranslationJson.parseObjectOrNull(exampleLine(prompt, "输出："))
            assertNotNull("$target 的示例输出不是合法 JSON", output)

            val lines = input?.get("lines")?.jsonArray
            assertNotNull("$target 的示例输入里没有 lines 数组", lines)
            val translations = output?.get("translations")?.jsonArray
            assertNotNull("$target 的示例输出里没有 translations 数组", translations)

            assertEquals(
                "$target 的示例里输入和输出的条数不一致——模型会照抄这个比例",
                lines?.size,
                translations?.size,
            )
        }
    }

    @Test
    fun `示例会示范「一行里两个人对话」要保持分段`() {
        // 「- Home.\n- Wait!」这个例子是刻意留的：一行里两个人说话时怎么分段，
        // 是字幕里最容易被模型「顺手合并」的地方（合并之后时间轴还对得上，
        // 只是那一行会变成两个人在同一个字幕框里说话）。
        // 所以第二行必须是「- X\n- Y」，而且译文那边也要照做。
        TranslationTarget.entries.forEach { target ->
            val prompt = buildSystemPrompt(target, emptyMap())
            val lines = TranslationJson.parseObjectOrNull(exampleLine(prompt, "输入："))
                ?.get("lines")?.jsonArray
            val translations = TranslationJson.parseObjectOrNull(exampleLine(prompt, "输出："))
                ?.get("translations")?.jsonArray

            val source = lines?.get(1)?.jsonPrimitive?.content
            assertTrue(
                "$target 的示例输入第二行不是双人对话：$source",
                source != null && source.startsWith("- ") && source.contains('\n'),
            )
            val translated = translations?.get(1)?.jsonPrimitive?.content
            assertTrue(
                "$target 的示例译文第二行没保住分段：$translated",
                translated != null && translated.startsWith("- ") && translated.contains('\n'),
            )
        }
    }

    @Test
    fun `术语表仍然会进提示词`() {
        // 这条不在这个文件的目标里，但它是提示词里唯一「用户直接指定」的约束，
        // 而它排在示例前面——重排示例那段时很容易连它一起动掉。
        val prompt = buildSystemPrompt(TranslationTarget.JAPANESE, mapOf("Guild" to "ギルド"))

        assertTrue(prompt.contains("## 术语表（必须遵守）"))
        assertTrue(prompt.contains("`Guild`"))
        assertTrue(prompt.contains("`ギルド`"))
    }
}
