package com.multisuperplayer.core.data.library

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAF 目录遍历的单元测试。
 *
 * 用假树喂各种**真实设备上会遇到、但在工位上很难复现**的形状：
 * 自引用的目录（符号链接）、超过深度上限的深目录、一次返回几千项的宽目录、
 * 读不到子项的目录（授权被回收）。这些输入如果没被挡住，表现都是
 * 「添加文件夹之后应用卡死或闪退」，而且**只在用户的设备上**。
 */
class SafTreeWalkerTest {

    // --------------------------------------------------------------- 假树

    private class FakeNode(
        override val documentId: String,
        override val displayName: String?,
        override val isDirectory: Boolean,
        override val mimeType: String? = null,
        override val sizeBytes: Long = 0L,
        override val lastModifiedMs: Long = 0L,
        private val childrenProvider: () -> List<DocumentNode> = { emptyList() },
    ) : DocumentNode {
        override val uri: String get() = "content://test/document/$documentId"
        override fun children(): List<DocumentNode> = childrenProvider()
    }

    private fun dir(
        documentId: String,
        name: String,
        vararg children: DocumentNode,
    ): DocumentNode = FakeNode(documentId, name, isDirectory = true) { children.toList() }

    private fun file(
        documentId: String,
        name: String?,
        mimeType: String? = null,
        sizeBytes: Long = 1024L,
        lastModifiedMs: Long = 0L,
    ): DocumentNode = FakeNode(
        documentId = documentId,
        displayName = name,
        isDirectory = false,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
    )

    private fun walk(root: DocumentNode) = SafTreeWalker.walk(root)

    // --------------------------------------------------------------- 正常路径

    @Test
    fun `扁平目录里的媒体文件都被收下`() {
        val outcome = walk(
            dir(
                "primary:Music", "Music",
                file("primary:Music/a.mp3", "a.mp3"),
                file("primary:Music/b.flac", "b.flac"),
                file("primary:Music/movie.mkv", "movie.mkv"),
            ),
        )

        assertEquals(listOf("saf:primary:Music/a.mp3", "saf:primary:Music/b.flac", "saf:primary:Music/movie.mkv"),
            outcome.entries.map { it.id })
        assertFalse(outcome.truncated)
    }

    @Test
    fun `嵌套目录会被递归进去`() {
        val outcome = walk(
            dir(
                "primary:Music", "Music",
                dir(
                    "primary:Music/Album", "Album",
                    file("primary:Music/Album/x.mp3", "x.mp3"),
                ),
            ),
        )

        assertEquals(1, outcome.entries.size)
        // 相对路径保留从卷根起的**完整层级**（不含卷名），字幕查找要靠它定位同级目录。
        assertEquals("Music/Album/", outcome.entries.first().relativePath)
    }

    @Test
    fun `条目按 SAF 来源打标并填好字段`() {
        val entry = walk(
            dir(
                "primary:Music", "Music",
                file("primary:Music/My Song.mp3", "My Song.mp3", mimeType = "audio/mpeg", lastModifiedMs = 1_700_000_000_000L),
            ),
        ).entries.single()

        assertEquals(MediaSource.SAF_TREE, entry.source)
        assertEquals("My Song", entry.title)
        assertEquals("My Song.mp3", entry.displayName)
        assertEquals(MediaKind.AUDIO, entry.kind)
        assertEquals("Music/", entry.relativePath)
        assertEquals("audio/mpeg", entry.mimeType)
        assertEquals(1_700_000_000L, entry.dateAddedSeconds)
        // 封面留 null 是刻意的（没有 MediaStore 的缩略图通道），界面有兜底路径。
        assertNull(entry.artworkUri)
        assertFalse(entry.hasArtwork)
        // 标签信息不填是已知取舍：填它要给每个文件开一次 MediaMetadataRetriever。
        assertNull(entry.artist)
        assertEquals(0L, entry.durationMs)
    }

    @Test
    fun `没有后缀的文件用整个文件名当标题`() {
        val entry = walk(
            dir("primary:Music", "Music", file("primary:Music/VID_20240101", "VID_20240101")),
        ).entries.single()

        // substringBeforeLast 拿不到点时不能把标题变成空串。
        assertEquals("VID_20240101", entry.title)
    }

    // --------------------------------------------------------------- 过滤

    @Test
    fun `非媒体文件不进结果`() {
        val outcome = walk(
            dir(
                "primary:Music", "Music",
                file("primary:Music/a.mp3", "a.mp3"),
                file("primary:Music/a.srt", "a.srt"),
                file("primary:Music/cover.jpg", "cover.jpg"),
                file("primary:Music/readme.txt", "readme.txt"),
            ),
        )

        assertEquals(listOf("a.mp3"), outcome.entries.map { it.displayName })
    }

