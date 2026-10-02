package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 「这条媒体的字幕该去哪儿找」。
 *
 * 这是整个字幕发现里唯一一个**错了不会报错**的地方：走错一支只会得到
 * 「这个文件夹里没有字幕」，而用户完全看不出真正的原因是我们根本没查对地方。
 *
 * 真机上真发生过一次：文件浏览器打开的条目落在「查 MediaStore」那一支
 * （它没有 `relativePath`，于是直接回「拿不到目录」），结果是字幕就在同一个文件夹里躺着，
 * 而面板一直说找不到。它当时骗过了编译、骗过了九百多个测试，因为这里**没有任何一个测试**。
 *
 * 下面的用例按「每条来源各有一个位置」组织，其中两条专门钉住那个回归。
 */
class SubtitleLookupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val download: File get() = File(folder.root, "Download").apply { mkdirs() }

    private fun entry(
        source: MediaSource,
        uri: String,
        relativePath: String? = null,
    ) = MediaEntry(
        id = "x",
        uri = uri,
        title = "movie",
        kind = MediaKind.VIDEO,
        source = source,
        relativePath = relativePath,
    )

    private fun localMedia(name: String = "movie.mp4"): String =
        File(download, name).absolutePath

    // ------------------------------------------------------------ 文件浏览器

    @Test
    fun `文件浏览器的条目去列它自己的目录`() {
        val lookup = subtitleLookupOf(entry(MediaSource.FILE_SYSTEM, localMedia()))

        assertEquals(SubtitleLookup.LocalDirectory(download.absolutePath), lookup)
        assertEquals(download.absolutePath, lookup.where)
    }

    @Test
    fun `带 file 前缀的路径一样能取出目录`() {
        val lookup = subtitleLookupOf(
            entry(MediaSource.FILE_SYSTEM, "file://${localMedia()}"),
        )

        assertEquals(SubtitleLookup.LocalDirectory(download.absolutePath), lookup)
    }

    @Test
    fun `文件浏览器条目即使带了相对路径也不去查 MediaStore`() {
        // 这就是那个真机回归的形状：相对路径恰好存在（有些调用方会顺手填上），
        // 于是判断如果写成「有 relativePath 就查 MediaStore」，它就会被带偏。
        // 决定去哪儿的必须是**来源**，不是「哪个字段恰好有值」。
        val lookup = subtitleLookupOf(
            entry(MediaSource.FILE_SYSTEM, localMedia(), relativePath = "Download/"),
        )

        assertEquals(SubtitleLookup.LocalDirectory(download.absolutePath), lookup)
    }

    @Test
    fun `推不出目录时返回 Unavailable 而不是猜一个`() {
        // 没有上一级的路径。猜成 `"."` 或空串会去列当前工作目录，
        // 于是自动挂上一条来自陌生目录的字幕——比「没找到」糟得多。
        assertEquals(
            SubtitleLookup.Unavailable,
            subtitleLookupOf(entry(MediaSource.FILE_SYSTEM, "movie.mp4")),
        )
    }

    @Test
    fun `content uri 不会被当成路径`() {
        // 分区存储下别的应用分享进来的东西长这样；它不是路径，
        // 任何「去掉 scheme 当成文件路径」的猜测都会指向一个不存在的位置。
        assertEquals(
            SubtitleLookup.Unavailable,
            subtitleLookupOf(
                entry(MediaSource.FILE_SYSTEM, "content://media/external/video/media/42"),
            ),
        )
    }

    // ------------------------------------------------------------------- SAF

    @Test
    fun `SAF 条目去问 provider 要兄弟目录`() {
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fmovie.mp4"
        val lookup = subtitleLookupOf(entry(MediaSource.SAF_TREE, docUri))

        assertEquals(SubtitleLookup.SafDocument(docUri), lookup)
        // 日志里指位置用的是 document uri 原文：这正是排查时要去对照的东西。
        assertEquals(docUri, lookup.where)
    }

    @Test
    fun `SAF 条目也不去查 MediaStore`() {
        // 用户授权 SAF 的典型动机就是「MediaStore 看不见的地方」（云盘、受限的 SD 卡目录）。
        // 有 relativePath 就改去查媒体库，等于把这条来源存在的理由抹掉，
        // 而且失败得完全安静：查回 0 行，界面说「这个文件夹里没有字幕」。
        val docUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fmovie.mp4"
        val lookup = subtitleLookupOf(
            entry(MediaSource.SAF_TREE, docUri, relativePath = "Movies/"),
        )

        assertEquals(SubtitleLookup.SafDocument(docUri), lookup)
    }

    // --------------------------------------------------------------- 媒体库

    @Test
    fun `媒体库条目用相对路径去查索引`() {
        val lookup = subtitleLookupOf(
            entry(MediaSource.MEDIA_STORE, "content://media/external/video/media/7", "Movies/"),
        )

        assertEquals(SubtitleLookup.MediaStoreDirectory("Movies/"), lookup)
    }

    @Test
    fun `媒体库条目没有相对路径时拿不到位置`() {
        // Android 9 及以下没有 RELATIVE_PATH 这一列。
        assertEquals(
            SubtitleLookup.Unavailable,
            subtitleLookupOf(
                entry(MediaSource.MEDIA_STORE, "content://media/external/video/media/7"),
            ),
        )
    }

    @Test
    fun `空白的相对路径不算数`() {
        // `"   "` 和 null 是一回事：拿它去拼 SQL 会查出所有目录（甚至报错），
        // 那是「没有位置」而不是「所有位置」。
        assertEquals(
            SubtitleLookup.Unavailable,
            subtitleLookupOf(
                entry(MediaSource.MEDIA_STORE, "content://media/external/video/media/7", "   "),
            ),
        )
    }

    // --------------------------------------------------------- 没有本地目录的

    @Test
    fun `网络与分享进来的条目没有目录可查`() {
        // 这两类都不该去猜一个本地目录：
        // - 网络条目（m3u8 之类）根本没有本地文件夹；
        // - 分享进来的条目只有别人给的 content uri，它未必在媒体库索引里，
        //   拿相对路径去查会命中的**可能是别人的同名文件**，然后自动挂上一条
        //   与这条媒体毫无关系的字幕。
        assertEquals(
            SubtitleLookup.Unavailable,
            subtitleLookupOf(entry(MediaSource.REMOTE, "https://example.com/movie.m3u8")),
        )
        assertEquals(
            SubtitleLookup.Unavailable,
            subtitleLookupOf(
                entry(MediaSource.SHARED, "content://com.other.app/file/123", "Download/"),
            ),
        )
    }
}
