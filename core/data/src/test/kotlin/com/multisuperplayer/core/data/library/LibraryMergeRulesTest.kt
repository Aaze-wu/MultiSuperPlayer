package com.multisuperplayer.core.data.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 多来源合并去重的单元测试。
 *
 * 去重写反的表现是**「媒体库里每首歌都出现两次」**——一个看起来像「你手机里
 * 真有两份」的现象，用户完全无法判断该删哪一个。反过来，去重做过头会把用户
 * 真实存在的文件藏起来。两个方向都要锁住。
 */
class LibraryMergeRulesTest {

    private fun entry(
        id: String,
        name: String,
        path: String?,
        size: Long,
        source: MediaSource = MediaSource.MEDIA_STORE,
        artist: String? = null,
    ) = MediaEntry(
        id = id,
        uri = "content://test/$id",
        title = name,
        kind = MediaKind.AUDIO,
        source = source,
        artist = artist,
        displayName = name,
        relativePath = path,
        sizeBytes = size,
    )

    private fun merge(mediaStore: List<MediaEntry>, saf: List<MediaEntry>) =
        LibraryMergeRules.merge(mediaStore, saf)

    @Test
    fun `同路径同名同大小视为同一个文件`() {
        val store = listOf(entry("audio:1", "a.mp3", "Music/", 1024L, artist = "某歌手"))
        val saf = listOf(entry("saf:primary:Music/a.mp3", "a.mp3", "Music/", 1024L, MediaSource.SAF_TREE))

        val merged = merge(store, saf)

        assertEquals(1, merged.size)
        // 冲突时保留 MediaStore 那条：它有 artist。
        assertEquals("audio:1", merged.single().id)
        assertEquals("某歌手", merged.single().artist)
    }

    @Test
    fun `大小不同时视为两个文件`() {
        val store = listOf(entry("audio:1", "a.mp3", "Music/", 1024L))
        val saf = listOf(entry("saf:primary:Music/a.mp3", "a.mp3", "Music/", 2048L, MediaSource.SAF_TREE))

        assertEquals(2, merge(store, saf).size)
    }

    @Test
    fun `路径不同时视为两个文件`() {
        val store = listOf(entry("audio:1", "a.mp3", "Music/", 1024L))
        val saf = listOf(entry("saf:primary:Other/a.mp3", "a.mp3", "Other/", 1024L, MediaSource.SAF_TREE))

        assertEquals(2, merge(store, saf).size)
    }

    @Test
    fun `SAF 独有的文件会被加进来`() {
        val store = listOf(entry("audio:1", "a.mp3", "Music/", 1024L))
        val saf = listOf(
            entry("saf:primary:Music/b.mp3", "b.mp3", "Music/", 2048L, MediaSource.SAF_TREE),
            entry("saf:primary:Cloud/c.flac", "c.flac", "Cloud/", 4096L, MediaSource.SAF_TREE),
        )

        val merged = merge(store, saf)

        assertEquals(3, merged.size)
        assertEquals(listOf("audio:1", "saf:primary:Music/b.mp3", "saf:primary:Cloud/c.flac"),
            merged.map { it.id })
    }

    @Test
    fun `算不出身份的条目一律保留`() {
        // provider 不给大小（sizeBytes = 0）时算不出身份键。宁可多显示一条，
        // 也不要因为一个临时状态把真实存在的文件判成重复而藏起来。
        val store = listOf(entry("audio:1", "a.mp3", "Music/", 1024L))
        val saf = listOf(
            entry("saf:x1", "a.mp3", "Music/", 0L, MediaSource.SAF_TREE),
            entry("saf:x2", "a.mp3", "Music/", 0L, MediaSource.SAF_TREE),
        )

        assertEquals(3, merge(store, saf).size)
    }

    @Test
    fun `没有相对路径的条目保留`() {
        // 网络条目 / 分享进来的条目都可能是这样。
        val store = listOf(entry("audio:1", "a.mp3", "Music/", 1024L))
        val saf = listOf(entry("saf:cloud", "a.mp3", null, 1024L, MediaSource.SAF_TREE))

        assertEquals(2, merge(store, saf).size)
    }

    @Test
    fun `大小写差异不产生重复`() {
        val store = listOf(entry("audio:1", "Song.MP3", "Music/", 1024L))
        val saf = listOf(entry("saf:primary:music/song.mp3", "song.mp3", "music/", 1024L, MediaSource.SAF_TREE))

        assertEquals(1, merge(store, saf).size)
    }

    @Test
    fun `SAF 侧内部重复的去重只发生在有 MediaStore 数据时`() {
        // 用户可能把 `primary:` 和 `primary:Music` 两棵树都授权进来。
        // 同一个文件会被扫出两次——但两次的 documentId 相同，
        // 所以**扫描阶段**（SafTreeScanner 按 entry id 去重）就已经处理掉了。
        val saf = listOf(
            entry("saf:primary:Music/a.mp3", "a.mp3", "Music/", 1024L, MediaSource.SAF_TREE),
            entry("saf:primary:Music/a.mp3", "a.mp3", "Music/", 1024L, MediaSource.SAF_TREE),
        )

        // 有 MediaStore 条目时，身份键集合是共用的，SAF 内部的重复也会被去掉。
        assertEquals(2, merge(listOf(entry("audio:1", "other.mp3", "Music/", 1L)), saf).size)

        // 但 MediaStore 一侧为空时**不去重**（直接返回 SAF 那侧）。这是有意的：
        // 身份键不含卷名（`primary:Music/a.mp3` 和 `ABCD-1234:Music/a.mp3`
        // 算同一个身份），去重会把 SD 卡上那个真实文件**静默藏起来**。
        // 多显示一条用户可以自己处理，藏起来他永远发现不了。
        assertEquals(2, merge(emptyList(), saf).size)
    }

    @Test
    fun `一侧为空时直接返回另一侧`() {
        val store = listOf(entry("audio:1", "a.mp3", "Music/", 1024L))
        val saf = listOf(entry("saf:primary:Music/b.mp3", "b.mp3", "Music/", 2048L, MediaSource.SAF_TREE))

        assertEquals(store, merge(store, emptyList()))
        assertEquals(saf, merge(emptyList(), saf))
        assertEquals(emptyList<MediaEntry>(), merge(emptyList(), emptyList()))
    }

    @Test
    fun `MediaStore 的条目顺序被完整保留`() {
        val store = listOf(
            entry("audio:1", "b.mp3", "Music/", 1L),
            entry("audio:2", "a.mp3", "Music/", 2L),
        )
        val saf = listOf(entry("saf:primary:Music/a.mp3", "a.mp3", "Music/", 2L, MediaSource.SAF_TREE))

        val merged = merge(store, saf)

        // a.mp3 已经被 MediaStore 覆盖，所以 SAF 不该在后面再补一条。
        assertEquals(listOf("audio:1", "audio:2"), merged.map { it.id })
    }
}
