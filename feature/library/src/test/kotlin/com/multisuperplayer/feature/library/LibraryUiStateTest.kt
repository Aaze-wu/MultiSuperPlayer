package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `buildLibraryUiState` 的测试。
 *
 * 它是 ViewModel 里 `combine {}` 的那一步，也是两个真实缺陷的发生地：
 * 搜索时把搜索框藏起来、空状态和列表同时显示。所以每个派生字段都单独钉一条。
 */
class LibraryUiStateTest {

    private fun entry(id: String, title: String = id, kind: MediaKind = MediaKind.AUDIO) =
        MediaEntry(
            id = id,
            uri = "content://media/$id",
            title = title,
            kind = kind,
        )

    private val audio = entry("a1", "晴天")
    private val video = entry("v1", "海边.mp4", kind = MediaKind.VIDEO)
    private val ready = MediaLibraryState.Ready(entries = listOf(audio, video))

    @Test
    fun `筛选与搜索的结果和计数一起算出`() {
        val state = buildLibraryUiState(ready, LibraryFilter.ALL, "海边")
        assertEquals(listOf("v1"), state.entries.map { it.id })
        assertEquals(1, state.counts[LibraryFilter.ALL])
        assertEquals(0, state.counts[LibraryFilter.AUDIO])
        assertEquals(1, state.counts[LibraryFilter.VIDEO])
        // 计数按搜索后的集合算，但「库里有东西吗」必须看原始库。
        assertEquals(2, state.libraryCount)
        assertFalse(state.libraryEmpty)
    }

    @Test
    fun `三个展示维度被原样带进状态`() {
        val state = buildLibraryUiState(
            library = ready,
            filter = LibraryFilter.ALL,
            query = "",
            sort = LibrarySort.LARGEST,
            groupMode = LibraryGroupMode.FOLDER,
            viewMode = LibraryViewMode.GRID,
        )
        assertEquals(LibrarySort.LARGEST, state.sort)
        assertEquals(LibraryGroupMode.FOLDER, state.groupMode)
        assertEquals(LibraryViewMode.GRID, state.viewMode)
    }

    @Test
    fun `展示维度的默认值是不分组且列表视图`() {
        // 默认值写错的代价是「升级之后一进媒体库看到的是网格」，而且是静默的。
        val state = buildLibraryUiState(ready, LibraryFilter.ALL, "")
        assertEquals(LibrarySort.TITLE_ASC, state.sort)
        assertEquals(LibraryGroupMode.NONE, state.groupMode)
        assertEquals(LibraryViewMode.LIST, state.viewMode)
    }

    @Test
    fun `entries 保持筛选后的输入顺序不在这里排序`() {
        // 排序由 LibraryArrangement 在界面层做。这里一旦顺手排一下，
        // 「标签上的数字」和「列表顺序」就会各自依赖一份不同的中间结果。
        val state = buildLibraryUiState(
            library = MediaLibraryState.Ready(entries = listOf(video, audio)),
            filter = LibraryFilter.ALL,
            query = "",
            sort = LibrarySort.TITLE_ASC,
        )
        assertEquals(listOf("v1", "a1"), state.entries.map { it.id })
    }

    @Test
    fun `只拿到部分权限时 partial 为真`() {
        val state = buildLibraryUiState(
            library = MediaLibraryState.Ready(entries = listOf(audio), partial = true),
            filter = LibraryFilter.ALL,
            query = "",
        )
        assertTrue(state.partial)
        assertFalse(state.truncated)
    }

    @Test
    fun `扫描被截断时 truncated 为真`() {
        // partial 和 truncated 必须是两个字段：它们要叫用户做的事完全不同
        // （一个是去授权，一个是「东西太多，分批看」）。
        val state = buildLibraryUiState(
            library = MediaLibraryState.Ready(entries = listOf(audio), truncated = true),
            filter = LibraryFilter.ALL,
            query = "",
        )
        assertTrue(state.truncated)
        assertFalse(state.partial)
    }

    @Test
    fun `非 Ready 状态的两个横幅标记都是假`() {
        listOf(
            MediaLibraryState.Loading,
            MediaLibraryState.NeedsPermission,
            MediaLibraryState.Error(MspText.Plain("read failed")),
        ).forEach { library ->
            val state = buildLibraryUiState(library, LibraryFilter.ALL, "")
            assertFalse(library.toString(), state.partial)
            assertFalse(library.toString(), state.truncated)
            assertEquals(library.toString(), 0, state.libraryCount)
        }
    }
}
