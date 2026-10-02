package com.multisuperplayer.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 术语表。
 *
 * 这里的断言集中在**两个语义必须分得开**上：「不翻译」是确定性的（哨兵保护，
 * 与模型行为无关），「固定译法」只是提示词偏好。混成一个标志位，
 * 就会出现「用户明明设了不翻译，译文里还是被音译了」这种没法解释的现象。
 */
class GlossaryTest {

    private val glossary: Glossary = mapOf(
        "桐人" to "",              // 空值 ⇒ 不翻译
        "Kirito" to "Kirito",     // 与 key 相同 ⇒ 不翻译
        "Guild" to "公会",         // 有别的译法 ⇒ 固定译法
        "  " to "x",              // 空 key ⇒ 整条丢掉
    )

    @Test
    fun `空值或与原文相同视为不翻译`() {
        val terms = glossary.doNotTranslateTerms()
        assertTrue("桐人" in terms)
        assertTrue("Kirito" in terms)
        assertFalse("Guild" in terms)
        assertEquals(2, terms.size)
    }

    @Test
    fun `其余为固定译法`() {
        assertEquals(listOf("Guild" to "公会"), glossary.fixedTranslations())
    }

    @Test
    fun `同一份表用两种问法都自洽`() {
        // 一条目不可能同时是「不翻译」和「固定译法」，否则管线会做出矛盾的事。
        val both = glossary.doNotTranslateTerms().toSet() intersect
            glossary.fixedTranslations().map { it.first }.toSet()
        assertTrue(both.isEmpty())
    }

    @Test
    fun `指纹与插入顺序无关`() {
        val a = mapOf("桐人" to "", "Guild" to "公会")
        val b = mapOf("Guild" to "公会", "桐人" to "")
        assertEquals(a.fingerprint(), b.fingerprint())
    }

    @Test
    fun `指纹会随术语内容变化`() {
        val base = mapOf("Guild" to "公会")
        assertTrue(base.fingerprint() != mapOf("Guild" to "行会").fingerprint())
        assertTrue(base.fingerprint() != mapOf("Guild" to "公会", "桐人" to "").fingerprint())
        // 用户加完术语表点重翻，必须真的重翻——否则界面上看起来就是"点了没反应"。
        assertEquals("none", emptyMap<String, String>().fingerprint())
        assertEquals(12, base.fingerprint().length)
    }

    @Test
    fun `指纹忽略首尾空白`() {
        assertEquals(
            mapOf("Guild" to "公会").fingerprint(),
            mapOf(" Guild " to " 公会 ").fingerprint(),
        )
    }

    @Test
    fun `编解码往返`() {
        val text = encodeGlossary(glossary)
        assertEquals(glossary, decodeGlossary(text))
    }

    @Test
    fun `坏内容读成空表而不是抛异常`() {
        assertEquals(emptyMap(), decodeGlossary(null))
        assertEquals(emptyMap(), decodeGlossary(""))
        assertEquals(emptyMap(), decodeGlossary("不是 JSON"))
        assertEquals(emptyMap(), decodeGlossary("[1,2]"))
    }

    // ------------------------------------------------------------ 哨兵保护

    @Test
    fun `不翻译的词被替换成占位符且能原样还原`() {
        val protector = GlossaryProtector(mapOf("桐人" to "", "Kirito" to "Kirito"))
        val source = "桐人和 Kirito 一起打怪"
        val protected = protector.protect(source)

        assertFalse(protected.contains("桐人"))
        assertFalse(protected.contains("Kirito"))
        assertTrue(protected.contains(PLACEHOLDER_OPEN))
        assertEquals(source, protector.restore(protected))
    }

    @Test
    fun `固定译法不进哨兵保护`() {
        // 硬替换会造出「公会长」这种本来是「会长」的东西；模型看得懂上下文。
        val protector = GlossaryProtector(mapOf("Guild" to "公会"))
        assertTrue(protector.isEmpty)
        assertEquals("Guild 里", protector.protect("Guild 里"))
    }

