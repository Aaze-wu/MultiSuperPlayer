package com.multisuperplayer.core.data.favorites

import com.multisuperplayer.core.data.browser.BrowserCrumb
import com.multisuperplayer.core.data.browser.BrowserSourceKind
import com.multisuperplayer.core.data.browser.BrowserTrail
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收藏编解码的单元测试。
 *
 * 文件和文件夹共用一个存储键，所以两类的往返都要钉住；目录名里的 `|`、
 * 换行、记录分隔符都必须原样回来——它们出现在用户自己的文件名里是很正常的事，
 * 而编码错一个字符的表现是「收藏页里出现两条断掉的记录」。
 */
class FavoriteCodecTest {

    private val record = FavoriteCodec.RECORD_SEPARATOR

    private fun file(
        mediaId: String = "1",
        uri: String = "content://media/external/audio/media/1",
        title: String = "标题",
        artist: String? = "歌手",
        durationMs: Long = 200_000L,
        kind: MediaKind = MediaKind.AUDIO,
        addedAtMs: Long = 1_700_000_000_000L,
    ) = Favorite.File(
        item = PlaylistItem(mediaId, uri, title, artist, durationMs, kind),
        addedAtMs = addedAtMs,
    )

    private fun folder(
        kind: BrowserSourceKind = BrowserSourceKind.FILE_SYSTEM,
        crumbs: List<FavoriteCrumb> = listOf(
            FavoriteCrumb("内部存储", "/storage/emulated/0"),
            FavoriteCrumb("Download", "/storage/emulated/0/Download"),
        ),
        addedAtMs: Long = 1_700_000_000_000L,
    ) = Favorite.Folder(kind, crumbs, addedAtMs)

    // --------------------------------------------------------------- 往返

