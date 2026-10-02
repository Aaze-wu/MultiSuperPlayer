package com.multisuperplayer.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 译文解析。
 *
 * ## 这个文件的重点不是"能不能解析成功"
 *
 * 而是**两类失败必须被分开**：「模型把结构答错了」和「预算不够被截断了」。
 * 前者要拆小批次/改提示词，后者要加大 max_tokens。如果两者共用一句
 * 「解析失败」，重试策略就永远在撞同一堵墙，而且看日志的人会一直去查提示词。
 */
class TranslationResponseParserTest {

    private fun ok(parsed: ParsedTranslations): List<String> =
        (parsed as ParsedTranslations.Ok).texts

    private fun err(parsed: ParsedTranslations): TranslationFailure =
        (parsed as ParsedTranslations.Err).failure

    @Test
    fun `标准形状`() {
        assertEquals(listOf("甲", "乙"), ok(parseTranslationPayload("""{"translations":["甲","乙"]}""", 2)))
    }

    @Test
    fun `裸数组`() {
        assertEquals(listOf("甲", "乙"), ok(parseTranslationPayload("""["甲","乙"]""", 2)))
    }

    @Test
    fun `其他常见键名`() {
        assertEquals(listOf("甲"), ok(parseTranslationPayload("""{"translation":["甲"]}""", 1)))
        assertEquals(listOf("甲"), ok(parseTranslationPayload("""{"lines":["甲"]}""", 1)))
        assertEquals(listOf("甲"), ok(parseTranslationPayload("""{"result":["甲"]}""", 1)))
    }

    @Test
    fun `多包一层单元素数组可以接受`() {
        // 模型有时会给出 [{...}]。这是**形状差一层**，不是内容错，
        // 硬性失败等于白白重试一次。
        assertEquals(listOf("甲", "乙"), ok(parseTranslationPayload("""[{"translations":["甲","乙"]}]""", 2)))
    }

    @Test
    fun `多元素数组不能被当成整批吞下去`() {
        // [{"...":["甲"]},{"...":["乙"]}] 看起来"每项都是一条译文"，
        // 但它更可能是模型把整批拆成了两卷——只取第一卷等于**静默交付一半的答案**。
        val failure = err(parseTranslationPayload("""[{"translations":["甲"]},{"translations":["乙"]}]""", 2))
        assertTrue(failure is TranslationFailure.BadResponse)
        assertTrue(failure.detail.contains("translations"), "报错应列出模型真实的字段名")
    }

    @Test
    fun `模型自己发明字段名时把它的字段名报出来`() {
        // 这类失败的指纹就是"报错里列的是**它**的键名"。
        // 报成「解析失败」的话，没人知道是模型换了字段还是我们键名写错了。
        val failure = err(
            parseTranslationPayload("""{"volume1":["甲","乙"],"volume2":["丙","丁"]}""", 2),
        )
        assertTrue(failure is TranslationFailure.BadResponse)
        assertTrue(failure.detail.contains("volume1"))
        assertTrue(failure.detail.contains("volume2"))
    }

    @Test
    fun `自创键名但只有一个字符串值时能救回来`() {
        assertEquals(listOf("你好"), ok(parseTranslationPayload("""{"id":3,"zh":"你好"}""", 1)))
    }

    @Test
    fun `去掉 markdown 代码块`() {
        val raw = "```json\n{\"translations\":[\"甲\"]}\n```"
        assertEquals(listOf("甲"), ok(parseTranslationPayload(raw, 1)))
        assertEquals("a\nb", stripCodeFences("```\na\nb\n```"))
        assertEquals("没有围栏", stripCodeFences("没有围栏"))
    }

    @Test
    fun `多个顶层值取形状对得上的那个`() {
        val raw = """{"translations":["甲"]} {"translations":["乙","丙"]} 以上是译文。"""
        assertEquals(listOf("乙", "丙"), ok(parseTranslationPayload(raw, 2)))
    }

    @Test
    fun `被截断时报截断而不是形状错`() {
        val raw = """{"translations":["甲","乙","丙","丁","戊"""
        // 判据一：finish_reason 明说 length。
        val byFinish = err(parseTranslationPayload(raw, 5, finishReason = "length", maxTokens = 4096))
        assertTrue(byFinish is TranslationFailure.Truncated, "finish_reason=length 应判为截断")

        // 判据二（独立）：括号没闭合 **且** 长度已接近上限。
        // 有些网关把 max_tokens 截断报成 stop，只看 finish_reason 会漏掉一半。
        val byLength = err(parseTranslationPayload(raw, 5, finishReason = "stop", maxTokens = estimatedCap(raw)))
        assertTrue(byLength is TranslationFailure.Truncated, "未闭合 + 接近上限也应判为截断")
    }

