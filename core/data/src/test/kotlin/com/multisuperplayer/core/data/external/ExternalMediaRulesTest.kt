package com.multisuperplayer.core.data.external

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「外部打开 / 分享」的读取规则。
 *
 * 这些取舍**只能**在这里验：规则层不碰 `Intent`（`android.jar` 在 JVM 单测里只有空壳），
 * 而真机上的错误形态又很难认——分享一个文件夹、里面混着 `.txt` 和 `.jpg`，
 * 界面上只会表现为「下一首卡住」。所以「哪些该收、哪些该扔、类型怎么判」全在这里钉住。
 */
class ExternalMediaRulesTest {

    private fun read(
        action: String?,
        mimeType: String? = null,
        data: String? = null,
        streams: List<String> = emptyList(),
    ): ExternalPayload? = ExternalMediaRules.read(action, mimeType, data, streams)

    @Test
    fun `VIEW 看的是 data，来源由协议决定`() {
        val remote = read(ExternalMediaRules.ACTION_VIEW, data = "https://host/dir/a.mp4")
        assertEquals(MediaSource.REMOTE, remote?.refs?.single()?.source)
        assertEquals(MediaKind.VIDEO, remote?.refs?.single()?.kind)

        val shared = read(ExternalMediaRules.ACTION_VIEW, data = "content://com.x/video/9")
        assertEquals(MediaSource.SHARED, shared?.refs?.single()?.source)
    }

    @Test
    fun `http 与 https 都算远端，大小写不敏感`() {
        assertEquals(MediaSource.REMOTE, ExternalMediaRules.sourceOf("http://a.com/b.mp4"))
        assertEquals(MediaSource.REMOTE, ExternalMediaRules.sourceOf("HTTPS://a.com/b.mp4"))
    }

    @Test
    fun `路径里带 http 的 content uri 不是远端`() {
        // 只看协议那一段：`content://com.x/https://a.mp4` 是个 content uri，
        // 用「包不包含 http」来判会把它错判成远端，结果是它的读权限、字幕查找
        // 全按远端处理，而它其实是个本地文件。
        assertEquals(MediaSource.SHARED, ExternalMediaRules.sourceOf("content://com.x/https://a.mp4"))
        assertEquals(MediaSource.SHARED, ExternalMediaRules.sourceOf("file:///sdcard/a.mp4"))
    }

    @Test
    fun `SEND 看的是 stream，来源一律是分享`() {
        val payload = requireNotNull(
            read(
                ExternalMediaRules.ACTION_SEND,
                mimeType = "video/mp4",
                streams = listOf("content://com.x/1"),
            ),
        )
        assertEquals(1, payload.refs.size)
        assertEquals(MediaSource.SHARED, payload.refs.single().source)
        // VIEW 的 data 在 SEND 里不会被看（SEND 的 data 常常是空或者无关的东西）。
        assertNull(read(ExternalMediaRules.ACTION_SEND, data = "content://com.x/1"))
    }

    @Test
    fun `SEND_MULTIPLE 里每条 uri 各判各的来源`() {
        // 一次分享「一个本地文件 + 一条链接」是完全可能的，整批用一个来源会错一半。
        val payload = read(
            ExternalMediaRules.ACTION_SEND_MULTIPLE,
            streams = listOf("content://com.x/1", "https://host/b.mp4"),
        )
        assertEquals(
            listOf(MediaSource.SHARED, MediaSource.REMOTE),
            payload?.refs?.map { it.source },
        )
    }

    @Test
    fun `重复的 uri 会去重`() {
        // 队列里出现两条一模一样的条目会让「下一首」看起来像没反应。
        val payload = read(
            ExternalMediaRules.ACTION_SEND_MULTIPLE,
            streams = listOf("content://com.x/1", "content://com.x/1", "content://com.x/2"),
        )
        assertEquals(listOf("content://com.x/1", "content://com.x/2"), payload?.refs?.map { it.uri })
    }

    @Test
    fun `不认识的动作不产生请求`() {
        // manifest 只声明了 VIEW / SEND / SEND_MULTIPLE，别的动作说明是被人显式点名
        // 发过来的（多半是调试），按「不认识」处理比猜一个语义好。
        assertNull(read("android.intent.action.MAIN", data = "content://com.x/1"))
        assertNull(read("android.intent.action.OPEN_DOCUMENT", data = "content://com.x/1"))
        assertNull(read(null, data = "content://com.x/1"))
    }

    @Test
    fun `一个 uri 都没有时不产生请求`() {
        assertNull(read(ExternalMediaRules.ACTION_VIEW))
        assertNull(read(ExternalMediaRules.ACTION_VIEW, data = "   "))
        assertNull(read(ExternalMediaRules.ACTION_SEND, streams = emptyList()))
        assertNull(read(ExternalMediaRules.ACTION_SEND_MULTIPLE, streams = listOf("", "  ")))
    }

    @Test
    fun `发送方声明的 MIME 会去掉参数部分`() {
        val payload = read(
            ExternalMediaRules.ACTION_SEND,
            mimeType = "video/mp4; charset=utf-8",
            streams = listOf("content://com.x/1"),
        )
        // `;` 后面是 charset 之类的参数（`SafScanRules.kindOf` 里也是这么比的）。
        assertEquals("video/mp4", payload?.mimeType)
    }

    @Test
    fun `分享进来的非媒体后缀会被剔掉`() {
        // 一次分享一叠文件是常见操作（整张相册、整个文件夹），把 `.txt` / `.jpg`
        // 混进播放队列只会让「下一首」卡在一个必然失败的条目上。
        assertNull(read(ExternalMediaRules.ACTION_SEND, streams = listOf("content://com.x/a.txt")))
        assertNull(read(ExternalMediaRules.ACTION_SEND, streams = listOf("content://com.x/a.jpg")))
        val mixed = read(
            ExternalMediaRules.ACTION_SEND,
            streams = listOf("content://com.x/a.txt", "content://com.x/b.mp4"),
        )
        assertEquals(listOf("content://com.x/b.mp4"), mixed?.refs?.map { it.uri })
    }

    @Test
    fun `网络地址即使后缀不是媒体也保留`() {
        // 和 `RemoteUrlRules.kindOf` 对同一件事的取舍一致：用户主动填进来的一条地址，
        // 「地址后面不是媒体」交给内核去报错，比我们把它变成一个安静的空结果好。
        val payload = read(ExternalMediaRules.ACTION_VIEW, data = "https://host/a.txt")
        assertEquals(MediaKind.UNKNOWN, payload?.refs?.single()?.kind)
    }

    @Test
    fun `没有后缀的 content uri 照样收下，类型未知`() {
        // `…/video/media/123` 判不出类型很正常：真正的文件名要靠 provider 的
        // DISPLAY_NAME 补（`ExternalMediaResolver`）。这里不能因为「判不出」就扔掉。
        val payload = requireNotNull(
            read(ExternalMediaRules.ACTION_VIEW, data = "content://com.x/video/media/123"),
        )
        assertEquals(1, payload.refs.size)
        assertEquals(MediaKind.UNKNOWN, payload.refs.single().kind)
    }

    @Test
    fun `文件名与网络地址是同一条规则`() {
        assertEquals("a.mp4", ExternalMediaRules.fileNameOf("content://com.x/dir/a.mp4"))
        assertEquals("My Movie.mp4", ExternalMediaRules.fileNameOf("content://com.x/dir/My%20Movie.mp4?x=1"))
    }
}
