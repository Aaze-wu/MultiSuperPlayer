package com.multisuperplayer.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别文本清洗的测试。
 *
 * 这里每一个断言都对应一个「屏幕上会看到什么」的事实，不是覆盖率：
 *
 * - 占位符 `<unk>` 直接显示的话，静音段落会变成一条写着 `<unk>` 的字幕；
 * - zipformer 是按 token 输出的，汉字之间带空格，不清掉就是满屏飘空格；
 * - 强制切分把句子从中间切开之后，后半句会以「，」开头，而行首不放标点是字幕惯例。
 */
class AsrTextNormalizerTest {

    @Test
    fun `空白输入返回空串`() {
        assertEquals("", AsrTextNormalizer.normalize(""))
        assertEquals("", AsrTextNormalizer.normalize("   "))
        assertEquals("", AsrTextNormalizer.normalize("\n\t "))
    }

    @Test
    fun `占位符当成空`() {
        // 这些是识别器在「这段音频里没有可识别语音」时的输出，不同模型习惯不一样。
        // 不清掉就会出现写着 `<unk>` 的字幕——而它看起来像乱码，用户会以为程序坏了。
        listOf("<unk>", "</s>", "<s>", "<blank>", "(unk)", "[unk]", "unk").forEach { placeholder ->
            assertEquals("占位符 $placeholder 应该被清掉", "", AsrTextNormalizer.normalize(placeholder))
        }
    }

    @Test
    fun `占位符不分大小写`() {
        assertEquals("", AsrTextNormalizer.normalize("<UNK>"))
        assertEquals("", AsrTextNormalizer.normalize("<Unk>"))
        assertEquals("", AsrTextNormalizer.normalize("UNK"))
    }

    @Test
    fun `占位符与真文本混在一起时只留下真文本`() {
        assertEquals("你好", AsrTextNormalizer.normalize("<unk> 你好 <unk>"))
        assertEquals("你好世界", AsrTextNormalizer.normalize("你好 <unk> 世界"))
    }

    @Test
    fun `中文之间的空格被去掉，英文单词之间的保留`() {
        // zipformer 是逐 token 输出，汉字之间带空格；中文正字法里没有词间空格
        assertEquals(
            "我今天下午有一个 meeting",
            AsrTextNormalizer.normalize("我 今天 下午 有 一个 meeting"),
        )
        // 英文单词之间的空格是**内容**，不能被一起清掉
        assertEquals("hello world", AsrTextNormalizer.normalize("hello world"))
        assertEquals("你好 world", AsrTextNormalizer.normalize("你好 world"))
    }

    @Test
    fun `多余的空白被折叠成一个空格`() {
        assertEquals("a b", AsrTextNormalizer.normalize("a   b"))
        assertEquals("a b", AsrTextNormalizer.normalize("a\n\tb"))
        assertEquals("你好", AsrTextNormalizer.normalize("  你好  "))
    }

    @Test
    fun `sentencepiece 的词首标记不会漏到屏幕上`() {
        // sherpa 的 getText() 已经把它还原成空格了，这里是保险：
        // 漏出来的话屏幕上会出现「今天▁天气」这种字符
        assertEquals("hello world", AsrTextNormalizer.normalize("hello\u2581world"))
        // 中文场景下它还原成空格之后又被「汉字之间不留空格」那条规则吃掉
        assertEquals("今天天气", AsrTextNormalizer.normalize("今天\u2581天气"))
    }

    @Test
    fun `行首的标点被去掉，行尾的保留`() {
        // 强制切分把句子从中间切开，后半句的起头带着原来的标点
        assertEquals("今天天气不错", AsrTextNormalizer.normalize("，今天天气不错"))
        assertEquals("今天天气不错", AsrTextNormalizer.normalize(", 今天天气不错"))
        // 只有标点的结果等于空
        assertEquals("", AsrTextNormalizer.normalize("。！？"))
        // 注意：`trimStart` 删的是**行首那一段连续标点**，被空格隔开就停下。
        // 所以「， ……」留下的是「……」，不是空串。这不是漏掉了清理：
        // 它的归宿是 `isMeaningful` —— 纯标点做不成字幕，在生成那一步就被丢掉。
        assertEquals("……", AsrTextNormalizer.normalize("， ……"))
        assertFalse(AsrTextNormalizer.isMeaningful(AsrTextNormalizer.normalize("， ……")))
        // 行尾的句号是正常内容
        assertEquals("你好。", AsrTextNormalizer.normalize("你好。"))
    }

