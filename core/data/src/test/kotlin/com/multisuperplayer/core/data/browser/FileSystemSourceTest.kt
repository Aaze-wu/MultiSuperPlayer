package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.MediaKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 唯一能在 JVM 里真跑的目录来源。
 *
 * 它承载的转换——目录 vs 文件、绝对路径怎么拼、目录的大小、后缀怎么判——
 * 是三个来源共用的口径，所以这里用**真的临时目录树**验证，而不是造假数据：
 * 造出来的 `File` 树不会骗人，`listFiles()` 是真实行为。
 *
 * 另外两个来源（SAF / 媒体库）在 JVM 里连构造都做不到，
 * 它们的判断逻辑已经全部挪到纯规则里（`BrowserRules` / `BrowserRoots`）。
 */
class FileSystemSourceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File get() = folder.root

    @Test
    fun `没有全盘权限时返回 Unreadable 而不是空目录`() = runBlocking {
        // 这两个结果在界面上是两句话（见 BrowserContent 的注释）。
        // 返回空列表 = 让用户对着一个装满文件的目录说「里面什么都没有」，
        // 而屏幕上没有任何线索指向「权限没开」。
        val source = FileSystemSource { false }

        assertEquals(BrowserListing.Unreadable, source.list(root.absolutePath))
    }

    @Test
    fun `位置不是目录时返回 NotADirectory`() = runBlocking {
        val file = File(root, "movie.mp4").apply { writeText("x") }
        val source = granted()

        assertEquals(BrowserListing.NotADirectory, source.list(file.absolutePath))
        // 不存在的路径也是同一档：界面该退回上一层，而不是留在原地重试。
        assertEquals(BrowserListing.NotADirectory, source.list(File(root, "nope").absolutePath))
    }

    @Test
    fun `列出的目录与文件带正确的标记和 ref`() = runBlocking {
        File(root, "Movies").mkdirs()
        File(root, "song.mp3").writeText("x")

        val listing = granted().list(root.absolutePath) as BrowserListing.Ready
        val byName = listing.entries.associateBy { it.name }

        assertTrue(byName.getValue("Movies").isDirectory)
        assertFalse(byName.getValue("Movies").isFile)
        assertFalse(byName.getValue("song.mp3").isDirectory)

        // ref 是绝对路径：界面把它原样交给播放器，或者原样传回来再列一次。
        assertEquals(File(root, "Movies").absolutePath, byName.getValue("Movies").ref)
        assertEquals(File(root, "song.mp3").absolutePath, byName.getValue("song.mp3").ref)

        // 后缀判定复用 SAF 扫描用的那张表，保证三个来源口径一致：
        // 同一个 `.mkv` 在媒体库、SAF 目录、文件浏览器里必须是同一类。
        assertEquals(MediaKind.AUDIO, byName.getValue("song.mp3").kind)
    }

    @Test
    fun `目录的大小恒为零`() = runBlocking {
        File(root, "Movies").mkdirs()

        val listing = granted().list(root.absolutePath) as BrowserListing.Ready
        val movies = listing.entries.first { it.name == "Movies" }

        // `File.length()` 对目录在不同卷上返回 0 或 4096（块大小）。
        // 直接透出会让界面上出现「Movies 4.0 KB」这种没有意义的信息。
        assertEquals(0L, movies.sizeBytes)
    }

    @Test
    fun `空目录返回 Ready 空表`() = runBlocking {
        // 和 `Unreadable` 必须是两回事：这里目录**读到了**，就是没有内容。
        assertEquals(BrowserListing.Ready(emptyList()), granted().list(root.absolutePath))
    }

    @Test
    fun `隐藏文件照常列出，过滤交给上层`() = runBlocking {
        File(root, ".nomedia").writeText("")

        val listing = granted().list(root.absolutePath) as BrowserListing.Ready

        // source 的契约是「不排序、不过滤」。一旦在这里就把隐藏项丢掉，
        // `BrowserRules` 算出来的 `hiddenCount` 永远是 0，
        // 界面再也说不出「这里只是被开关挡住了」这句话。
        assertTrue(listing.entries.any { it.name == ".nomedia" })
    }

    @Test
    fun `字幕这类非媒体文件留在列表里但 kind 为 null`() = runBlocking {
        File(root, "movie.srt").writeText("")
        File(root, "weird.xyz").writeText("")

        val listing = granted().list(root.absolutePath) as BrowserListing.Ready
        val byName = listing.entries.associateBy { it.name }

        // `.srt` 要留着（「为当前视频指定这个字幕」的场景），但明确不是媒体：
        // kind = null 让界面知道「点了不会播放」。
        assertNull(byName.getValue("movie.srt").kind)
        assertFalse(byName.getValue("movie.srt").playable)

        // 认不出的后缀给 UNKNOWN：**可播**，交给内核去探测。
        // 这一档和上面那一档在界面上待遇不同，所以判据不能合并。
        assertEquals(MediaKind.UNKNOWN, byName.getValue("weird.xyz").kind)
        assertTrue(byName.getValue("weird.xyz").playable)
    }

    @Test
    fun `来源类型是文件系统`() {
        assertEquals(BrowserSourceKind.FILE_SYSTEM, granted().kind)
    }

    private fun granted(): FileSystemSource = FileSystemSource { true }
}
