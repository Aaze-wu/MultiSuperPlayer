package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放列表规则的单元测试。
 *
 * 每条规则都容易写反，而写反之后界面上一切正常：列表还是列表，
 * 只是「同一个文件加两次变成两行」，或者「删一条删掉了两条」。
 */
class PlaylistRulesTest {

    private fun item(mediaId: String, title: String = mediaId, artist: String? = null) =
        PlaylistItem(
            mediaId = mediaId,
            uri = "content://test/$mediaId",
            title = title,
            artist = artist,
            durationMs = 1000L,
            kind = MediaKind.AUDIO,
        )

    private fun entry(id: String, title: String, kind: MediaKind = MediaKind.AUDIO) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = kind,
        source = MediaSource.MEDIA_STORE,
    )

    private fun playlist(id: String, createdAtMs: Long) =
        Playlist(id = id, name = id, createdAtMs = createdAtMs)

    // --------------------------------------------------------------- 名字清洗

    @Test
    fun `名字去掉首尾空白并截断`() {
        assertEquals("我喜欢的", PlaylistRules.sanitizeName("  我喜欢的  "))
        assertEquals("", PlaylistRules.sanitizeName("   "))
        assertEquals(PlaylistRules.MAX_NAME_LENGTH, PlaylistRules.sanitizeName("あ".repeat(500)).length)
    }

    @Test
    fun `空名字留给界面决定怎么显示`() {
        // 数据层不返回「未命名」：那是一句要翻译的界面文案。
        val empty = Playlist(id = "pl-1", name = "", createdAtMs = 0L)

        assertFalse(PlaylistRules.hasUsableName(empty))
        assertTrue(PlaylistRules.hasUsableName(Playlist(id = "pl-1", name = "x", createdAtMs = 0L)))
        assertFalse(PlaylistRules.hasUsableName(Playlist(id = "pl-1", name = "   ", createdAtMs = 0L)))
    }

    // --------------------------------------------------------------- 追加

    @Test
    fun `追加接在原来的后面`() {
        val existing = listOf(item("audio:1"), item("audio:2"))
        val result = PlaylistRules.withAdded(existing, listOf(item("audio:3")))

        assertEquals(listOf("audio:1", "audio:2", "audio:3"), result.map { it.mediaId })
    }

    @Test
    fun `已经在列表里的条目不会被重复加入`() {
        val existing = listOf(item("audio:1"), item("audio:2"))
        val result = PlaylistRules.withAdded(existing, listOf(item("audio:2"), item("audio:3")))

        assertEquals(listOf("audio:1", "audio:2", "audio:3"), result.map { it.mediaId })
    }

    @Test
    fun `一次传入里自身的重复也会被去掉`() {
        // 「全选 → 加入播放列表」这种操作很容易把同一个文件带进来两次。
        val result = PlaylistRules.withAdded(
            emptyList(),
            listOf(item("audio:1"), item("audio:1"), item("audio:2")),
        )

        assertEquals(listOf("audio:1", "audio:2"), result.map { it.mediaId })
    }

    @Test
    fun `已有的条目不会被新快照替换`() {
        val existing = listOf(item("audio:1", title = "SAF 里看到的名字", artist = "旧歌手"))
        val incoming = listOf(item("audio:1", title = "MediaStore 里看到的名字", artist = "新歌手"))

        val result = PlaylistRules.withAdded(existing, incoming)

        // 静默替换会让「我在播放列表里看到的名字」莫名其妙地变。
        assertEquals(1, result.size)
        assertEquals("SAF 里看到的名字", result.single().title)
        assertEquals("旧歌手", result.single().artist)
    }

    @Test
    fun `mediaId 为空的条目被丢掉`() {
        val result = PlaylistRules.withAdded(emptyList(), listOf(item(""), item("  "), item("audio:1")))

        assertEquals(listOf("audio:1"), result.map { it.mediaId })
    }

    @Test
    fun `什么都不用加时返回原对象`() {
        val existing = listOf(item("audio:1"))

        assertSame(existing, PlaylistRules.withAdded(existing, emptyList()))
        assertSame(existing, PlaylistRules.withAdded(existing, listOf(item("audio:1"))))
    }

    @Test
    fun `超过上限的部分被丢弃`() {
        val existing = (0 until PlaylistRules.MAX_ITEMS - 1).map { item("audio:$it") }
        val incoming = listOf(item("audio:extra1"), item("audio:extra2"), item("audio:extra3"))

        val result = PlaylistRules.withAdded(existing, incoming)

        assertEquals(PlaylistRules.MAX_ITEMS, result.size)
        assertEquals("audio:extra1", result.last().mediaId)
    }

    // --------------------------------------------------------------- 删除

    @Test
    fun `按 id 删除条目`() {
        val items = listOf(item("audio:1"), item("audio:2"), item("audio:3"))

        val result = PlaylistRules.withRemoved(items, listOf("audio:2"))

        assertEquals(listOf("audio:1", "audio:3"), result.map { it.mediaId })
    }

    @Test
    fun `一次删多条`() {
        val items = listOf(item("audio:1"), item("audio:2"), item("audio:3"))

        val result = PlaylistRules.withRemoved(items, listOf("audio:1", "audio:3"))

        assertEquals(listOf("audio:2"), result.map { it.mediaId })
    }

    @Test
    fun `要删的 id 不存在时返回原对象`() {
        // 调用方按 `!==` 判断「不用写盘了」。
        val items = listOf(item("audio:1"))
        val empty = emptyList<PlaylistItem>()

        assertSame(items, PlaylistRules.withRemoved(items, listOf("audio:999")))
        assertSame(items, PlaylistRules.withRemoved(items, emptyList()))
        assertSame(empty, PlaylistRules.withRemoved(empty, listOf("audio:1")))
    }

    // --------------------------------------------------------------- 排序

    @Test
    fun `向后移动`() {
        val items = listOf(item("a"), item("b"), item("c"))

        val result = PlaylistRules.move(items, from = 0, to = 2)

        assertEquals(listOf("b", "c", "a"), result.map { it.mediaId })
    }

    @Test
    fun `向前移动`() {
        val items = listOf(item("a"), item("b"), item("c"))

        val result = PlaylistRules.move(items, from = 2, to = 0)

        assertEquals(listOf("c", "a", "b"), result.map { it.mediaId })
    }

    @Test
    fun `移动到原位时返回原对象`() {
        val items = listOf(item("a"), item("b"))

        assertSame(items, PlaylistRules.move(items, from = 1, to = 1))
    }

    @Test
    fun `下标越界时原样返回而不是夹到最近的位置`() {
        // 界面上拿到的下标可能已经过期（拖动期间列表被别处改了）。
        // clamp 会让条目跑到用户没指定的地方，那比不动更糟。
        val items = listOf(item("a"), item("b"))

        assertSame(items, PlaylistRules.move(items, from = -1, to = 0))
        assertSame(items, PlaylistRules.move(items, from = 0, to = 2))
        assertSame(items, PlaylistRules.move(items, from = 5, to = 0))
        assertSame(items, PlaylistRules.move(items, from = 0, to = -1))
    }

    @Test
    fun `空列表上移动不抛异常`() {
        val items = emptyList<PlaylistItem>()

        assertSame(items, PlaylistRules.move(items, from = 0, to = 0))
    }

    // --------------------------------------------------------------- 解析

    @Test
    fun `按传入顺序解析且不重排`() {
        val items = listOf(item("audio:3"), item("audio:1"), item("audio:2"))
        val byId = mapOf(
            "audio:1" to entry("audio:1", "第一首"),
            "audio:2" to entry("audio:2", "第二首"),
            "audio:3" to entry("audio:3", "第三首"),
        )

        val result = PlaylistRules.resolve(items, byId)

        // 用户排的顺序就是列表的顺序，解析只换元数据。
        assertEquals(listOf("audio:3", "audio:1", "audio:2"), result.map { it.mediaId })
        assertEquals(listOf("第三首", "第一首", "第二首"), result.map { it.title })
    }

    @Test
    fun `解析不到的条目保留快照而不是消失`() {
        // 文件被删了 / 授权失效：这一行要能显示成「文件已不在」，
        // 而不是让用户发现「我的播放列表少了一条」。
        val items = listOf(item("audio:1", title = "老名字"), item("audio:2", title = "还在"))

        val result = PlaylistRules.resolve(items, mapOf("audio:2" to entry("audio:2", "新名字")))

        assertEquals(2, result.size)
        assertEquals("老名字", result.first().title)
        assertEquals("新名字", result.last().title)
    }

    @Test
    fun `解析时类型换成媒体库里的类型`() {
        val items = listOf(item("video:1", title = "快照名字"))

        val result = PlaylistRules.resolve(
            items,
            mapOf("video:1" to entry("video:1", "视频", MediaKind.VIDEO)),
        )

        assertEquals(MediaKind.VIDEO, result.single().kind)
    }

    @Test
    fun `空列表解析成空列表`() {
        assertTrue(PlaylistRules.resolve(emptyList(), emptyMap()).isEmpty())
    }

    // ------------------------------------------------------------ 列表顺序

    @Test
    fun `没拖动过时按创建时间从早到晚`() {
        // 拖动之前的行为必须一点不变：老用户升级上来不该看到列表重排。
        val playlists = listOf(playlist("pl-b", 200L), playlist("pl-a", 100L))

        val result = PlaylistRules.applyOrder(playlists, orderedIds = emptyList())

        assertEquals(listOf("pl-a", "pl-b"), result.map { it.id })
    }

    @Test
    fun `拖动之后按拖出来的顺序而不是创建时间`() {
        val playlists = listOf(playlist("pl-a", 100L), playlist("pl-b", 200L))

        val result = PlaylistRules.applyOrder(playlists, orderedIds = listOf("pl-b", "pl-a"))

        assertEquals(listOf("pl-b", "pl-a"), result.map { it.id })
    }

    @Test
    fun `顺序里已经不存在的 id 被忽略`() {
        // 删掉一个播放列表后，那个键里还留着它的 id（删的时候不去改那个键）。
        val playlists = listOf(playlist("pl-a", 100L), playlist("pl-b", 200L))

        val result = PlaylistRules.applyOrder(playlists, listOf("pl-gone", "pl-b", "pl-a"))

        assertEquals(listOf("pl-b", "pl-a"), result.map { it.id })
    }

    @Test
    fun `拖过顺序之后新建的播放列表排在最后`() {
        // 最容易写反的一条：新建的应该接在**后面**（rank 相同时的哨兵值要比
        // 任何真实下标都大）。写反了不报错，只是新建的列表突然跑到最前面。
        val playlists = listOf(
            playlist("pl-new", 999L),
            playlist("pl-a", 100L),
            playlist("pl-b", 200L),
        )

        val result = PlaylistRules.applyOrder(playlists, listOf("pl-b", "pl-a"))

        assertEquals(listOf("pl-b", "pl-a", "pl-new"), result.map { it.id })
    }

    @Test
    fun `顺序里重复的 id 以第一次出现为准`() {
        val playlists = listOf(playlist("pl-a", 100L), playlist("pl-b", 200L))

        val result = PlaylistRules.applyOrder(playlists, listOf("pl-b", "pl-b", "pl-a"))

        assertEquals(listOf("pl-b", "pl-a"), result.map { it.id })
    }

    @Test
    fun `没有播放列表时原样返回`() {
        val playlists = emptyList<Playlist>()

        assertSame(playlists, PlaylistRules.applyOrder(playlists, listOf("pl-a")))
    }

    @Test
    fun `移动对任意列表都成立`() {
        // 条目列表和播放列表清单共用同一套算术。
        assertEquals(listOf("b", "a", "c"), PlaylistRules.move(listOf("a", "b", "c"), 0, 1))
    }

    @Test
    fun `顺序能编码成一行再解回来`() {
        val ids = listOf("pl-aaa111", "pl-bbb222")

        assertEquals(ids, PlaylistRules.decodeOrder(PlaylistRules.encodeOrder(ids)))
    }

    @Test
    fun `顺序里的空段被丢掉`() {
        // 那个键是纯文本，手改过就可能含有空段；空段不该变成一条不存在的播放列表。
        assertEquals(listOf("pl-a", "pl-b"), PlaylistRules.decodeOrder("pl-a,,pl-b"))
        assertTrue(PlaylistRules.decodeOrder("").isEmpty())
        assertTrue(PlaylistRules.decodeOrder(null).isEmpty())
    }
}
