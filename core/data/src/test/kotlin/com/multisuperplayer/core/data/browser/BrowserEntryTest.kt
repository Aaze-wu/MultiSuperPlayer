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
}
