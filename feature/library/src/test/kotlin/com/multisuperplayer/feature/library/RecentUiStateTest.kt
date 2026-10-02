package com.multisuperplayer.feature.library

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.RecentPlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `recentUiState` 的测试。
 *
 * 这里每一条都在防同一种观感缺陷：把「还不知道」显示成「没有」。
 * 用户分不清这两者，只会得出「记录丢了」的结论。
 */
class RecentUiStateTest {

    private fun entry(id: String) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = id,
        kind = MediaKind.AUDIO,
    )

    private val rows = listOf(RecentPlay(entry("a1"), positionMs = 12_000L, playedAtMs = 1_700_000_000_000L))
    private val ready = MediaLibraryState.Ready(listOf(entry("a1")))

    @Test
    fun `库就绪时原样显示读到的记录`() {
        val state = recentUiState(ready, rows)

        assertEquals(listOf("a1"), state.rows?.map { it.entry.id })
        assertFalse(state.loading)
        assertFalse(state.blocked)
    }

    @Test
    fun `库就绪但记录还没读到时是加载中`() {
        val state = recentUiState(ready, null)

        assertTrue(state.loading)
        assertNull(state.rows)
    }

    @Test
    fun `库还在扫描时不显示空列表`() {
        // 即使已经读到了一批空结果，也不能显示：那时候 MediaLibraryRepository
        // 的 state 还没就绪，recent() 返回的空是「不知道」而不是「没有」。
        val state = recentUiState(MediaLibraryState.Loading, emptyList())

        assertTrue(state.loading)
        assertNull(state.rows)
    }

    @Test
    fun `读不到媒体库时说明是拿不到而不是没有记录`() {
        val state = recentUiState(MediaLibraryState.NeedsPermission, null)

        assertTrue(state.blocked)
        assertFalse(state.loading)
        assertEquals(emptyList<RecentPlay>(), state.rows)
    }

    @Test
    fun `读库失败时同样是拿不到`() {
        val state = recentUiState(MediaLibraryState.Error(MspText.Plain("挂了")), null)

        assertTrue(state.blocked)
        assertFalse(state.loading)
    }

    @Test
    fun `确实没有记录时是一个空列表而不是加载中`() {
        val state = recentUiState(ready, emptyList())

        assertFalse(state.loading)
        assertFalse(state.blocked)
        assertEquals(0, state.rows?.size)
    }

    @Test
    fun `播放进度和播放时间都留着`() {
        val state = recentUiState(ready, rows)
        val row = state.rows!!.single()

        // 这两项是这一页的全部信息量，任何一步丢掉都会让它退化成普通的文件列表。
        assertEquals(12_000L, row.positionMs)
        assertEquals(1_700_000_000_000L, row.playedAtMs)
    }

    @Test
    fun `解析不出来的记录不会出现在列表里`() {
        val orphan = RecentPlay(entry("gone"), positionMs = 5_000L, playedAtMs = 1L)

        // recentUiState 只是搬运，过滤在 RecentPlayRules 里做；这里钉住的是
        // 「搬运不会顺手把记录变成别的东西」。
        val state = recentUiState(ready, listOf(orphan))

        assertEquals(1, state.rows?.size)
    }

    @Test
    fun `空媒体库不会把播放列表项藏起来`() {
        val state = recentUiState(MediaLibraryState.Ready(emptyList()), rows)

        // 库是空的但记录不为空（例如权限刚被撤销）——仍然照原样给出，
        // 由调用方决定怎么显示；在这里清空会让「找不到文件」变成「没有记录」。
        assertEquals(1, state.rows?.size)
    }

    @Test
    fun `顺序原样保留，这一层不重排`() {
        val many = listOf(
            RecentPlay(entry("a1"), 1L, 300L),
            RecentPlay(entry("a2"), 2L, 200L),
            RecentPlay(entry("a3"), 3L, 100L),
        )

        val state = recentUiState(ready, many)

        // 顺序完全由数据层（RecentPlayRules）决定；这一层再排一次的话，
        // 两处规则一旦分叉，用户看到的顺序就和「最近播放」这个名字对不上了。
        assertEquals(listOf("a1", "a2", "a3"), state.rows?.map { it.entry.id })
    }
}
