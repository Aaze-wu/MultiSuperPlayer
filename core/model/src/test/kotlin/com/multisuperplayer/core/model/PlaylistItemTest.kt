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

    @Test
    fun `手输的网络地址回放时仍然是远端来源`() {
        // 和上面那个 SAF 用例是同一条原则：判据是「是不是我们写进去的那个前缀」。
        // 判成媒体库的话，字幕查找会去问 MediaStore 要这条 http 地址的「同目录」。
        val url = "https://host/dir/a.mp4"
        val id = ExternalMediaIds.remoteIdOf(url)

        assertEquals("url:https://host/dir/a.mp4", id)
        assertEquals(MediaSource.REMOTE, PlaylistItem.sourceOf(id))
        assertEquals(
            MediaSource.REMOTE,
            PlaylistItem(mediaId = id, uri = url, title = "a").toEntry().source,
        )
    }

    @Test
    fun `分享进来的 content uri 不会被当成媒体库条目`() {
        // 两者都长成 `content://…`，只能靠前缀区分：`shared:` 是外部应用递进来的
        // （读权限跟着那次调用，和 MediaStore 无关），不带前缀的才是媒体库。
        // 认错同样不会报错，只会让同目录字幕去问 MediaStore 要一个不存在的相对路径。
        val uri = "content://com.other.app/video/9"
        val id = ExternalMediaIds.sharedIdOf(uri)

        assertEquals(MediaSource.SHARED, PlaylistItem.sourceOf(id))
        assertEquals(
            MediaSource.SHARED,
            PlaylistItem(mediaId = id, uri = uri, title = "v").toEntry().source,
        )
    }

    @Test
    fun `四个来源的前缀互不包含`() {
        // `sourceOf` 用的是 when + 前缀判断，分支顺序不影响结果的前提正是
        // 「没有任何一个前缀是另一个的前缀」。这条用例把这个前提钉在原地，
        // 将来谁改了前缀（比如把 `url:` 改成 `u:`）会立刻失败。
        assertEquals(MediaSource.FILE_SYSTEM, PlaylistItem.sourceOf(BrowserEntry.mediaIdOf("/sdcard/a.mp4")))
        assertEquals(MediaSource.REMOTE, PlaylistItem.sourceOf(ExternalMediaIds.remoteIdOf("http://a/b.mp4")))
        assertEquals(MediaSource.SHARED, PlaylistItem.sourceOf(ExternalMediaIds.sharedIdOf("content://a/b.mp4")))
        // SAF 的 id 里带 `:`，但它不带任何前缀 ⇒ 落到最保守的分支。
        assertEquals(MediaSource.MEDIA_STORE, PlaylistItem.sourceOf("saf:primary:Music/a.flac"))
    }

    @Test
    fun `浏览页里的 SAF 条目按 ref 回到 SAF 来源`() {
        // 浏览页的 id 一律带 `file:` 前缀，但前缀后面可能是绝对路径、也可能是
        // 系统文件选择器给的 document uri。前者直接列上一级目录，后者要去问
        // provider 要兄弟文件——混为一谈的后果是**整个目录**的字幕都找不到，
        // 而且不报任何错（与文件名无关，所以很难联想到是来源判错）。
        val safRef = "content://com.android.externalstorage.documents/tree/primary%3AMovies" +
            "/document/primary%3AMovies%2Fmovie.mp4"

        assertEquals(MediaSource.SAF_TREE, PlaylistItem.sourceOf(BrowserEntry.mediaIdOf(safRef)))
        assertEquals(
            MediaSource.SAF_TREE,
            PlaylistItem(mediaId = BrowserEntry.mediaIdOf(safRef), uri = safRef, title = "movie").toEntry().source,
        )
    }
}