    @Test
    fun `行首的引号与括号保留`() {
        // 「他说「你好」」被切开之后，后半句以「开头是合理的
        assertEquals("「你好」", AsrTextNormalizer.normalize("「你好」"))
        assertEquals("（旁白）他笑了", AsrTextNormalizer.normalize("（旁白）他笑了"))
        // 标点只在**行首**被删：前面有引号时那个逗号是内容
        assertEquals("「，你好", AsrTextNormalizer.normalize("「，你好"))
    }

    @Test
    fun `汉字与全角标点之间也不留空格`() {
        assertEquals("你好！", AsrTextNormalizer.normalize("你好 ！"))
        assertEquals("你好，世界", AsrTextNormalizer.normalize("你好 ， 世界"))
    }

    @Test
    fun `扩展 B 区的汉字之间同样不留空格`() {
        // 用码点构造而不是字面量：扩展 B 区是代理对，按 Char 遍历会看到两个
        // 「非汉字」的半代理，于是罕见姓氏中间会留下一个空格——一个只在
        // 罕见人名上出现、看起来像「数据脏」的 bug
        val rare = String(Character.toChars(0x20000))
        assertEquals(rare + rare, AsrTextNormalizer.normalize("$rare $rare"))
    }

    @Test
    fun `日语的假名之间不留空格，假名与英文之间的保留`() {
        // 日语那条模型也是**逐字 token** 输出（官方示例里就是 "よ", "う", "呼", "び"）。
        // 假名没被算进「中日韩字符」的话，一条日语字幕会变成「こ ん に ち は」——
        // 那不是日语正字法，而屏幕上看不出是程序的问题还是模型的问题。
        assertEquals("こんにちは", AsrTextNormalizer.normalize("こ ん に ち は"))
        assertEquals("こんにちは世界", AsrTextNormalizer.normalize("こんにちは 世界"))
        assertEquals("今日はいい天気ですね", AsrTextNormalizer.normalize("今日 は いい 天気 です ね"))
        // 片假名区里除了假名还有长音符「ー」和中点「・」，它们跟着一起折叠
        assertEquals("ロボット・アニメーション", AsrTextNormalizer.normalize("ロボット ・ アニメーション"))

        // 但「假名和拉丁字母之间」的那个空格是**内容**：日文里夹英文单词时不能吃掉
        assertEquals("こんにちは world", AsrTextNormalizer.normalize("こんにちは world"))
        assertEquals("NHK のニュース", AsrTextNormalizer.normalize("NHK の ニュース"))
    }

    @Test
    fun `日语的行首标点同样被去掉`() {
        // 「。」「、」都在 LEADING_PUNCTUATION 里，规则不区分中日的句读
        assertEquals("今日はいい天気ですね", AsrTextNormalizer.normalize("。今日はいい天気ですね"))
        // 行尾的感叹号是正常内容
        assertEquals("美味しい！", AsrTextNormalizer.normalize("美味しい！"))
        // 只有假名结果的照样算有意义（假名是 Letter），这一点与中文一致
        assertTrue(AsrTextNormalizer.isMeaningful(AsrTextNormalizer.normalize("はい")))
    }

    @Test
    fun `isMeaningful 认字母和数字，不认纯标点`() {
        assertTrue(AsrTextNormalizer.isMeaningful("你好"))
        assertTrue(AsrTextNormalizer.isMeaningful("abc"))
        assertTrue(AsrTextNormalizer.isMeaningful("123"))
        assertTrue(AsrTextNormalizer.isMeaningful("？abc"))

        // 纯标点是识别器在噪声上最常见的输出，做成字幕就是一条空白的框
        assertFalse(AsrTextNormalizer.isMeaningful("！"))
        assertFalse(AsrTextNormalizer.isMeaningful("…"))
        assertFalse(AsrTextNormalizer.isMeaningful("。"))
        assertFalse(AsrTextNormalizer.isMeaningful(""))
        assertFalse(AsrTextNormalizer.isMeaningful("   "))
    }
}
