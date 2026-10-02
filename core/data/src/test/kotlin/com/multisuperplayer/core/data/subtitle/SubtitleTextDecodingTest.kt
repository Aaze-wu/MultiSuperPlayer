package com.multisuperplayer.core.data.subtitle

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
