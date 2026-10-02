package com.multisuperplayer.feature.library

import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildPlaylistsUiState` 的测试。
 *
 * 这里的重点是「详情」是怎么被选出来的：用一个可能已经失效的 id 去查，
 * 查不到必须安静地退回列表页，而不是让整个页面消失或崩掉。
 */
class PlaylistsUiStateTest {

    private fun entry(id: String, title: String = id) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = MediaKind.AUDIO,
    )

    private val item1 = PlaylistItem.of(entry("a1", "晴天"))
    private val itemGone = PlaylistItem.of(entry("gone", "旧歌"))
    private val playlist = Playlist(
        id = "pl-1",
        name = "通勤",
        createdAtMs = 1L,
        items = listOf(item1, itemGone),
    )
    private val ready = MediaLibraryState.Ready(listOf(entry("a1", "晴天")))

    @Test
    fun `打开某个列表时同时给出展示行与播放队列`() {
        val state = buildPlaylistsUiState(listOf(playlist), ready, "pl-1")

        val open = state.open!!
        assertEquals("pl-1", open.id)
        assertEquals("通勤", open.name)
        assertEquals(2, open.rows.size)
        // 展示行不丢：查不到的条目要显示成「文件已不在」。
        assertEquals(1, open.missingCount)
        // 队列同样不丢，且下标一一对应。
        assertEquals(listOf("a1", "gone"), open.queue.map { it.id })
    }

    @Test
    fun `未指定打开的列表时不带详情`() {
        val state = buildPlaylistsUiState(listOf(playlist), ready, null)

        assertNull(state.open)
        assertEquals(1, state.playlists?.size)
    }

    @Test
    fun `打开一个不存在的 id 时安静地退回列表`() {
        val state = buildPlaylistsUiState(listOf(playlist), ready, "pl-does-not-exist")

        // 用户刚在别处删掉了它、或者进程被回收后恢复：这时唯一正确的行为是
        // 显示列表，而不是显示一个空白的详情页。
        assertNull(state.open)
        assertEquals(1, state.playlists?.size)
    }

    @Test
    fun `媒体库还没就绪时所有行都算未解析`() {
        val state = buildPlaylistsUiState(listOf(playlist), MediaLibraryState.Loading, "pl-1")

        val open = state.open!!
        assertEquals(2, open.missingCount)
        // 队列仍然完整：查不到不等于放不出来（文件可能还在，只是没权限看）。
        assertEquals(2, open.queue.size)
    }

    @Test
    fun `媒体库空的时候不会把列表里的条目误当成已解析`() {
        val state = buildPlaylistsUiState(listOf(playlist), MediaLibraryState.Ready(emptyList()), "pl-1")

        assertEquals(2, state.open!!.missingCount)
    }

    @Test
    fun `没有播放列表时 open 一定是 null`() {
        val state = buildPlaylistsUiState(emptyList(), ready, "pl-1")

        assertNull(state.open)
        assertEquals(0, state.playlists?.size)
    }

    @Test
    fun `还没读到和确实没有是两种状态`() {
        assertTrue(PlaylistsUiState().loading)
        assertNull(PlaylistsUiState().playlists)
        assertFalse(PlaylistsUiState(playlists = emptyList()).loading)
    }

    @Test
    fun `详情里的名字就是列表当前的名字`() {
        val renamed = playlist.copy(name = "通勤 2")

        val state = buildPlaylistsUiState(listOf(renamed), ready, "pl-1")

        // 详情不是快照，它跟着当前数据走；否则重命名后返回列表再点进来还是旧名字。
        assertEquals("通勤 2", state.open!!.name)
    }

    @Test
    fun `空列表的详情不是 null，而是零行`() {
        val empty = Playlist(id = "pl-2", name = "空的", createdAtMs = 2L)

        val state = buildPlaylistsUiState(listOf(empty), ready, "pl-2")

        // open == null 的含义是「没有打开任何一个列表」，不能用它来表示「打开了一个空列表」。
        assertEquals(0, state.open!!.rows.size)
        assertEquals(0, state.open!!.queue.size)
        assertEquals(0, state.open!!.missingCount)
    }

    @Test
    fun `探针会被接到详情里的每一行`() {
        val path = "/sdcard/Movies/a.mp4"
        val browsed = PlaylistItem.of(
            MediaEntry(
                id = BrowserEntry.mediaIdOf(path),
                uri = path,
                title = "a",
                kind = MediaKind.VIDEO,
                source = MediaSource.FILE_SYSTEM,
            ),
        )
        val mixed = Playlist(id = "pl-3", name = "混着", createdAtMs = 3L, items = listOf(item1, browsed))

        val asked = mutableListOf<String>()
        val state = buildPlaylistsUiState(listOf(mixed), ready, "pl-3") { p ->
            asked.add(p)
            true
        }

        // 真机上的表现是：从浏览器加进播放列表的两条，一进详情就被标成「文件已不在」，
        // 顶部还挂一句「播的时候可能失败」。库查不到 ≠ 文件没了。
        assertEquals(0, state.open!!.missingCount)
        assertEquals(listOf(path), asked)
    }

    @Test
    fun `不传探针时浏览页条目仍然算未解析`() {
        val path = "/sdcard/Movies/a.mp4"
        val browsed = PlaylistItem.of(
            MediaEntry(
                id = BrowserEntry.mediaIdOf(path),
                uri = path,
                title = "a",
                kind = MediaKind.VIDEO,
                source = MediaSource.FILE_SYSTEM,
            ),
        )
        val mixed = Playlist(id = "pl-4", name = "混着", createdAtMs = 4L, items = listOf(browsed))

        val state = buildPlaylistsUiState(listOf(mixed), ready, "pl-4")

        // 保守默认：宁可多标一条「文件已不在」，也不要把删掉的文件说成还在。
        assertEquals(1, state.open!!.missingCount)
    }
}
