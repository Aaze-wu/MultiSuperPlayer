package com.multisuperplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条记录能不能取封面、去哪儿取」。
 *
 * 判错的代价不对称，所以这一组必须钉死：判成「不能取」只是少一张图，
 * 判成「能取」会让**每一行**都挂一次注定失败的取图，而且列表每次滚回来
 * 都重试一遍。这种退化在实机上表现为「列表稍微有点卡」，翻不出来的。
 *
 * 另外两条重载（[MediaEntry] 与 [BrowserEntry]）对「什么算合法 uri」给出的
 * 答案必须一致，否则会出现「媒体库页有封面、浏览页没有」这种查不出原因的差异。
 */
class ArtworkSourceRulesTest {

    private val contentUri = "content://media/external/audio/media/42"

    private fun mediaEntry(
        uri: String? = contentUri,
        kind: MediaKind = MediaKind.AUDIO,
        source: MediaSource = MediaSource.MEDIA_STORE,
        artworkUri: String? = null,
    ) = MediaEntry(
        id = "id-1",
        uri = uri.orEmpty(),
        title = "标题",
        kind = kind,
        source = source,
        artworkUri = artworkUri,
    )

    private fun browserEntry(
        ref: String = "/storage/emulated/0/Music/song.mp3",
        kind: MediaKind? = MediaKind.AUDIO,
        isDirectory: Boolean = false,
    ) = BrowserEntry(ref = ref, name = "song.mp3", isDirectory = isDirectory, kind = kind)

    // ---- 类型判据 ------------------------------------------------------------

    @Test
    fun `只有音视频要封面`() {
        assertTrue(ArtworkSourceRules.wantsArtwork(MediaKind.AUDIO))
        assertTrue(ArtworkSourceRules.wantsArtwork(MediaKind.VIDEO))
        // UNKNOWN 连自己是音频还是视频都不知道，给它抽帧是在猜。
        assertFalse(ArtworkSourceRules.wantsArtwork(MediaKind.UNKNOWN))
    }

    @Test
    fun `类型未知时不发请求`() {
        assertNull(ArtworkSourceRules.requestFor(contentUri, MediaKind.UNKNOWN, MediaSource.MEDIA_STORE))
        assertNull(ArtworkSourceRules.requestFor(mediaEntry(kind = MediaKind.UNKNOWN)))
        assertNull(ArtworkSourceRules.requestFor(browserEntry(kind = MediaKind.UNKNOWN)))
    }

    // ---- 来源判据 ------------------------------------------------------------

    @Test
    fun `网络来源一律不取`() {
        // REMOTE 是本机没有的东西：既抽不了帧，也不该为了一个列表缩略图
        // 把整个视频下下来。这条是整个规则里最该守住的一条。
        assertNull(ArtworkSourceRules.requestFor("https://example.com/a.mp4", MediaKind.VIDEO, MediaSource.REMOTE))
        assertNull(ArtworkSourceRules.requestFor(mediaEntry(source = MediaSource.REMOTE)))
    }

    @Test
    fun `本机来源都放行`() {
        // 媒体库、SAF、分享进来的、文件浏览器打开的——四种都是本机可打开的 uri，
        // 取不到的时候有兜底图标，白跑一次的成本只有一次失败的解码。
        listOf(
            MediaSource.MEDIA_STORE,
            MediaSource.SAF_TREE,
            MediaSource.SHARED,
            MediaSource.FILE_SYSTEM,
        ).forEach { source ->
            assertNotNull("$source 应当放行", ArtworkSourceRules.requestFor(contentUri, MediaKind.AUDIO, source))
        }
    }

    // ---- uri 判据 ------------------------------------------------------------

    @Test
    fun `uri 缺失或全是空白时不发请求`() {
        assertNull(ArtworkSourceRules.requestFor(null, MediaKind.AUDIO, MediaSource.MEDIA_STORE))
        assertNull(ArtworkSourceRules.requestFor("", MediaKind.AUDIO, MediaSource.MEDIA_STORE))
        assertNull(ArtworkSourceRules.requestFor("   ", MediaKind.AUDIO, MediaSource.MEDIA_STORE))
        assertNull(ArtworkSourceRules.requestFor(mediaEntry(uri = null)))
        assertNull(ArtworkSourceRules.requestFor(mediaEntry(uri = "")))
    }

