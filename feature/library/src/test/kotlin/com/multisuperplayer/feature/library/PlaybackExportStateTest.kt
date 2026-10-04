package com.multisuperplayer.feature.library

import com.multisuperplayer.core.data.export.PlaybackExportCollection
import com.multisuperplayer.core.data.export.PlaybackExportTrack
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「导出」这一层里**不碰文件系统**的那部分：状态文案、集合拼装、失效条目的去留。
 *
 * ## 为什么只测这些，以及为什么这样就够
 *
 * [exportPlaybackTo] 本身拿不到单测环境：它要一个真的 `TextExportWriter`
 * （构造需要 `Context`）和一个真的 `android.net.Uri`，而 `feature:library` 的
 * 测试依赖只有 JUnit（没有 Robolectric、没有 mock 框架）。硬凑一个假 writer
 * 反而会把「写失败」这条路变成永远走不到的死代码——那种测试全绿却什么都没验。
 *
 * 所以真正的编码正确性钉在 `core:data`（`PlaybackExportTest`：CSV 转义、
 * JSON 形状、BOM 方向），**「这批数据该变成哪些集合、其中哪些已经失效」**
 * 钉在这里。这三件事恰好是「导出文件对不对」的全部前因：
 *
 * 1. **失效条目必须进导出文件**。播放列表刻意保留「文件已不在」的条目
 *    （`PlaylistRows.queue` 有完整理由），导出时把它们丢掉，用户拿着文件
 *    去核对就会发现少的那几条恰好在 app 里赫然在列。README 里把这条
 *    列成已知限制，所以它必须有测试。
 * 2. **每条状态说的话不一样**。「已保存（12 条）」「已保存（12 条，其中 3 条
 *    文件已不在）」「导出失败，文件没有写进去」「没有可导出的内容」——四句话，
 *    四种后续动作。合并任意两句，用户就会做错事（空目录反复重试、或者以为
 *    数据没导出去而删掉列表）。
 * 3. **「全都空」和「有一个空」必须分开**。用户勾了两个列表、其中一个是空的，
 *    导出必须照常进行。这条判据写错的方向是「整体静默什么都不给」，
 *    而界面上没有任何提示。
 */
class PlaybackExportStateTest {

    // ===== 文案 =====

    private val textOf: (MspText) -> String = { text ->
        when (text) {
            is MspText.Plain -> text.text
            is MspText.Res -> "R#${text.id}"
        }
    }

