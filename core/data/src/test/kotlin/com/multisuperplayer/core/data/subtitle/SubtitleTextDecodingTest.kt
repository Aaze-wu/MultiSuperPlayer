package com.multisuperplayer.core.data.subtitle

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕文件编码探测测试。
 *
 * 这一步的错误症状全都一样：「字幕是乱码」。所以每种编码都得有一条**真的用
 * 该编码写出字节**的测试，而不是构造一个看起来像的字符串——编码问题只存在于
 * 字节层面，字符串层面的测试一个也证明不了。
 */
class SubtitleTextDecodingTest {

    private val sample = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界\n"

    /** 带假名的日语，用来区分「真的日语」和「能解码但其实是乱码」。 */
    private val japaneseSample = "1\n00:00:01,000 --> 00:00:02,000\nこんにちは、世界。今日はいい天気ですね。\n"

    @Test
    fun `纯 UTF-8 内容按 UTF-8 解出`() {
        val decoded = SubtitleTextDecoding.decode(sample.toByteArray(Charsets.UTF_8))

        assertEquals("UTF-8", decoded.charset)
        assertFalse("能严格解出来就不是猜的", decoded.guessed)
        assertEquals(sample, decoded.text)
    }

    @Test
    fun `带 BOM 的 UTF-8 不会把 BOM 留在文本里`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val decoded = SubtitleTextDecoding.decode(bom + sample.toByteArray(Charsets.UTF_8))

        assertEquals("UTF-8", decoded.charset)
        assertFalse(decoded.guessed)
        assertTrue("BOM 必须被吃掉", decoded.text.startsWith("1\n"))
    }

    @Test
    fun `带 BOM 的 UTF-16LE 能正确解出`() {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val decoded = SubtitleTextDecoding.decode(bom + sample.toByteArray(Charsets.UTF_16LE))

        assertEquals("UTF-16LE", decoded.charset)
        assertEquals(sample, decoded.text)
    }

    @Test
    fun `带 BOM 的 UTF-16BE 能正确解出`() {
        val bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        val decoded = SubtitleTextDecoding.decode(bom + sample.toByteArray(Charsets.UTF_16BE))

        assertEquals("UTF-16BE", decoded.charset)
        assertEquals(sample, decoded.text)
    }

    @Test
    fun `没有 BOM 的 UTF-16 也必须认出来`() {
        // NUL 字节本身是合法 UTF-8，所以严格 UTF-8 解码会「成功」——
        // 如果探测顺序把 UTF-8 放在前面，这里会得到一份夹着 \u0000 的垃圾文本，
        // 而 guessed 会是 false，所有上层防线都认为解码没问题。
        val decoded = SubtitleTextDecoding.decode(sample.toByteArray(Charsets.UTF_16LE))

        assertEquals("UTF-16LE", decoded.charset)
        assertEquals(sample, decoded.text)
        assertTrue("没有 BOM 属于「猜的」，要告诉上层", decoded.guessed)
        assertFalse("文本里不该留下 NUL", decoded.text.contains('\u0000'))
    }

    @Test
    fun `GBK 内容能解出正确的中文且如实标记为猜测`() {
        val bytes = sample.toByteArray(Charset.forName("GBK"))
        val decoded = SubtitleTextDecoding.decode(bytes)

        assertEquals(sample, decoded.text)
        assertTrue("GB18030 是兜底，用户有权知道", decoded.guessed)
        // 中文走兜底，不能被 Shift-JIS 那一支抢走，否则解出来的是满屏半角片假名
        assertEquals("GB18030", decoded.charset)
    }

    @Test
    fun `Shift-JIS 的日语字幕能正确解出，且报出来的字符集名是 Shift-JIS`() {
        // 日语字幕的主流编码，也是最容易静静碎掉的一支：Shift-JIS 的字节序列
        // 几乎全都落在 GB18030 的合法范围里，所以「能解」本身没有信息量——
        // 不管它可以得到一份「合法的乱码」，而它不是异常，是看上去正常的字幕。
        val bytes = japaneseSample.toByteArray(Charset.forName("windows-31j"))
        val decoded = SubtitleTextDecoding.decode(bytes)

        assertEquals("应该解出原文，而不是能解码的乱码", japaneseSample, decoded.text)
        assertTrue("字节里没 BOM、也不是合法 UTF-8，属于猜的", decoded.guessed)
        // 字符集名会直接进用户看得到的提示（「已按 X 解码」）。这里必须是固定串，
        // 不能是 `Charset.name()`：本平台实际生效的名字是 windows-31j，
        // 而那是平台细节，不是用户关心的东西。
        assertEquals("Shift-JIS", decoded.charset)

        // 反向：UTF-8 的日语不能被新加的 Shift-JIS 分支抢在前面解错。
        // 判定顺序改了的话，这条是唯一会发现的地方。
        val utf8 = SubtitleTextDecoding.decode(japaneseSample.toByteArray(Charsets.UTF_8))
        assertEquals("UTF-8", utf8.charset)
        assertFalse(utf8.guessed)
        assertEquals(japaneseSample, utf8.text)
    }

    @Test
    fun `全是汉字没有假名的日语会落到 GB18030（已知取舍）`() {
        // 假名是判定的主要依据（每个 +3 分），而纯汉字的日语（机构名、地名）
        // 在两种解码下都是汉字，分都一样——那句 `>` 就永远不成立，落回兜底。
        // 和 Big5 那条已知局限同一个原因，这里写下来是为了：将来有人想「修」它时，
        // 会先看到这是一条**有意的**取舍，而不是漏了。
        val text = "1\n00:00:01,000 --> 00:00:02,000\n東京大学医学部付属病院\n"
        val decoded = SubtitleTextDecoding.decode(text.toByteArray(Charset.forName("windows-31j")))

        assertEquals("GB18030", decoded.charset)
        assertNotEquals("这就是已知局限：纯汉字的日语会解成乱码", text, decoded.text)
    }

    @Test
    fun `GB18030 兜底不会把 UTF-8 内容解坏`() {
        // 反向验证判定顺序：能严格解出 UTF-8 的内容绝不能走到兜底分支，
        // 否则里面的非 ASCII 会被重新解释成别的字。
        val decoded = SubtitleTextDecoding.decode("简体中文文本".toByteArray(Charsets.UTF_8))

        assertEquals("UTF-8", decoded.charset)
        assertEquals("简体中文文本", decoded.text)
        assertFalse(decoded.guessed)
    }

    @Test
    fun `空内容与单字节内容不会崩`() {
        assertEquals("", SubtitleTextDecoding.decode(ByteArray(0)).text)
        assertEquals("A", SubtitleTextDecoding.decode("A".toByteArray(Charsets.UTF_8)).text)
    }
}
