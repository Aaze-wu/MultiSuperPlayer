package com.multisuperplayer.core.data.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 文件浏览器打开的那条媒体，字幕是靠**直接列目录**找到的。
 *
 * ## 为什么这里的测试必须用真的目录树
 *
 * 这个来源唯一的依赖是 `java.io.File`，所以可以让真的 `listFiles()` 说话：
 * 目录里放什么就得到什么。造一个假的 `DirectoryScan` 回去只能验证
 * 「我们把假数据传对了」，而这一层要判断的东西（哪些算字幕、目录怎么滤掉、
 * 读不到和空目录怎么分）恰好全在「真实行为」这一侧。
 *
 * ## 顺带钉住的东西
 *
 * 「什么算字幕文件」必须和另外两条来源、以及用户手选那条**完全一致**
 * ——都来自 `SubtitleFileNaming.discoverableExtensions`。两边各留一份清单的话，
 * 迟早出现「自动找到的 `.lrc` 在手动列表里没有」这种没人能解释的差别。
 */
class FileSystemSubtitleLocatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val locator = FileSystemSubtitleLocator()

    private fun dir(name: String = "Download"): File =
        File(folder.root, name).apply { mkdirs() }

    @Test
    fun `发现同目录的字幕，并给出可直接读取的 uri`() {
        val download = dir()
        File(download, "movie.mp4").writeText("video")
        File(download, "movie.srt").writeText("subtitle")
        File(download, "movie.chs.ass").writeText("x")
        File(download, "movie.zh.srt").writeText("y")

        val found = locator.scanDirectory(download.absolutePath) as DirectoryScan.Found
        val names = found.subtitles.map { it.fileName }

        // 排序只保证日志/测试可复现；最终候选顺序由 sortedForSelection 决定。
        assertEquals(listOf("movie.chs.ass", "movie.srt", "movie.zh.srt"), names)
        assertTrue(File(download, "movie.mp4").exists())

        val row = found.subtitles.single { it.fileName == "movie.srt" }
        // 必须补 scheme：读字幕走的是 ContentResolver.openInputStream，
        // 它只认 file: / content: ——裸路径会被当成 content uri 去问 ContentProvider。
        assertEquals("file://${File(download, "movie.srt").absolutePath}", row.uri)
        // 大小要取真实值：面板上会显示它，0 会让每一行看起来都是空文件。
        assertEquals("subtitle".length.toLong(), row.sizeBytes)
    }

    @Test
    fun `不是字幕的文件一无所获`() {
        val download = dir()
        File(download, "movie.mp4").writeText("video")
        File(download, "readme.txt").writeText("x")
        File(download, "poster.jpg").writeText("x")
        File(download, "checksums.nfo").writeText("x")

        assertEquals(DirectoryScan.NoSubtitles(4), locator.scanDirectory(download.absolutePath))
    }

    @Test
    fun `位图字幕不算候选`() {
        // `.sub` / `.idx` / `.sup` 是「一张张图片 + 索引」的位图字幕，解析器读不出文本。
        // 列进候选只会让用户点一个必然失败的东西——这条规则和另外两条来源共用。
        val download = dir()
        File(download, "movie.mp4").writeText("v")
        File(download, "movie.idx").writeText("x")
        File(download, "movie.sub").writeText("x")
        File(download, "movie.sup").writeText("x")

        assertEquals(DirectoryScan.NoSubtitles(4), locator.scanDirectory(download.absolutePath))
    }

    @Test
    fun `歌词也算字幕`() {
        // 音频旁边放的是 .lrc：这条来源同样要收，否则「给这首歌配歌词」
        // 在文件浏览器里点开就失效了。
        val music = dir("Music")
        File(music, "song.mp3").writeText("v")
        File(music, "song.lrc").writeText("lyrics")

        val found = locator.scanDirectory(music.absolutePath) as DirectoryScan.Found
        assertEquals(listOf("song.lrc"), found.subtitles.map { it.fileName })
    }

    @Test
    fun `名字像字幕的目录不算候选`() {
        // 「是个文件」和「名字像字幕」必须同时成立。只看后缀的话，一个叫
        // `old.srt` 的目录会被列成候选，点它只会失败。
        val download = dir()
        File(download, "movie.mp4").writeText("v")
        File(download, "episodes.srt").mkdirs()

        assertEquals(DirectoryScan.NoSubtitles(2), locator.scanDirectory(download.absolutePath))
    }

    @Test
    fun `读到的目录里没有字幕时是 NoSubtitles 而不是 Invisible`() {
        // 这两句话给用户的建议相反：一个说「把字幕放进来」，一个说「去检查权限」。
        // 混成一个，用户会拿着「没有字幕」去反复改名，而问题在权限上。
        val download = dir()
        File(download, "movie.mp4").writeText("v")

        assertEquals(DirectoryScan.NoSubtitles(1), locator.scanDirectory(download.absolutePath))
    }

    @Test
    fun `空目录同样是 NoSubtitles`() {
        // 这一档是本来源和另外两条**刻意不一样**的地方：`listFiles()` 返回非 null
        // 数组本身就证明「目录读到了」，没有「查回 0 行是因为查询没通」这种歧义。
        // 把空目录说成 Invisible，会把用户送去检查一个什么毛病都没有的权限。
        val empty = dir("Empty")

        assertEquals(DirectoryScan.NoSubtitles(0), locator.scanDirectory(empty.absolutePath))
    }

    @Test
    fun `列不出来的目录才是 Invisible`() {
        // 不存在的路径里当然也没有字幕，但正确的话是「读不到这个文件夹」，
        // 让用户去看权限/位置，而不是「这个文件夹里没有字幕」。
        assertEquals(
            DirectoryScan.Invisible,
            locator.scanDirectory(File(folder.root, "gone").absolutePath),
        )
        // 拿一个文件当目录问也是同一档：`listFiles()` 对两者都返回 null。
        val file = File(folder.root, "movie.mp4").apply { writeText("v") }
        assertEquals(DirectoryScan.Invisible, locator.scanDirectory(file.absolutePath))
    }
}
