package com.multisuperplayer.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/**
 * 文件哈希与十六进制转换的测试。
 *
 * `sha256` 在 v0.6 里只是**记录**，不参与「文件是否就绪」的判定（那边只比大小），
 * 所以它错了不会拦住任何流程——但一个错的哈希写在日志里会让所有后续排查失真。
 * 这里用公开的已知向量钉住它，顺带钉住「流式读取真的读完了整个文件」：
 * 少读一块的表现是「哈希不对」，而不是报错。
 */
class FileHashingTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /** 公开的已知向量。 */
    private val sha256OfEmpty = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val sha256OfAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val sha256OfHello = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun fileOf(bytes: ByteArray, name: String = "sample.bin"): File =
        temporary.newFile(name).apply { writeBytes(bytes) }

    // ------------------------------------------------------------ toHex

    @Test
    fun `toHex 每字节补两位、方向是大端、字母小写`() {
        assertEquals("", ByteArray(0).toHex())
        assertEquals("00", byteArrayOf(0).toHex())
        // 高位 0 必须补出来，否则 "0f10" 会被拼成 "f10"——长度都变了
        assertEquals("000f10ff", byteArrayOf(0, 15, 16, -1).toHex())
        // 和小写十六进制比较的哈希做呼应：大写会让「相等」永远为假
        assertEquals("abcdef", byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()).toHex())
    }

    // ------------------------------------------------------------ sha256HexOfFile

    @Test
    fun `空文件的 sha256`() {
        assertEquals(sha256OfEmpty, sha256HexOfFile(fileOf(ByteArray(0))))
    }

    @Test
    fun `小文件的 sha256 与已知向量一致`() {
        assertEquals(sha256OfAbc, sha256HexOfFile(fileOf("abc".toByteArray(), "abc.txt")))
        assertEquals(sha256OfHello, sha256HexOfFile(fileOf("hello".toByteArray(), "hello.txt")))
    }

    @Test
    fun `内容差一个字节，哈希就完全不同`() {
        assertNotEquals(
            sha256HexOfFile(fileOf("abc".toByteArray(), "a.bin")),
            sha256HexOfFile(fileOf("abd".toByteArray(), "b.bin")),
        )
    }

    @Test
    fun `超过缓冲区大小的文件会被完整读完`() {
        // 缓冲区是 256 KB，这里用 300 KB —— 刚好要读第二次。
        // 循环条件写错（比如只看第一次 read 的返回值）的表现就是「哈希不对」，
        // 而小于一个缓冲区的文件会全部通过。
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        assertEquals(expected, sha256HexOfFile(fileOf(bytes)))
    }

    @Test
    fun `缓冲区整数倍大小的文件也对`() {
        // 正好 256 KB × 2：循环的终止条件只在这种长度上被执行到
        val bytes = ByteArray(256 * 1024 * 2) { (it % 97).toByte() }
        val expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        assertEquals(expected, sha256HexOfFile(fileOf(bytes)))
    }

    @Test
    fun `同样长度但内容不同的文件哈希不同`() {
        val first = ByteArray(300_000)
        val second = ByteArray(300_000) { 1 }

        assertNotEquals(sha256HexOfFile(fileOf(first, "1.bin")), sha256HexOfFile(fileOf(second, "2.bin")))
    }
}
