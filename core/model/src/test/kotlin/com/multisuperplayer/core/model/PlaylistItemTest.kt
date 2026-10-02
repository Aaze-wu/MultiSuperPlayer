package com.multisuperplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PlaylistItem] 回放时用的那条推导线：`mediaId` → 来源。
 *
 * 为什么值得单独测：这里错了不会抛异常，只会让**同目录字幕查不到**。
 * 浏览页加进去的文件（`file:` 前缀）如果被当成媒体库条目，字幕查找就会去问
 * MediaStore 要相对路径——而那个文件压根不在媒体库里。
 */
class PlaylistItemTest {

    @Test
    fun `浏览页加进来的条目回放时仍然是文件系统来源`() {
        val entry = BrowserEntry(
            ref = "/storage/emulated/0/Movies/movie.mp4",
            name = "movie.mp4",
            isDirectory = false,
            kind = MediaKind.VIDEO,
        )
        val media = requireNotNull(entry.toMediaEntry())

        val restored = PlaylistItem.of(media).toEntry()

        assertEquals(MediaSource.FILE_SYSTEM, restored.source)
        // uri / id 原样带过去：播放器要的是路径，字幕查找要的是它的上一级目录。
        assertEquals(media.uri, restored.uri)
        assertEquals(media.id, restored.id)
    }

    @Test
    fun `媒体库的 id 仍然是媒体库来源`() {
        val item = PlaylistItem(mediaId = "12345", uri = "content://media/external/video/media/12345", title = "movie")

        assertEquals(MediaSource.MEDIA_STORE, PlaylistItem.sourceOf(item.mediaId))
        assertEquals(MediaSource.MEDIA_STORE, item.toEntry().source)
    }

    @Test
    fun `SAF 的 id 不会被误判成文件系统`() {
        // `saf:` 开头的 id 只有 MediaSource.SAF_TREE 认得，按文件路径去查字幕同样是查不到。
        // 判据必须是「是不是我们写进去的那个前缀」，不能是「看起来像不像路径」。
        val id = "saf:primary:Music/album/a.flac"
        assertEquals(MediaSource.MEDIA_STORE, PlaylistItem.sourceOf(id))
        assertEquals(MediaSource.MEDIA_STORE, PlaylistItem(mediaId = id, uri = id, title = "a").toEntry().source)
    }

    @Test
    fun `显式传入的来源优先`() {
        // 调用方比 id 更清楚的时候（比如从媒体库直接构造）留了一个覆盖口。
        val item = PlaylistItem(mediaId = "12345", uri = "content://x", title = "movie")
        assertEquals(MediaSource.SHARED, item.toEntry(MediaSource.SHARED).source)
    }
}