    private fun entry(
        id: String,
        title: String = id,
        artist: String? = null,
        durationMs: Long = 0L,
        kind: MediaKind = MediaKind.AUDIO,
    ) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = kind,
        artist = artist,
        durationMs = durationMs,
    )

    private fun item(
        id: String,
        title: String = id,
        uri: String = "content://media/$id",
    ) = PlaylistItem(
        mediaId = id,
        uri = uri,
        title = title,
        kind = MediaKind.AUDIO,
    )

    private fun collection(name: String, vararg tracks: PlaybackExportTrack) =
        PlaybackExportCollection(name = name, tracks = tracks.toList())

    private fun track(missing: Boolean = false) = PlaybackExportTrack(
        mediaId = "m",
        uri = "u",
        title = "t",
        artist = null,
        kind = MediaKind.AUDIO,
        durationMs = 0L,
        missing = missing,
    )

    // ===== 状态 → 那一句话 =====

    @Test
    fun `什么都没发生时一个字都不用说`() {
        // 说「正在导出…」看起来更友好，但它会让每次成功的导出都先在屏幕上闪一下，
        // 而 Running 真正的用途只是重入闸门（见类型注释）。
        assertNull(PlaybackExportState.Idle.message())
        assertNull(PlaybackExportState.Running.message())
    }

    @Test
    fun `成功时必须带上文件名和条数`() {
        val message = PlaybackExportState.Saved(fileName = "播放记录.csv", tracks = 12, missing = 0)
            .message()

        // 文件名要回显：用户刚在系统选择器里翻过好几个目录，只说「已保存」
        // 他并不知道存哪了。条数要回显：「导出了 0 条」和「我选错文件了」
        // 从外面看一模一样。
        assertEquals(MspText.Res(R.string.msp_export_result_saved, "播放记录.csv", 12), message)
        assertTrue(textOf(message!!).isNotEmpty())
    }

    @Test
    fun `有失效条目时换一句话并把那个数字也说清`() {
        val message = PlaybackExportState.Saved(fileName = "歌单.json", tracks = 12, missing = 3)
            .message()

        // 失效条数是这次导出唯一「用户需要拿去做点什么」的信息（去把文件找回来），
        // 和普通成功共用一句话就等于不说。
        assertEquals(
            MspText.Res(R.string.msp_export_result_saved_missing, "歌单.json", 12, 3),
            message,
        )
    }

    @Test
    fun `没有内容和写失败是两个状态两句话`() {
        // 「没有可导出的内容」比写失败更早发生，而且不该被当成失败：
        // 一个还没建过播放列表的用户点导出，报红字是冤枉他。
        assertEquals(MspText.Res(R.string.msp_export_result_empty), PlaybackExportState.Empty.message())
        assertEquals(
            MspText.Res(R.string.msp_export_result_write_failed),
            PlaybackExportState.Failed(MspText.Res(R.string.msp_export_result_write_failed)).message(),
        )
    }

    // ===== 「有没有东西可导」 =====

    @Test
    fun `全都是空集合才算没有内容`() {
        assertFalse(emptyList<PlaybackExportCollection>().hasExportableTracks)
        assertFalse(listOf(collection("空 A"), collection("空 B")).hasExportableTracks)
    }

    @Test
    fun `只要有一个集合非空就照常导出`() {
        // 这条判据写成 `all { isEmpty() }` 和 `any { isNotEmpty() }` 读起来一样，
        // 但写错的方向是「用户选了三个列表、其中一个空的，结果什么都没导出」。
        assertTrue(
            listOf(collection("空"), collection("有", track())).hasExportableTracks,
        )
    }

    // ===== 一行 → 一条导出记录 =====

    @Test
    fun `库里查不到的行也要导出而且标成失效`() {
        val rows = PlaylistRows.build(listOf(item("gone", "旧歌")), emptyMap())

        val exported = exportTrackOf(rows.single())

        // 内容来自快照（撤了存储权限之后整张列表都查不到，那时用户更需要看到
        // 自己当初存进来的是什么），缺失标记是给接收方看的，让它自己决定怎么办。
        assertEquals("旧歌", exported.title)
        assertEquals("gone", exported.mediaId)
        assertTrue("失效条目必须出现在导出文件里，且要标出来", exported.missing)
    }

    @Test
    fun `库里能查到的行用库里那一份（改名要跟着走）`() {
        val live = entry("a1", title = "新名字", artist = "歌手", durationMs = 331_000L)
        val rows = PlaylistRows.build(listOf(item("a1", title = "旧名字")), mapOf("a1" to live))

        val exported = exportTrackOf(rows.single())

        assertEquals("新名字", exported.title)
        assertEquals("歌手", exported.artist)
        assertEquals(331_000L, exported.durationMs)
        assertFalse(exported.missing)
    }

    // ===== 整份数据 → 集合列表 =====

    @Test
    fun `导出全部时每个播放列表各成一份集合`() {
        val playlists = listOf(
            Playlist("p1", "通勤", createdAtMs = 1L, items = listOf(item("a"), item("b"))),
            Playlist("p2", "睡前", createdAtMs = 2L, items = listOf(item("c"))),
        )
        val library = MediaLibraryState.Ready(
            entries = listOf(entry("a"), entry("b"), entry("c")),
        )

        val collections = playlistExportCollections(playlists, library)

        // 集合名直接用用户起的名字：多一层「播放列表：」前缀在只有一份集合时
        // 是噪音，在多份时才需要（那一列由编码器按集合数决定加不加）。
        assertEquals(listOf("通勤", "睡前"), collections.map { it.name })
        assertEquals(listOf(2, 1), collections.map { it.tracks.size })
        assertEquals(0, collections.sumOf { it.missingCount })
    }

    @Test
    fun `没有条目的播放列表也照样给出一份空集合`() {
        val playlists = listOf(Playlist("p1", "空列表", createdAtMs = 1L))

        val collections = playlistExportCollections(playlists, MediaLibraryState.Ready(emptyList()))

        // 直接过滤掉空列表会让「用户看到 3 个列表、导出文件里只有 2 个」，
        // 而他会怀疑自己丢了数据。空集合由编码器原样写出去（一个只有表头的段落），
        // 反而是清楚的。
        assertEquals(1, collections.size)
        assertTrue(collections.single().tracks.isEmpty())
    }

    @Test
    fun `浏览页条目靠路径探针决定失不失效`() {
        val browserItem = item(
            id = "file:/sdcard/Movies/x.mkv",
            uri = "/sdcard/Movies/x.mkv",
        )
        val playlists = listOf(Playlist("p1", "本地", createdAtMs = 1L, items = listOf(browserItem)))
        val library = MediaLibraryState.Ready(emptyList())

        // 探针是唯一的信息来源：这类条目**永远**不在媒体库里（内置浏览器专挑
        // MediaStore 看不见的地方），拿库当「存在」的裁判会把好文件全标成失效。
        val alive = playlistExportCollections(playlists, library) { true }.single()
        val dead = playlistExportCollections(playlists, library) { false }.single()

        assertFalse(alive.tracks.single().missing)
        assertTrue(dead.tracks.single().missing)
    }

    // ===== 详情页：导的是屏幕上那几行 =====

    @Test
    fun `详情页导出的就是当时拼好的那几行`() {
        val rows = PlaylistRows.build(
            items = listOf(item("a"), item("gone", "旧歌")),
            byId = mapOf("a" to entry("a")),
        )
        val detail = PlaylistDetail(
            id = "p1",
            name = "通勤",
            rows = rows,
            // 队列不是导出的输入（导出只看 rows），但它必须一起给：
            // 「列表下标 == 队列下标」是播放列表层的硬约束，测试里也不该用一个
            // 现实中不存在的状态去喂它。
            queue = rows.map { it.display },
        )

        val exported = detail.toExportCollection()

        // 不重新去读仓库、也不重新过一遍媒体库：用户点了导出，他期望拿到的就是
        // 屏幕上那几条。重读会引入时间差（这期间删掉的记录又回来了、标题又变了），
        // 而这种差异他从外部完全无法解释。
        assertEquals("通勤", exported.name)
        assertEquals(2, exported.tracks.size)
        assertEquals(1, exported.missingCount)
    }
}
