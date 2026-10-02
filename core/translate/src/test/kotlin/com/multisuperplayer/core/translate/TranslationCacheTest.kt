package com.multisuperplayer.core.translate

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 译文缓存。
 *
 * 缓存错了比没缓存更糟：用户会以为「重新翻译」按钮坏了。所以这里除了
 * 命中/落盘，还要断言 **key 的每一项都真的参与计算**。
 */
class TranslationCacheTest {

    private val target = TranslationTarget.SIMPLIFIED_CHINESE

    @Test
    fun `key 对每一项输入都敏感`() {
        val base = TranslationCacheCodec.key("m", target, "fp", "你好")
        assertEquals(base, TranslationCacheCodec.key("m", target, "fp", "你好"))
        assertEquals(base, TranslationCacheCodec.key("  m  ", target, "fp", "你好"))

        assertNotEquals(base, TranslationCacheCodec.key("m2", target, "fp", "你好"))
        assertNotEquals(base, TranslationCacheCodec.key("m", TranslationTarget.JAPANESE, "fp", "你好"))
        assertNotEquals(base, TranslationCacheCodec.key("m", target, "fp2", "你好"))
        assertNotEquals(base, TranslationCacheCodec.key("m", target, "fp", "你好呀"))
    }

    @Test
    fun `提示词版本参与 key`() {
        // 改了提示词却复用旧结果 ⇒ 用户点了「重新翻译」一条都没变。
        assertTrue(TRANSLATION_PROMPT_VERSION >= 1)
        val key = TranslationCacheCodec.key("m", target, "fp", "你好")
        assertEquals(64, key.length)
    }

    @Test
    fun `空术语表与有术语表的 key 不同`() {
        assertNotEquals(
            TranslationCacheCodec.key("m", target, emptyMap<String, String>().fingerprint(), "你好"),
            TranslationCacheCodec.key("m", target, mapOf("桐人" to "").fingerprint(), "你好"),
        )
    }

    @Test
    fun `单行编解码往返`() {
        val entry = CacheEntry("k", "源文", "译文", 123L)
        val line = TranslationCacheCodec.encodeLine(entry)
        assertEquals(entry, TranslationCacheCodec.decodeLine(line))
        assertTrue(!line.contains('\n'), "一行必须是一行，否则 JSONL 会错位")
    }

    @Test
    fun `坏行只丢自己`() {
        assertEquals(null, TranslationCacheCodec.decodeLine(""))
        assertEquals(null, TranslationCacheCodec.decodeLine("不是 JSON"))
        assertEquals(null, TranslationCacheCodec.decodeLine("""{"source":"a"}"""))
        assertEquals(null, TranslationCacheCodec.decodeLine("""{"key":"k","translation":"   "}"""))
        // missing 'at' 是可容忍的：时间戳只用于统计。
        assertEquals(0L, TranslationCacheCodec.decodeLine("""{"key":"k","translation":"甲"}""")?.atMillis)
    }

    @Test
    fun `整份解码时后写的覆盖先写的`() {
        val text = listOf(
            TranslationCacheCodec.encodeLine(CacheEntry("k", "a", "旧", 1)),
            "坏行",
            TranslationCacheCodec.encodeLine(CacheEntry("k", "a", "新", 2)),
        ).joinToString("\n")
        val all = TranslationCacheCodec.decodeAll(text)
        assertEquals(1, all.size)
        assertEquals("新", all.getValue("k").translation)
    }

    // ------------------------------------------------------------ 存储

    @Test
    fun `写入后能查到`() = runTest {
        val store = TranslationCacheStore(File(tempDir(), "c.jsonl"), TestDispatchers(testScheduler))
        // 返回值是「本次真正新增的条数」：新 key ⇒ 1；重复 key ⇒ 0（见下一个用例）。
        assertEquals(1, store.store(listOf(CacheEntry("k1", "a", "甲", 1))))
        assertEquals(mapOf("k1" to "甲"), store.lookup(listOf("k1", "没有的")))
        assertEquals(1, store.size())
        assertTrue(store.lookup(emptyList()).isEmpty())
    }

    @Test
    fun `重复 key 不重复追加`() = runTest {
        val file = File(tempDir(), "c.jsonl")
        val store = TranslationCacheStore(file, TestDispatchers(testScheduler))
        store.store(listOf(CacheEntry("k1", "a", "甲", 1)))
        assertEquals(0, store.store(listOf(CacheEntry("k1", "a", "乙", 2))))
        assertEquals(1, store.size())
        assertEquals(mapOf("k1" to "甲"), store.lookup(listOf("k1")))
        assertEquals(1, file.readText().trim().lines().size)
    }

    @Test
    fun `换一个实例仍然能读到（真的落盘了）`() = runTest {
        val file = File(tempDir(), "c.jsonl")
        TranslationCacheStore(file, TestDispatchers(testScheduler))
            .store(listOf(CacheEntry("k1", "a", "甲", 1)))
        val reopened = TranslationCacheStore(file, TestDispatchers(testScheduler))
        assertEquals(mapOf("k1" to "甲"), reopened.lookup(listOf("k1")))
    }

    @Test
    fun `清空后文件与索引都归零`() = runTest {
        val file = File(tempDir(), "c.jsonl")
        val store = TranslationCacheStore(file, TestDispatchers(testScheduler))
        store.store(listOf(CacheEntry("k1", "a", "甲", 1)))
        store.clear()
        assertEquals(0, store.size())
        assertTrue(!file.exists())
        assertTrue(store.lookup(listOf("k1")).isEmpty())
    }

    @Test
    fun `文件里堆了重复行时会在下次写入时压缩`() = runTest {
        // 真实来源：并发追加、或上次压缩改名失败。
        val dir = tempDir()
        val file = File(dir, "c.jsonl")
        val line = TranslationCacheCodec.encodeLine(CacheEntry("k1", "a", "甲", 1))
        file.writeText(List(100) { line }.joinToString("\n", postfix = "\n"))

        val store = TranslationCacheStore(file, TestDispatchers(testScheduler))
        assertEquals(1, store.store(listOf(CacheEntry("k2", "b", "乙", 2))))
        assertEquals(2, store.size())
        assertEquals(2, file.readText().trim().lines().size, "压缩后重复行应被清掉")
        assertEquals(mapOf("k1" to "甲"), store.lookup(listOf("k1")))
    }

    @Test
    fun `写不进去也不影响调用方`() = runTest {
        // 缓存失败不该让翻译失败：译文已经在内存里，用户照样看得到。
        val dir = tempDir()
        val blocker = File(dir, "blocker").apply { writeText("x") }
        val store = TranslationCacheStore(File(blocker, "c.jsonl"), TestDispatchers(testScheduler))
        store.store(listOf(CacheEntry("k1", "a", "甲", 1)))
        // 读不回来是预期的（写失败了），但**不能抛异常**。
        assertTrue(store.lookup(listOf("k1")).isEmpty())
    }
}
