package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrowserEntry] 自身推导出的那些字段的单元测试。
 *
 * 这些属性看起来都是「一行代码」，但每一个错了都不会抛异常——
 * 只会让列表里某一行的文字变成空白、或者某个文件点了没反应。
 */
class BrowserEntryTest {

    // ------------------------------------------------------------ 标题

    @Test
    fun `标题去掉后缀`() {
        assertEquals("movie", file("movie.mp4").displayTitle)
        // 只去掉**最后**一个点之后的部分：`a.b.c.mp4` 的标题是 `a.b.c`。
        assertEquals("a.b.c", file("a.b.c.mp4").displayTitle)
        // 隐藏文件去掉后缀之后仍然带着点。
        assertEquals(".hidden", file(".hidden.mp4").displayTitle)
    }

    @Test
    fun `没有后缀时标题就是文件名`() {
        assertEquals("no_extension", file("no_extension").displayTitle)
        assertEquals("中文片名", file("中文片名").displayTitle)
    }

    @Test
    fun `整个名字就是一个后缀时标题回退成原名，而不是空串`() {
        // 这一条是防「列表里出现一行空白」：`.nomedia` 去完后缀是空串。
        // 判据必须是「去完之后还剩不剩东西」，不能用「名字里有没有点」。
        assertEquals(".nomedia", file(".nomedia").displayTitle)
        assertEquals(".nomedia", file(".nomedia").toMediaEntry()?.title ?: "")
    }

    // ------------------------------------------------------------ 后缀

    @Test
    fun `后缀统一小写且不含点`() {
        assertEquals("mp4", file("movie.MP4").extension)
        assertEquals("mkv", file("movie.Mkv").extension)
        assertEquals("", file("no_extension").extension)
    }

    // ------------------------------------------------------------ 隐藏

    @Test
    fun `以点开头算隐藏`() {
        assertTrue(file(".nomedia").hidden)
        assertTrue(dir(".thumbnails").hidden)
        assertFalse(file("movie.mp4").hidden)
        // 点在中间不算隐藏。
        assertFalse(file("a.b.mp4").hidden)
    }

    // ------------------------------------------------------------ 可否播放

    @Test
    fun `目录不可播放`() {
        val folder = dir("Movies")
        assertTrue(folder.isDirectory)
        assertFalse(folder.isFile)
        assertFalse(folder.playable)
        assertNull(folder.toMediaEntry())
    }

    @Test
    fun `后缀认不出的文件仍然可播放，明确不是媒体的文件不可播放`() {
        // 这两种情况在界面上的待遇必须不同，所以判据是 `kind`：
        // UNKNOWN = 「可能是媒体，交给内核探测」（.mka 这类冷门容器）
        // null    = 「明确不是媒体」（.lrc / .txt / .zip）
        assertTrue(file("weird.xyz", kind = MediaKind.UNKNOWN).playable)
        assertFalse(file("lyrics.lrc", kind = null).playable)
        assertNull(file("lyrics.lrc", kind = null).toMediaEntry())
    }

    // ------------------------------------------------------------ 转成媒体条目

    @Test
    fun `文件转成媒体条目时带上来源与 id 前缀`() {
        val entry = BrowserEntry(
            ref = "/storage/emulated/0/Movies/movie.mp4",
            name = "movie.mp4",
            isDirectory = false,
            sizeBytes = 4_096L,
            lastModifiedMs = 1_700_000_000_000L,
            kind = MediaKind.VIDEO,
            mimeType = "video/mp4",
        )

        val media = requireNotNull(entry.toMediaEntry())

        assertEquals("file:/storage/emulated/0/Movies/movie.mp4", media.id)
        assertEquals("/storage/emulated/0/Movies/movie.mp4", media.uri)
        assertEquals("movie", media.title)
        assertEquals("movie.mp4", media.displayName)
        assertEquals(MediaKind.VIDEO, media.kind)
        assertEquals(MediaSource.FILE_SYSTEM, media.source)
        assertEquals(4_096L, media.sizeBytes)
        assertEquals("video/mp4", media.mimeType)
        // 秒级时间戳：`lastModifiedMs` 是毫秒，媒体条目统一用秒。
        assertEquals(1_700_000_000L, media.dateAddedSeconds)
    }

    @Test
    fun `时间未知时不写出一个假的 1970 年`() {
        // `lastModifiedMs = 0` 时 `dateAddedSeconds` 必须是 0（「不知道」），
        // 而不是 0（「1970-01-01」）——这两个在界面上都显示成「无」，
        // 但只有前者是对的，后者会让「按加入时间排序」出现一堆并列的第一名。
        assertEquals(0L, requireNotNull(file("movie.mp4", kind = MediaKind.VIDEO).toMediaEntry()).dateAddedSeconds)
    }