    @Test
    fun `文件收藏往返`() {
        val original = listOf(file())
        val decoded = FavoriteCodec.decode(FavoriteCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `文件夹收藏往返，保留整条面包屑`() {
        val original = listOf(folder())
        val decoded = FavoriteCodec.decode(FavoriteCodec.encode(original))
        assertEquals(original, decoded)
        assertEquals(2, (decoded.single() as Favorite.Folder).crumbs.size)
    }

    @Test
    fun `混合列表保持顺序：新的在前`() {
        val original = listOf(folder(addedAtMs = 3), file(addedAtMs = 2), folder(addedAtMs = 1))
        assertEquals(original, FavoriteCodec.decode(FavoriteCodec.encode(original)))
    }

    @Test
    fun `名字里的特殊字符往返`() {
        val tricky = "A|B\nC\rD${record}E\\F"
        val original = listOf(
            folder(crumbs = listOf(FavoriteCrumb(tricky, "/storage/emulated/0/$tricky"))),
            file(title = tricky, artist = tricky),
        )
        assertEquals(original, FavoriteCodec.decode(FavoriteCodec.encode(original)))
    }

    @Test
    fun `空列表往返成空列表`() {
        assertEquals(emptyList<Favorite>(), FavoriteCodec.decode(FavoriteCodec.encode(emptyList())))
        assertEquals(emptyList<Favorite>(), FavoriteCodec.decode(null))
        assertEquals(emptyList<Favorite>(), FavoriteCodec.decode(""))
    }

    // --------------------------------------------------------- 兼容 / 坏数据

    @Test
    fun `未知的媒体类型退化成 UNKNOWN`() {
        val raw = "file|1|content://x|t||0|WHATEVER|1"
        val decoded = FavoriteCodec.decode(raw).single() as Favorite.File
        assertEquals(MediaKind.UNKNOWN, decoded.item.kind)
    }

    @Test
    fun `未知的来源类型整条跳过`() {
        val raw = "folder|FUTURE_KIND|1|1|根|/root"
        // 跳过而不是猜成文件系统：猜错的代价是打开收藏落在一个读不出来的目录。
        assertEquals(emptyList<Favorite>(), FavoriteCodec.decode(raw))
    }

    @Test
    fun `字段数不对的记录被跳过，其它记录照常读出`() {
        val valid = FavoriteCodec.encode(listOf(file()))
        val broken = "file|1|"
        val decoded = FavoriteCodec.decode(listOf(broken, valid).joinToString(record.toString()))
        assertEquals(1, decoded.size)
        assertTrue(decoded.single() is Favorite.File)
    }

    @Test
    fun `文件夹段数对不上时跳过`() {
        // 段数是 2，但只跟了一对名字/ref。
        assertEquals(
            0,
            FavoriteCodec.decode("folder|FILE_SYSTEM|1|2|Download|/storage/emulated/0/Download").size,
        )
    }

    @Test
    fun `文件缺少 uri 时跳过`() {
        assertEquals(emptyList<Favorite>(), FavoriteCodec.decode("file|1||标题||0|AUDIO|1"))
    }

    // ------------------------------------------------------------------- 键

    @Test
    fun `文件与文件夹的键互不冲突`() {
        val fileKey = FavoriteRules.fileKey("1")
        val folderKey = FavoriteRules.folderKey(BrowserSourceKind.FILE_SYSTEM, "1")
        assertFalse(fileKey == folderKey)
    }

    @Test
    fun `同一个目录换来源算两条不同的收藏`() {
        val fs = folder(kind = BrowserSourceKind.FILE_SYSTEM)
        val saf = folder(kind = BrowserSourceKind.SAF)
        assertFalse(fs.key == saf.key)
    }

    // --------------------------------------------------------------- 规则层

    @Test
    fun `重复加入同一条不改变列表`() {
        val current = listOf(file())
        assertSame(current, FavoriteRules.withAdded(current, file(addedAtMs = 99)))
    }

    @Test
    fun `新收藏排在最前面`() {
        val first = file(mediaId = "1")
        val second = file(mediaId = "2")
        val result = FavoriteRules.withAdded(listOf(first), second)
        assertEquals(listOf(second, first), result)
    }

    @Test
    fun `达到上限后不再加入`() {
        val full = List(FavoriteRules.MAX_FAVORITES) { file(mediaId = "id-$it") }
        assertSame(full, FavoriteRules.withAdded(full, file(mediaId = "one-more")))
    }

    @Test
    fun `移除不存在的键返回同一实例`() {
        val current = listOf(file())
        assertSame(current, FavoriteRules.withRemoved(current, setOf("file:nope")))
    }

    @Test
    fun `批量加入与移除`() {
        val a = MediaEntry(id = "1", uri = "content://1", title = "A", kind = MediaKind.AUDIO)
        val b = MediaEntry(id = "2", uri = "content://2", title = "B", kind = MediaKind.AUDIO)
        val added = FavoriteRules.withAddedAll(emptyList(), listOf(FavoriteRules.fileOf(a, 1L), FavoriteRules.fileOf(b, 1L)))
        assertEquals(listOf(FavoriteRules.fileKey("2"), FavoriteRules.fileKey("1")), added.map { it.key })
        val removed = FavoriteRules.withRemoved(added, FavoriteRules.fileKeysOf(listOf(a)))
        assertEquals(listOf(FavoriteRules.fileKey("2")), removed.map { it.key })
    }

    @Test
    fun `全部已收藏的判据`() {
        val a = MediaEntry(id = "1", uri = "content://1", title = "A", kind = MediaKind.AUDIO)
        val b = MediaEntry(id = "2", uri = "content://2", title = "B", kind = MediaKind.AUDIO)
        val current = listOf(FavoriteRules.fileOf(a, 1L))
        assertTrue(FavoriteRules.allFavorited(current, listOf(a)))
        assertFalse(FavoriteRules.allFavorited(current, listOf(a, b)))
        assertEquals(listOf(b), FavoriteRules.unfavorited(current, listOf(a, b)))
        assertFalse(FavoriteRules.allFavorited(current, emptyList()))
    }

    @Test
    fun `文件夹收藏解析面包屑的文案`() {
        val trail = BrowserTrail(
            kind = BrowserSourceKind.SAF,
            crumbs = listOf(
                BrowserCrumb(MspText.Plain("内部存储"), "content://tree/root"),
                BrowserCrumb(MspText.Plain("Music"), "content://tree/root/document/Music"),
            ),
        )
        val favorite = FavoriteRules.folderOf(trail, 1L) { text -> (text as MspText.Plain).text.uppercase() }
        assertEquals(
            listOf(
                FavoriteCrumb("内部存储", "content://tree/root"),
                FavoriteCrumb("MUSIC", "content://tree/root/document/Music"),
            ),
            favorite.crumbs,
        )
        // toTrail 用的是**收藏时**的文案，所以和原来的 trail 只在 label 上可能不同，
        // 而 kind / ref / 层级必须完全一致——否则「打开收藏的文件夹」会落到别处。
        val restored = favorite.toTrail()
        assertEquals(trail.kind, restored.kind)
        assertEquals(trail.crumbs.map { it.ref }, restored.crumbs.map { it.ref })
    }
}
