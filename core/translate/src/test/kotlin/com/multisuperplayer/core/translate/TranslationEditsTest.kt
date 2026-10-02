package com.multisuperplayer.core.translate

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 人工逐句修正。
 *
 * 两个必须成立的性质：换了字幕文件之后**不会整体错位**；
 * 清空修正时**不会删文件**。
 */
class TranslationEditsTest {

    @Test
    fun `key 里既有行号又有原文摘要`() {
        assertEquals(TranslationEditsCodec.key(0, "甲"), TranslationEditsCodec.key(0, " 甲 "))
        assertNotEquals(TranslationEditsCodec.key(0, "甲"), TranslationEditsCodec.key(1, "甲"))
        // 同一行但原文换了 ⇒ 修正失效，退回模型译文。
        // 按行号硬套会把 corrections 整体错位一句，还错得毫无声响。
        assertNotEquals(TranslationEditsCodec.key(0, "甲"), TranslationEditsCodec.key(0, "乙"))
    }

    @Test
    fun `编解码往返`() {
        val edits = mapOf(TranslationEditsCodec.key(0, "甲") to "甲改")
        assertEquals(edits, TranslationEditsCodec.decode(TranslationEditsCodec.encode(edits)))
    }

    @Test
    fun `空 key 与空值都不写入`() {
        val text = TranslationEditsCodec.encode(mapOf("" to "x", "k" to "  "))
        assertEquals(emptyMap(), TranslationEditsCodec.decode(text))
    }

    @Test
    fun `坏内容读成空表`() {
        assertEquals(emptyMap(), TranslationEditsCodec.decode(null))
        assertEquals(emptyMap(), TranslationEditsCodec.decode(""))
        assertEquals(emptyMap(), TranslationEditsCodec.decode("不是 JSON"))
        assertEquals(emptyMap(), TranslationEditsCodec.decode("[1,2]"))
        assertEquals(emptyMap(), TranslationEditsCodec.decode("""{"k":123}"""))
    }

    @Test
    fun `存了能读回来`() = runTest {
        val dir = tempDir()
        val store = TranslationEditsStore(dir, TestDispatchers(testScheduler))
        val edits = mapOf(TranslationEditsCodec.key(3, "甲") to "改过的译文")

        store.save("media-1", edits)
        assertEquals(edits, store.load("media-1"))
        // 别的片子不受影响。
        assertEquals(emptyMap(), store.load("media-2"))
        // 真的是写到盘上的（不是只在内存里）。
        assertTrue(store.fileFor("media-1").exists())
        assertTrue(!File(dir, "media-1.json.tmp").exists(), "临时文件必须已经改名")
    }

    @Test
    fun `再次保存是覆盖而不是追加`() = runTest {
        val store = TranslationEditsStore(tempDir(), TestDispatchers(testScheduler))
        store.save("m", mapOf("k1" to "旧"))
        store.save("m", mapOf("k2" to "新"))
        assertEquals(mapOf("k2" to "新"), store.load("m"))
    }

    @Test
    fun `清空修正不删文件`() = runTest {
        // 「空 Map」有两个来源：用户真的删光了，和调用方传错了参数。
        // 都当成删文件的话，第二种就是一次静默的数据丢失。
        val store = TranslationEditsStore(tempDir(), TestDispatchers(testScheduler))
        store.save("m", mapOf("k" to "x"))
        store.save("m", emptyMap())

        val file = store.fileFor("m")
        assertTrue(file.exists(), "清空后文件应仍在（内容是 {}）")
        assertEquals("{}", file.readText(Charsets.UTF_8))
        assertEquals(emptyMap(), store.load("m"))
    }

    @Test
    fun `读坏文件不抛异常`() = runTest {
        val dir = tempDir()
        val store = TranslationEditsStore(dir, TestDispatchers(testScheduler))
        store.fileFor("m").writeText("这不是 JSON")
        assertEquals(emptyMap(), store.load("m"))
    }

    @Test
    fun `媒体标识是稳定的安全文件名`() {
        val key = translationMediaKey("content://media/external/video/media/42")
        assertEquals(key, translationMediaKey("content://media/external/video/media/42"))
        assertNotEquals(key, translationMediaKey("content://media/external/video/media/43"))
        assertEquals(16, key.length)
        assertTrue(key.all { it in "0123456789abcdef" }, "文件名必须是纯十六进制")
    }
}