    @Test
    fun `隐藏文件和隐藏目录都跳过`() {
        val outcome = walk(
            dir(
                "primary:Music", "Music",
                file("primary:Music/.hidden.mp3", ".hidden.mp3"),
                dir(
                    "primary:Music/.thumbnails", ".thumbnails",
                    file("primary:Music/.thumbnails/a.mp3", "a.mp3"),
                ),
                file("primary:Music/可见.mp3", "可见.mp3"),
            ),
        )

        assertEquals(listOf("可见.mp3"), outcome.entries.map { it.displayName })
    }

    @Test
    fun `displayName 缺失时用 documentId 里的文件名兜底`() {
        val outcome = walk(
            dir("primary:Music", "Music", file("primary:Music/a.mp3", name = null)),
        )

        assertEquals("a.mp3", outcome.entries.single().displayName)
    }

    @Test
    fun `displayName 和 documentId 都拿不到名字时丢弃`() {
        val outcome = walk(dir("primary:Music", "Music", file("primary:Music/", name = "")))

        assertTrue(outcome.entries.isEmpty())
    }

    // --------------------------------------------------------------- 护栏

    @Test
    fun `自引用的目录不会无限递归`() {
        // 真实场景：provider 返回的子节点 documentId 等于它的祖先（符号链接/实现 bug）。
        // 没有 visited 集合的话这里会栈溢出——而栈溢出会直接杀进程。
        lateinit var root: FakeNode
        root = FakeNode("primary:Music", "Music", isDirectory = true) { listOf(root) }

        val outcome = walk(root)

        assertTrue(outcome.entries.isEmpty())
    }

    @Test
    fun `互相引用的两个目录也不会死循环`() {
        lateinit var a: FakeNode
        lateinit var b: FakeNode
        a = FakeNode("primary:A", "A", isDirectory = true) { listOf(b) }
        b = FakeNode("primary:B", "B", isDirectory = true) { listOf(a) }

        assertTrue(walk(a).entries.isEmpty())
    }

    @Test
    fun `超过深度上限时标记为截断`() {
        // 造一条 MAX_DEPTH + 2 层深的目录链，最后一层放一个媒体文件。
        var node: DocumentNode = file("primary:deep/deep.mp3", "deep.mp3")
        for (level in SafScanRules.MAX_DEPTH + 2 downTo 1) {
            val child = node
            node = dir("primary:deep/l$level", "l$level", child)
        }

        val outcome = walk(node)

        assertTrue(outcome.truncated)
        assertTrue("深处的那个文件不该被收到", outcome.entries.isEmpty())
    }

    @Test
    fun `刚好在上限内的深度不会被判截断`() {
        var node: DocumentNode = file("primary:deep/deep.mp3", "deep.mp3")
        for (level in SafScanRules.MAX_DEPTH downTo 1) {
            val child = node
            node = dir("primary:deep/l$level", "l$level", child)
        }

        val outcome = walk(node)

        assertFalse(outcome.truncated)
        assertEquals(1, outcome.entries.size)
    }

    @Test
    fun `条数超过上限时收手并标记截断`() {
        // 一个宽目录：一次 children() 返回几千项。上限只在入口检查的话会被直接冲过去。
        val many = (0 until SafScanRules.MAX_ENTRIES + 50).map { index ->
            file("primary:Music/track$index.mp3", "track$index.mp3")
        }
        val outcome = walk(dir("primary:Music", "Music", *many.toTypedArray()))

        assertTrue(outcome.truncated)
        assertEquals(SafScanRules.MAX_ENTRIES, outcome.entries.size)
    }

    @Test
    fun `授权进来的不是目录时判截断且没有条目`() {
        val outcome = walk(file("primary:Music/a.mp3", "a.mp3"))

        assertTrue(outcome.truncated)
        assertTrue(outcome.entries.isEmpty())
    }

    @Test
    fun `某个子目录读不出来时其它分支照常收`() {
        // children() 的约定是「不抛异常，读不到返回空表」——授权中途失效就是这个形状。
        val broken = FakeNode("primary:Music/Broken", "Broken", isDirectory = true) { emptyList() }
        val outcome = walk(
            dir(
                "primary:Music", "Music",
                file("primary:Music/a.mp3", "a.mp3"),
                broken,
            ),
        )

        assertEquals(listOf("a.mp3"), outcome.entries.map { it.displayName })
        assertFalse(outcome.truncated)
    }

    @Test
    fun `空目录得到空结果且不判截断`() {
        val outcome = walk(dir("primary:Empty", "Empty"))

        assertTrue(outcome.entries.isEmpty())
        assertFalse(outcome.truncated)
    }
}
