package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表编解码的单元测试。
 *
 * 两个方向都不能出错，而且**失败方式很不对称**：
 * - 编码少转义一个字符 → 用户的「A|B」标题变成两条记录 / 一条断掉的列表；
 * - 解码抛异常 → 播放列表页直接打不开，用户丢掉的是**所有**列表。
 *
 * 所以测试里既有「特殊字符往返」，也有「坏数据只影响它自己」。
 */
class PlaylistCodecTest {

    private val record = PlaylistCodec.RECORD_SEPARATOR

    private fun item(
        mediaId: String = "audio:1",
        uri: String = "content://media/external/audio/media/1",
        title: String = "标题",
        artist: String? = "歌手",
        durationMs: Long = 200_000L,
        kind: MediaKind = MediaKind.AUDIO,
    ) = PlaylistItem(mediaId, uri, title, artist, durationMs, kind)

    private fun playlist(
        id: String = "pl-abc123",
        name: String = "我喜欢的",
        createdAtMs: Long = 1_700_000_000_000L,
        items: List<PlaylistItem> = listOf(item()),
    ) = Playlist(id, name, createdAtMs, items)

    // --------------------------------------------------------------- 往返

    @Test
    fun `普通播放列表可以往返`() {
        val original = playlist(items = listOf(item(), item(mediaId = "video:9", kind = MediaKind.VIDEO)))

        assertEquals(original, PlaylistCodec.decode(PlaylistCodec.encode(original)))
    }

    @Test
    fun `空播放列表也能往返`() {
        val original = playlist(items = emptyList())

        val restored = PlaylistCodec.decode(PlaylistCodec.encode(original))

        assertEquals(original, restored)
        assertTrue(restored!!.isEmpty)
    }

    @Test
    fun `特殊字符在往返后原样保留`() {
        val tricky = "A|B\\C" + '\n' + "D" + '\r' + "E" + record + "F"
        val original = playlist(
            name = "名字里有 | 和 \\ 和换行",
            items = listOf(item(title = tricky, artist = "歌手|另一个\\人")),
        )

        val restored = PlaylistCodec.decode(PlaylistCodec.encode(original))

        assertEquals(original, restored)
        assertEquals(tricky, restored!!.items.single().title)
    }

    @Test
    fun `编码结果是单行可打印的`() {
        val encoded = PlaylistCodec.encode(
            playlist(items = listOf(item(title = "第一行\n第二行"))),
        )

        // 记录之间用 RS 分隔，字段里的换行被换成 `\n` 两个字符——
        // 这样翻日志时能直接看懂，也不会因为换行被误当成记录边界。
        assertTrue(encoded.none { it == '\n' || it == '\r' })
        assertTrue(encoded.contains("\\n"))
    }

    @Test
    fun `没有艺术家的条目往返后仍是 null`() {
        val original = playlist(items = listOf(item(artist = null)))

        val restored = PlaylistCodec.decode(PlaylistCodec.encode(original))

        // 空字符串和 null 在界面上都是「未知」，但存储层不该悄悄把 null 变成 ""。
        assertNull(restored!!.items.single().artist)
    }

    @Test
    fun `条目里的 uri 和 id 都保留`() {
        val original = playlist(
            items = listOf(item(mediaId = "saf:primary:Music/a.mp3", uri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.mp3")),
        )

        val restored = PlaylistCodec.decode(PlaylistCodec.encode(original))

        assertEquals("saf:primary:Music/a.mp3", restored!!.items.single().mediaId)
        assertTrue(restored.items.single().uri.startsWith("content://com.android.externalstorage"))
    }

    // --------------------------------------------------------------- 坏数据

    @Test
    fun `空输入解码成 null`() {
        assertNull(PlaylistCodec.decode(null))
        assertNull(PlaylistCodec.decode(""))
    }

    @Test
    fun `头部字段数量不对时整条作废`() {
        // 字段数量不对 = 格式对不上，而不是「字段是空的」。
        assertNull(PlaylistCodec.decode("pl-abc123|名字"))
        assertNull(PlaylistCodec.decode("pl-abc123|名字|1000|多余"))
    }

    @Test
    fun `id 为空时整条作废`() {
        assertNull(PlaylistCodec.decode("|名字|1000"))
        assertNull(PlaylistCodec.decode("  |名字|1000"))
    }

    @Test
    fun `创建时间不是数字时整条作废`() {
        assertNull(PlaylistCodec.decode("pl-abc123|名字|昨天"))
    }

    @Test
    fun `坏掉的条目只跳过它自己`() {
        val raw = listOf(
            "pl-abc123|名字|1000",
            "audio:1|content://1|好条目|歌手|1000|AUDIO",
            "字段不够",
            "|content://2|缺 id|歌手|1000|AUDIO",
            "audio:3||缺 uri|歌手|1000|AUDIO",
            "audio:4|content://4|后面这条也要活下来|歌手|1000|AUDIO",
        ).joinToString(record.toString())

        val restored = PlaylistCodec.decode(raw)

        assertEquals(listOf("audio:1", "audio:4"), restored!!.items.map { it.mediaId })
    }

    @Test
    fun `时长不是数字时按 0 处理而不是丢掉整条`() {
        val raw = "pl-abc123|名字|1000$record" +
            "audio:1|content://1|标题|歌手|未知|AUDIO"

        val restored = PlaylistCodec.decode(raw)

        // 时长读不出来只是「进度条画不准」，没理由让这条记录消失。
        assertEquals(0L, restored!!.items.single().durationMs)
    }

    @Test
    fun `认不出来的类型按 UNKNOWN`() {
        // MediaKind 以后加值时，旧版本读到新记录不该整条作废。
        val raw = "pl-abc123|名字|1000$record" +
            "audio:1|content://1|标题|歌手|1000|SOMETHING_NEW"

        val restored = PlaylistCodec.decode(raw)

        assertEquals(MediaKind.UNKNOWN, restored!!.items.single().kind)
    }

    @Test
    fun `只有头部没有条目也能读出来`() {
        val restored = PlaylistCodec.decode("pl-abc123|名字|1000")

        assertTrue(restored!!.items.isEmpty())
    }

    @Test
    fun `未知的转义序列按字面量收下`() {
        // 以后加一个转义字符时，旧版本读到新格式也不该整条作废。
        val raw = "pl-abc123|名\\q字|1000"

        assertEquals("名q字", PlaylistCodec.decode(raw)!!.name)
    }

    @Test
    fun `字段多于六个的条目被跳过`() {
        val raw = "pl-abc123|名字|1000$record" +
            "audio:1|content://1|标题|歌手|1000|AUDIO|多余"

        assertTrue(PlaylistCodec.decode(raw)!!.items.isEmpty())
    }
}