    @Test
    fun `预算充足时的形状错不会被误判成截断`() {
        // 这是那条"放宽的判据"的负面用例：没有它，判据会吞掉真正的结构 bug，
        // 然后每次都去把预算翻倍——白花钱，还永远修不好。
        val raw = """{"translations":["甲","乙","丙","丁","戊"""
        val failure = err(parseTranslationPayload(raw, 5, finishReason = "stop", maxTokens = 100_000))
        assertTrue(failure is TranslationFailure.BadResponse)

        // maxTokens 未知（0）时那条判据不成立，也只能报形状错。
        assertTrue(err(parseTranslationPayload(raw, 5)) is TranslationFailure.BadResponse)
    }

    @Test
    fun `条数不够时报形状错并说明差多少`() {
        val failure = err(parseTranslationPayload("""{"translations":["甲"]}""", 3, maxTokens = 2048))
        assertTrue(failure is TranslationFailure.BadResponse)
        assertTrue(failure.detail.contains("1"))
        assertTrue(failure.detail.contains("3"))
    }

    @Test
    fun `纯文本兜底`() {
        assertEquals(listOf("甲", "乙"), ok(parseTranslationPayload("甲\n乙", 2)))
        // 只有正文里完全没有任何括号时才走这条路：否则被截断的 JSON 会被
        // 当成纯文本凑出一堆残句，比直接失败糟得多。
        val cut = "{\"translations\":[\"甲\""
        val failure = err(parseTranslationPayload(cut, 1, finishReason = "length", maxTokens = 4096))
        assertTrue(failure is TranslationFailure.Truncated)
    }

    @Test
    fun `纯文本兜底会去掉列表符号`() {
        assertEquals(listOf("甲", "乙"), ok(parseTranslationPayload("- 甲\n- 乙", 2)))
        assertEquals("甲", stripLinePrefix("  • 甲 "))
        assertEquals("甲", stripLinePrefix("1. 甲"))
        assertEquals("甲", stripLinePrefix("1、甲"))
        assertEquals("甲", stripLinePrefix("甲"))
    }

    @Test
    fun `纯文本条数不符时说清楚`() {
        val failure = err(parseTranslationPayload("甲\n乙\n丙", 2))
        assertTrue(failure is TranslationFailure.BadResponse)
        assertTrue(failure.detail.contains("3"))
    }

    @Test
    fun `JSON 路径不丢对白连字符`() {
        // 字幕里 `- ` 是两个人对话的标记，是内容的一部分。
        // 在 JSON 路径上顺手 strip 掉，就把对白结构静默弄丢了。
        val texts = ok(parseTranslationPayload("""{"translations":["- 回家。","- 等等！"]}""", 2))
        assertEquals("- 回家。", texts[0])
    }

    @Test
    fun `空内容报形状错`() {
        assertTrue(err(parseTranslationPayload("   ", 1)) is TranslationFailure.BadResponse)
    }

    // ------------------------------------------------------------ 扫描工具

    @Test
    fun `括号配对能跳过字符串里的括号与转义`() {
        assertEquals(9, findBalancedEnd("""{"a":"}"}""", 0))
        assertEquals(10, findBalancedEnd("""{"a":"\""}""", 0))
        assertEquals(null, findBalancedEnd("""{"a":[1,2""", 0))
        assertEquals(null, findBalancedEnd("[", 0))
    }

    @Test
    fun `未闭合判定`() {
        assertTrue(!hasUnclosedJson("""{"a":1}"""))
        assertTrue(!hasUnclosedJson("没有括号"))
        assertTrue(hasUnclosedJson("""{"a":[1,2"""))
        // 多个顶层值时被截断的是后面那个：只看第一个就会漏掉。
        assertTrue(hasUnclosedJson("""{"a":"}"} """ + "["))
    }

    @Test
    fun `扫描只留完整值`() {
        val values = scanTopLevelJsonValues("""{"a":1} {"b":[2,3]} {"c":""")
        assertEquals(2, values.size)
        assertTrue(scanTopLevelJsonValues("没有 JSON").isEmpty())
    }

    /**
     * 反推一个「刚好够触发 90% 判据」的上限，避免测试里写死数字。
     *
     * 判据是 `estimated * 10 >= maxTokens * 9`，所以上限取 `estimated * 10 / 9`
     * （整数除法向下取整，正好落在成立的那一侧）。
     */
    private fun estimatedCap(raw: String): Int = estimateTokens(raw) * 10 / 9
}
