package com.multisuperplayer.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 术语表文本的读写。
 *
 * 这一层的价值全在「用户手写坏了会怎样」：多一个空格、用中文输入法打出全角等号、
 * 顺手写一行注释、末尾补一条纠正。这些都不会报错，只会静默地少一条或多一条，
 * 而症状是「术语表看起来没生效」。
 */
class GlossaryTextTest {

    @Test
    fun `只有左边的词是不翻译`() {
        assertEquals(mapOf("桐人" to ""), parseGlossary("桐人"))
    }

    @Test
    fun `等号两边是固定译法`() {
        assertEquals(mapOf("Guild" to "公会"), parseGlossary("Guild = 公会"))
    }

    @Test
    fun `全角等号也算等号`() {
        // 中文输入法下 `=` 常常打出 `＝`，肉眼几乎看不出区别。
        assertEquals(mapOf("Guild" to "公会"), parseGlossary("Guild ＝ 公会"))
    }

    @Test
    fun `分隔符取第一个，译文里带等号不会被截断`() {
        assertEquals(mapOf("k" to "a=b"), parseGlossary("k = a=b"))
    }

    @Test
    fun `注释和空行会被跳过`() {
        val text = """
            # 这是一行注释
            桐人

            // 另一种注释
            Guild = 公会
        """.trimIndent()
        assertEquals(mapOf("桐人" to "", "Guild" to "公会"), parseGlossary(text))
    }

    @Test
    fun `同一个词写两次时后写的赢`() {
        // 用户不会回去删旧的那条，他会在末尾补一条纠正。
        assertEquals(mapOf("Guild" to "公会"), parseGlossary("Guild = 行会\nGuild = 公会"))
    }

    @Test
    fun `左边是空的条目直接丢掉`() {
        assertEquals(emptyMap(), parseGlossary("= 公会"))
    }

    @Test
    fun `空文本解析出空表`() {
        assertEquals(emptyMap(), parseGlossary(""))
    }

    @Test
    fun `首尾空格会被去掉`() {
        assertEquals(mapOf("Guild" to "公会"), parseGlossary("  Guild   =   公会  "))
    }

    @Test
    fun `不翻译的词写回去时不带等号`() {
        // 输出成 `桐人 = 桐人` 虽然等价，但用户下次看到会以为那是固定译法。
        assertEquals("桐人", formatGlossary(mapOf("桐人" to "")))
    }

    @Test
    fun `写回去时省略式的不翻译也算不翻译`() {
        assertEquals("桐人", formatGlossary(mapOf("桐人" to "桐人")))
    }

    @Test
    fun `固定译法写回成等号形式`() {
        assertEquals("Guild = 公会", formatGlossary(mapOf("Guild" to "公会")))
    }

    @Test
    fun `往返一遍不丢内容`() {
        val glossary = mapOf("桐人" to "", "Guild" to "公会", "Excalibur" to "Excalibur")

        val once = parseGlossary(formatGlossary(glossary))

        // 比的是**语义**而不是那张表：`Excalibur = Excalibur` 会被写成裸词
        // `Excalibur`（两者都是「不翻译」），所以 map 本身并不逐字相同。
        assertEquals(glossary.doNotTranslateTerms().sorted(), once.doNotTranslateTerms().sorted())
        assertEquals(
            glossary.fixedTranslations().sortedBy { it.first },
            once.fixedTranslations().sortedBy { it.first },
        )
        // 而且必须幂等：再往返一遍完全一样，否则每存一次都会「多出一行」。
        assertEquals(once, parseGlossary(formatGlossary(once)))
    }

    @Test
    fun `解析出来的结果直接就是引擎认的那两种`() {
        val glossary = parseGlossary("桐人\nGuild = 公会")

        assertEquals(listOf("桐人"), glossary.doNotTranslateTerms())
        assertEquals(listOf("Guild" to "公会"), glossary.fixedTranslations())
        assertTrue(glossary.fingerprint() != emptyMap<String, String>().fingerprint())
    }
}
