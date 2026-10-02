package com.multisuperplayer.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 密钥密文编解码的单元测试。
 *
 * 这段逻辑**不会**在真机上被主动触发——只有数据被写脏、被截断、或 Keystore 被重置时才会走到。
 * 所以它必须靠单测保证：真出问题的那一刻，用户看到的是「密钥好像没了」，
 * 而我们能从这行日志对上号。
 */
class ApiKeyBlobTest {

    private val iv = ByteArray(12) { it.toByte() }
    private val ciphertext = byteArrayOf(0x00, 0x1F, 0x7F, -1, -128)

    @Test
    fun `编出来再解回去完全一致`() {
        val encoded = encodeKeyBlob(iv, ciphertext)

        val decoded = decodeKeyBlob(encoded)

        assertEquals(iv.toList(), decoded?.first?.toList())
        assertEquals(ciphertext.toList(), decoded?.second?.toList())
    }

    @Test
    fun `单字节也能往返`() {
        val decoded = decodeKeyBlob(encodeKeyBlob(ByteArray(12), byteArrayOf(0)))

        assertEquals(12, decoded?.first?.size)
        assertEquals(1, decoded?.second?.size)
    }

    @Test
    fun `IV 长度不对就判废`() {
        // GCM 的 IV 长度是加密侧的契约；长度不对说明数据不是我们写的。
        assertNull(decodeKeyBlob(encodeKeyBlob(ByteArray(11), ciphertext)))
        assertNull(decodeKeyBlob(encodeKeyBlob(ByteArray(16), ciphertext)))
        assertNull(decodeKeyBlob(encodeKeyBlob(ByteArray(0), ciphertext)))
    }

    @Test
    fun `密文为空就判废`() {
        assertNull(decodeKeyBlob(encodeKeyBlob(iv, ByteArray(0))))
    }

    @Test
    fun `缺少分隔符就判废`() {
        assertNull(decodeKeyBlob(""))
        assertNull(decodeKeyBlob("ab"))
        assertNull(decodeKeyBlob("00"))
        assertNull(decodeKeyBlob("000000000000000000000000"))
    }

    @Test
    fun `多一个分隔符也判废`() {
        assertNull(decodeKeyBlob("000000000000000000000000:aa:bb"))
    }

    @Test
    fun `非十六进制字符判废`() {
        assertNull(decodeKeyBlob("zzzzzzzzzzzzzzzzzzzzzzzz:aa"))
        assertNull(decodeKeyBlob("000000000000000000000000:gg"))
    }

    @Test
    fun `奇数长度的十六进制判废`() {
        assertNull(decodeKeyBlob("00000000000000000000000:aa"))
        assertNull(decodeKeyBlob("000000000000000000000000:a"))
    }

    @Test
    fun `空白输入规范化为 null`() {
        assertNull(normalizeApiKeyInput(""))
        assertNull(normalizeApiKeyInput("   "))
        assertNull(normalizeApiKeyInput("\n\t "))
    }

    @Test
    fun `首尾空白被去掉而内容不动`() {
        assertEquals("sk-abc.def_123", normalizeApiKeyInput("  sk-abc.def_123 \n"))
        assertEquals("sk-a b", normalizeApiKeyInput("sk-a b"))
    }
}
