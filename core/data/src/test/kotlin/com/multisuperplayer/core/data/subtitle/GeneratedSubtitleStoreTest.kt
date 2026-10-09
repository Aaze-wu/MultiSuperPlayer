package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.translate.translationMediaKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 生成字幕落盘的测试。
 *
 * 这个类的错法全是「不报错但用户看见不对」的那一类，所以逐条钉住：
 *
 * - 合成 uri 的 key 会被当**文件名**用，混进 `/` 就会写到别的目录里去；
 * - 0 字节的文件是写一半被杀掉留下的垃圾，算成「有字幕」会让面板列出一条空候选；
 * - `save` 失败必须**返回 false 而不是抛**：界面要说的是「识别好了但没存下」，
 *   和「识别失败」是两句完全不同的话；
 * - `delete` 是真删文件（和「人工修正译文」刻意不删相反），所以要确认它真的删了。
 */
class GeneratedSubtitleStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val mediaA = "content://media/external/video/media/1001"
    private val mediaB = "content://media/external/video/media/1002"

    /**
     * 这些用例只关心文件系统、不关心虚拟时间：`save`/`read`/`delete` 里的每个挂起点
     * 都是真实的文件读写，没有 `delay()` 可以跳过，所以直接用 [Dispatchers.Unconfined]
     * 让它们内联执行（用测试调度器反而要求每个用例都 `advanceUntilIdle()`）。
     */
    private object SameThreadDispatchers : DispatcherProvider {
        override val main get() = Dispatchers.Unconfined
        override val default get() = Dispatchers.Unconfined
        override val io get() = Dispatchers.Unconfined
    }

    private fun store(): GeneratedSubtitleStore = GeneratedSubtitleStore(temporary.root, SameThreadDispatchers)

    private fun mediaKey(uri: String): String = translationMediaKey(uri)

    // ------------------------------------------------------------ uri 与文件名

    @Test
    fun `uriFor 用的是和译文修正同一个 key，且不会带路径分隔符`() {
        val store = store()

        // 同一个 key 函数（区别只在目录）：同一媒体的两套数据不会互相覆盖
        assertEquals(GeneratedSubtitleStore.URI_PREFIX + mediaKey(mediaA), store.uriFor(mediaA))

        // key 会被当文件名用，出现分隔符就会写到私有目录外面去
        listOf(mediaA, mediaB, "", "片子/带斜杠:的名字.mp4", "🎬.mkv").forEach { uri ->
            val key = store.uriFor(uri).removePrefix(GeneratedSubtitleStore.URI_PREFIX)
            assertTrue("key 不能为空：$uri", key.isNotEmpty())
            assertFalse("key 不能含 /：$key", key.contains('/'))
            assertFalse("key 不能含 \\：$key", key.contains('\\'))
            assertFalse("key 不能含 ..：$key", key.contains(".."))
        }
    }

    @Test
    fun `uriFor 对同一个媒体稳定，对不同媒体不同`() {
        val store = store()

        assertEquals(store.uriFor(mediaA), store.uriFor(mediaA))
        assertNotEquals(store.uriFor(mediaA), store.uriFor(mediaB))
        // 媒体 uri 为 null（理论上不该发生）也要能算出一个合法 key，不能抛
        assertTrue(store.uriFor(null).startsWith(GeneratedSubtitleStore.URI_PREFIX))
    }

    @Test
    fun `fileFor 只认自己前缀的 uri`() {
        val store = store()

        // 外部字幕的 uri、真实文件 uri 都不能被当成本类的文件——
        // 认了会让「读外挂字幕」和「读生成字幕」两条路混淆
        assertNull(store.fileFor(mediaA))
        assertNull(store.fileFor("file:///sdcard/movie.srt"))
        assertNull(store.fileFor("content://media/external/video/media/1"))
        // 前缀后面什么都没有：算不出 key
        assertNull(store.fileFor(GeneratedSubtitleStore.URI_PREFIX))
    }

    @Test
    fun `往返：uriFor 出来的 uri 一定能换回同一个文件`() {
        val store = store()
        val file = store.fileFor(store.uriFor(mediaA))

        assertNotNull(file)
        assertEquals(GeneratedSubtitleStore.DIRECTORY_NAME, file!!.parentFile!!.name)
        assertEquals("${mediaKey(mediaA)}.${GeneratedSubtitleStore.EXTENSION}", file.name)
        assertNotEquals(store.fileFor(store.uriFor(mediaA)), store.fileFor(store.uriFor(mediaB)))
    }

    // ------------------------------------------------------------ 存在性

    @Test
    fun `没有文件时 exists 为 false、sizeBytes 为 0`() = runTest {
        val store = store()

        assertFalse(store.exists(store.uriFor(mediaA)))
        assertEquals(0L, store.sizeBytes(store.uriFor(mediaA)))
        // 不认识的 uri 一律返回「没有」，不能抛
        assertFalse(store.exists(mediaA))
        assertEquals(0L, store.sizeBytes(mediaA))
    }

    @Test
    fun `0 字节的文件不算存在`() {
        val store = store()
        val uri = store.uriFor(mediaA)
        store.fileFor(uri)!!.apply {
            parentFile?.mkdirs()
            createNewFile()
        }

        // 写一半被系统杀掉留下的就是 0 字节；`exists()` 用 length() 而不是 exists()
        // 正是为了这种情况：算成「有字幕」会让面板列出一条点开什么都没有的候选
        assertFalse(store.exists(uri))
        assertEquals(0L, store.sizeBytes(uri))
    }

    // ------------------------------------------------------------ 写读

    @Test
    fun `save 之后能读回原文，且不留下临时文件`() = runTest {
        val store = store()
        val uri = store.uriFor(mediaA)
        val text = "1\n00:00:00,840 --> 00:00:02,160\n你好\n\n"

        assertTrue(store.save(uri, text))
        assertTrue(store.exists(uri))
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), store.sizeBytes(uri))
        assertEquals(text, store.read(uri))

        // 先写 .tmp 再改名：正常路径上不能留下 .tmp（否则下次扫描会看到它）
        val leftovers = store.fileFor(uri)!!.parentFile!!.listFiles().orEmpty().map { it.name }
        assertEquals(listOf("${mediaKey(mediaA)}.${GeneratedSubtitleStore.EXTENSION}"), leftovers)
    }

    @Test
    fun `save 是覆盖而不是追加`() = runTest {
        val store = store()
        val uri = store.uriFor(mediaA)

        assertTrue(store.save(uri, "第一版"))
        assertTrue(store.save(uri, "第二版"))

        assertEquals("第二版", store.read(uri))
    }

    @Test
    fun `中文与 emoji 按 UTF-8 存取`() = runTest {
        val store = store()
        val uri = store.uriFor(mediaA)
        val text = "你好，世界 🎬\n第二行"

        assertTrue(store.save(uri, text))
        assertEquals(text, store.read(uri))
    }

    @Test
    fun `save 失败时不抛，返回 false`() = runTest {
        val store = store()
        val uri = store.uriFor(mediaA)

        // 目标路径上放一个非空目录：删不掉（`File.delete()` 对非空目录返回 false），
        // 于是「先删旧文件再改名」这一步失败。这正是磁盘/权限出问题时的形状，
        // 而调用方必须能从返回值分辨出来——识别已经付过算力，界面要说的是
        // 「识别好了但没存下」，不是「识别失败」。
        val target = store.fileFor(uri)!!
        target.mkdirs()
        File(target, "占位").writeText("x")

        assertFalse(store.save(uri, "新内容"))
        assertTrue("失败不该把不该删的东西删掉", target.isDirectory)
    }

    @Test
    fun `read 与 save 对不认识的 uri 都不抛`() = runTest {
        val store = store()

        assertNull(store.read(mediaA))
        assertFalse(store.save(mediaA, "内容"))
        // 不认识的 uri 不该在私有目录里造出任何文件
        assertFalse(File(temporary.root, GeneratedSubtitleStore.DIRECTORY_NAME).exists())
    }

    @Test
    fun `目录还不存在时 read 返回 null 而不是抛`() = runTest {
        val store = store()

        assertNull(store.read(store.uriFor(mediaA)))
    }

    // ------------------------------------------------------------ 删除

    @Test
    fun `delete 真删了文件，第二次返回 false`() = runTest {
        val store = store()
        val uri = store.uriFor(mediaA)
        assertTrue(store.save(uri, "内容"))
        val file = store.fileFor(uri)!!

        assertTrue(store.delete(uri))
        assertFalse("文件必须真的没了", file.exists())
        assertFalse(store.exists(uri))
        assertNull(store.read(uri))
        // 已经没什么可删时返回 false，界面靠它区分「已删除」和「本来就没有」
        assertFalse(store.delete(uri))
    }

    @Test
    fun `delete 不认识的 uri 返回 false 且不动别的文件`() = runTest {
        val store = store()
        val uriA = store.uriFor(mediaA)
        val uriB = store.uriFor(mediaB)
        assertTrue(store.save(uriA, "A 的字幕"))
        assertTrue(store.save(uriB, "B 的字幕"))

        assertFalse(store.delete(mediaA))
        assertFalse(store.delete(GeneratedSubtitleStore.URI_PREFIX))

        assertEquals("A 的字幕", store.read(uriA))
        assertEquals("B 的字幕", store.read(uriB))
    }

    @Test
    fun `每条媒体各存一份，互不覆盖`() = runTest {
        val store = store()

        assertTrue(store.save(store.uriFor(mediaA), "A"))
        assertTrue(store.save(store.uriFor(mediaB), "B"))

        assertEquals("A", store.read(store.uriFor(mediaA)))
        assertEquals("B", store.read(store.uriFor(mediaB)))
        assertTrue(store.delete(store.uriFor(mediaA)))
        assertEquals("B", store.read(store.uriFor(mediaB)))
    }

    // ------------------------------------------------------------ 统计与整体清空

    @Test
    fun `没有任何文件时统计是空的`() = runTest {
        val store = store()

        // 目录都还不存在（一次识别都没跑过）也要能回答，而不是抛
        val stats = store.stats()
        assertEquals(0, stats.entries)
        assertEquals(0L, stats.bytes)
        assertTrue(stats.isEmpty)
    }

    @Test
    fun `统计把 0 字节的垃圾排除在份数之外，但它的体积照样算`() = runTest {
        val store = store()
        val good = store.uriFor(mediaA)
        assertTrue(store.save(good, "1\n00:00:00,000 --> 00:00:01,000\n你好\n\n"))

        // 写一半被系统杀掉留下的 0 字节文件：`exists()` 不认它，界面上也不该显示成
        // 「已缓存 2 份」。但 0 字节确实占了一个 inode，把它从体积里漏掉只会让
        // 「清空之后还剩 0 份 · 0 B」看起来更漂亮，而磁盘上其实还躺着东西。
        File(store.fileFor(good)!!.parentFile, "半截.tmp").createNewFile()

        val stats = store.stats()
        assertEquals(1, stats.entries)
        assertEquals(store.sizeBytes(good), stats.bytes)
        assertFalse(stats.isEmpty)
    }

    @Test
    fun `清空会把所有生成的字幕连 tmp 残留一起删掉`() = runTest {
        val store = store()
        val uriA = store.uriFor(mediaA)
        val uriB = store.uriFor(mediaB)
        assertTrue(store.save(uriA, "A 的字幕"))
        assertTrue(store.save(uriB, "B 的字幕"))
        // `.tmp` 是 `GeneratedSubtitleStore.TEMP_SUFFIX`（私有常量，所以这里写字面量）。
        // 正常路径产不出它，留下的是「写一半被系统杀掉」的结果，而它照样占磁盘——
        // 只删正常文件的话，用户会看到「已清空」之后体积还在。
        val stray = File(store.fileFor(uriA)!!.parentFile, "写一半就死了.tmp")
        stray.writeText("半截内容")

        assertTrue(store.clear())

        val directory = store.fileFor(uriA)!!.parentFile!!
        assertTrue("目录里不该剩下任何文件", directory.listFiles().orEmpty().isEmpty())
        assertFalse(store.exists(uriA))
        assertFalse(store.exists(uriB))
        assertTrue("清空之后统计必须是空的", store.stats().isEmpty)
    }

    @Test
    fun `清空只动生成字幕目录，不碰隔壁的目录`() = runTest {
        val store = store()
        assertTrue(store.save(store.uriFor(mediaA), "A 的字幕"))

        // 隔壁放一份「人工修正过的译文」：两个目录是兄弟、键是同一个函数算出来的，
        // 一旦有人把 `clear()` 写成遍历父目录（或者把 `directory` 算成 filesDir），
        // 用户手改的译文会被一起删掉——而那是无法恢复的东西（见 `delete` 的注释）。
        val edits = File(temporary.root, "translation_edits").apply { mkdirs() }
        val edited = File(edits, "${mediaKey(mediaA)}.srt").apply { writeText("我一个字一个字敲的") }

        assertTrue(store.clear())

        assertTrue("手改的译文必须还在", edited.isFile)
        assertEquals("我一个字一个字敲的", edited.readText())
    }

    @Test
    fun `清空一个本来就没有缓存的目录也算成功`() = runTest {
        val store = store()

        // 空目录、以及目录压根不存在，都是「清干净了」这个结果的真子集。
        // 这里返回 false 的话界面会显示「清空失败，还剩 0 份」这种自相矛盾的句子。
        assertTrue(store.clear())
        assertTrue(store.clear())
    }
}