    @Test
    fun `相对路径留空`() {
        // 文件浏览器的条目不进媒体库，相对路径算错反而会带偏分组。
        val media = requireNotNull(file("movie.mp4", kind = MediaKind.VIDEO).toMediaEntry())
        assertEquals("", media.relativePath ?: "")
    }

    // ------------------------------------------------------------ 身份

    @Test
    fun `mediaId 与转出来的媒体条目 id 是同一个`() {
        // 两处各写一遍 `file:` 前缀的话，多选集合（按 mediaId 存）和播放列表
        // （按 MediaEntry.id 存）迟早会对不上，而症状只是「勾了却播不了」。
        val entry = file("movie.mp4", kind = MediaKind.VIDEO)
        assertEquals(BrowserEntry.MEDIA_ID_PREFIX + entry.ref, entry.mediaId)
        assertEquals(entry.mediaId, requireNotNull(entry.toMediaEntry()).id)
    }

    @Test
    fun `只有自己的前缀算浏览页的条目`() {
        assertTrue(BrowserEntry.isBrowserMediaId(BrowserEntry.mediaIdOf("/x/a.mp4")))
        // 媒体库的数字 id 与 SAF 的 `saf:` 都不是——猜错会让字幕查找走错分支。
        assertFalse(BrowserEntry.isBrowserMediaId("12345"))
        assertFalse(BrowserEntry.isBrowserMediaId("saf:primary:Music/a.mp3"))
    }

    // ------------------------------------------------------------ 来源

    @Test
    fun `SAF 目录里的文件来源是 SAF 而不是文件系统`() {
        // 系统文件选择器授权的目录，ref 是 `content://…` 的 document uri，不是路径。
        // 当成文件系统的话，「取上一级目录」取到的是那串 uri 本身（一个不存在的
        // 目录），于是这个文件夹里的字幕一条都找不到——而且整个目录都这样，
        // 看起来像「这个目录的字幕功能坏了」，与文件名无关。
        val entry = BrowserEntry(
            ref = SAF_DOCUMENT_URI,
            name = "movie.mp4",
            isDirectory = false,
            kind = MediaKind.VIDEO,
        )

        val media = requireNotNull(entry.toMediaEntry())

        assertEquals(MediaSource.SAF_TREE, media.source)
        // id 仍然带 `file:` 前缀：「这条来自浏览页」与来源是哪一支是两件事，
        // 前缀（[BrowserEntry.MEDIA_ID_PREFIX]）只管前者。
        assertEquals(BrowserEntry.MEDIA_ID_PREFIX + entry.ref, media.id)
    }

    @Test
    fun `本地路径的来源是文件系统`() {
        // 与上一条互为对照：两种 ref 都带 `file:` 前缀，只能看 ref 自己。
        assertEquals(MediaSource.FILE_SYSTEM, BrowserEntry.sourceOfRef("/storage/emulated/0/Movies/movie.mp4"))
        assertEquals(MediaSource.SAF_TREE, BrowserEntry.sourceOfRef(SAF_DOCUMENT_URI))
        assertEquals(MediaSource.FILE_SYSTEM, requireNotNull(file("movie.mp4").toMediaEntry()).source)
    }

    @Test
    fun `id 前缀能被原样取回来`() {
        // 回放时要从持久化的 id 还原来源：先取回 ref，再按 ref 判分支。
        // 两件事分在两个函数里，中间那一步（去前缀）错了会把一个路径变成
        // `ile:/…`，而它看上去仍然「像一个路径」。
        assertEquals(SAF_DOCUMENT_URI, BrowserEntry.refOf(BrowserEntry.mediaIdOf(SAF_DOCUMENT_URI)))
        assertEquals("/x/a.mp4", BrowserEntry.refOf(BrowserEntry.mediaIdOf("/x/a.mp4")))
    }

    // ------------------------------------------------------------ 工具

    private fun file(name: String, kind: MediaKind? = MediaKind.VIDEO): BrowserEntry = BrowserEntry(
        ref = "/x/$name",
        name = name,
        isDirectory = false,
        kind = kind,
    )

    private fun dir(name: String): BrowserEntry = BrowserEntry(
        ref = "/x/$name",
        name = name,
        isDirectory = true,
    )

    private companion object {
        /**
         * 系统文件选择器给出的 document uri，**带 tree 段**——浏览页里的 SAF 条目
         * 就是用 `DocumentsContract.buildDocumentUriUsingTree` 拼出来的（见
         * `SafDocumentSource`），没有 tree 段的那种在字幕查找那一步会被判成
         * 「无法列目录」，所以测试数据必须用带 tree 段的真实形状。
         */
        const val SAF_DOCUMENT_URI =
            "content://com.android.externalstorage.documents/tree/primary%3AMovies" +
                "/document/primary%3AMovies%2Fmovie.mp4"
    }
}