    @Test
    fun `uri 首尾空白会被去掉`() {
        val request = ArtworkSourceRules.requestFor("  $contentUri  ", MediaKind.AUDIO, MediaSource.MEDIA_STORE)
        assertEquals(contentUri, request?.uri)
    }

    // ---- 媒体条目 ------------------------------------------------------------

    @Test
    fun `条目自带 artworkUri 时优先用它`() {
        val request = ArtworkSourceRules.requestFor(
            mediaEntry(uri = contentUri, artworkUri = "content://media/external/audio/albumart/9"),
        )
        assertEquals("content://media/external/audio/albumart/9", request?.uri)
    }

    @Test
    fun `条目没有 artworkUri 时兜底到 uri`() {
        // 这条兜底不是「猜一个值」：播放列表里的条目（PlaylistItem.toEntry()）
        // 根本不带 artworkUri 字段，而它的 uri 就是那个 content uri。
        // 少了它会出现「播放列表页有封面、媒体库页没有」这种查不出原因的差异。
        val request = ArtworkSourceRules.requestFor(mediaEntry(uri = contentUri, artworkUri = null))
        assertEquals(contentUri, request?.uri)
    }

    @Test
    fun `artworkUri 是空白时也兜底到 uri`() {
        // 空字符串和 null 在这里必须同待遇：MediaStore 的 albumArt 列
        // 在某些机器上给的是空串而不是 null。
        val request = ArtworkSourceRules.requestFor(mediaEntry(uri = contentUri, artworkUri = "   "))
        assertEquals(contentUri, request?.uri)
    }

    @Test
    fun `兜底也不会把类型和来源的判据绕过去`() {
        // 兜底只解决「去哪儿取」，不该顺带把「该不该取」也放过去。
        assertNull(ArtworkSourceRules.requestFor(mediaEntry(uri = contentUri, kind = MediaKind.UNKNOWN)))
        assertNull(ArtworkSourceRules.requestFor(mediaEntry(uri = contentUri, source = MediaSource.REMOTE)))
    }

    @Test
    fun `类型进请求`() {
        assertEquals(MediaKind.VIDEO, ArtworkSourceRules.requestFor(mediaEntry(kind = MediaKind.VIDEO))?.kind)
    }

    // ---- 浏览条目 ------------------------------------------------------------

    @Test
    fun `目录不取封面`() {
        assertNull(ArtworkSourceRules.requestFor(browserEntry(isDirectory = true)))
    }

    @Test
    fun `明确不是媒体的文件不取封面`() {
        // .lrc / .txt / .zip 的 kind 是 null 而不是 UNKNOWN，
        // 判据必须落在 playable 上，不能自己看后缀。
        assertFalse(browserEntry(kind = null).playable)
        assertNull(ArtworkSourceRules.requestFor(browserEntry(kind = null)))
    }

    @Test
    fun `可播文件用 ref 当 uri`() {
        // 浏览页的 ref 是最「可打开」的一种：绝对路径或 SAF document uri，
        // 两种都能直接交给系统解码，所以这一条不做来源判断。
        val path = "/storage/emulated/0/Movies/movie.mp4"
        val request = ArtworkSourceRules.requestFor(browserEntry(ref = path, kind = MediaKind.VIDEO))
        assertEquals(path, request?.uri)
        assertEquals(MediaKind.VIDEO, request?.kind)
    }

    @Test
    fun `浏览条目的 ref 是空白时也不发请求`() {
        // 两条重载对「空 uri」必须给同一个答案，否则读代码的人会以为
        // 浏览页那条路是故意不做校验的。
        assertNull(ArtworkSourceRules.requestFor(browserEntry(ref = "  ")))
    }
}