    @Test
    fun `长词优先替换`() {
        val protector = GlossaryProtector(mapOf("Kirito" to "", "Kirito Swordsman" to ""))
        val protected = protector.protect("Kirito Swordsman 很强")
        // 顺序错了会先吃掉 "Kirito"，剩下的 " Swordsman" 就裸露在译文里了。
        assertFalse(protected.contains("Swordsman"))
        assertEquals(1, protected.count { it == PLACEHOLDER_OPEN })
        assertEquals("Kirito Swordsman 很强", protector.restore(protected))
    }

    @Test
    fun `占位符编号只由一张表决定`() {
        // 两张各自排序的表会让编号错开，还原时静默换上另一个人的名字。
        val protector = GlossaryProtector(mapOf("a" to "", "bb" to ""))
        assertEquals("${PLACEHOLDER_OPEN}0${PLACEHOLDER_CLOSE} 和 ${PLACEHOLDER_OPEN}1${PLACEHOLDER_CLOSE}",
            protector.protect("bb 和 a"))
    }

    @Test
    fun `大小写不敏感替换`() {
        val protector = GlossaryProtector(mapOf("Excalibur" to ""))
        val protected = protector.protect("excalibur 与 EXCALIBUR")
        assertEquals(2, protected.count { it == PLACEHOLDER_OPEN })
        // 已知取舍：还原的是术语表里的规范写法，不是原文的大小写。
        // 用「不翻译」保护的是这个词本身，不是它的排版。
        assertEquals("Excalibur 与 Excalibur", protector.restore(protected))
    }

    @Test
    fun `正则元字符按字面匹配`() {
        val protector = GlossaryProtector(mapOf("A.C" to ""))
        val protected = protector.protect("A.C 和 ABC")
        assertEquals("${PLACEHOLDER_OPEN}0${PLACEHOLDER_CLOSE} 和 ABC", protected)
    }

    @Test
    fun `空术语表不做任何替换`() {
        val protector = GlossaryProtector(emptyMap())
        assertTrue(protector.isEmpty)
        assertEquals("你好", protector.protect("你好"))
        assertEquals("", protector.promptRules())
    }

    @Test
    fun `有术语表时会写进提示词规则`() {
        val protector = GlossaryProtector(mapOf("桐人" to ""))
        val rules = protector.promptRules()
        assertTrue(rules.contains(PLACEHOLDER_OPEN))
        assertTrue(rules.isNotBlank())
    }

    @Test
    fun `提示词里同时含两类术语`() {
        val prompt = buildSystemPrompt(TranslationTarget.SIMPLIFIED_CHINESE, glossary)
        assertTrue(prompt.contains("简体中文"))
        assertTrue(prompt.contains("translations"))
        // 固定译法：原文和译文都要写进去，否则模型不知道照着哪个译。
        assertTrue(prompt.contains("Guild"), "固定译法的原文要出现")
        assertTrue(prompt.contains("公会"), "固定译法的译文要出现")
        // 不翻译的词**不能**出现在提示词里：它走的是占位符哨兵，
        // 原文一旦也发过去，模型就有机会「顺手把它翻了」——那就不是确定性保护了。
        assertFalse(prompt.contains("桐人"), "不翻译的词不该进提示词")
        assertTrue(prompt.contains(PLACEHOLDER_OPEN), "不翻译的词要靠占位符规则交代")
    }

    @Test
    fun `还原是幂等的`() {
        val protector = GlossaryProtector(mapOf("桐人" to ""))
        val once = protector.restore(protector.protect("桐人"))
        assertEquals(once, protector.restore(once))
    }

    @Test
    fun `没有占位符时还原不改动文本`() {
        val protector = GlossaryProtector(mapOf("桐人" to ""))
        assertEquals("完全没有术语的句子", protector.restore("完全没有术语的句子"))
    }
}
