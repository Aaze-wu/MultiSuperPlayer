package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库界面「该画哪一块」的测试。
 *
 * 这一组测试的存在理由很具体：它覆盖的两个缺陷都是**编译期完全看不出来**的，
 * 只能在真机上肉眼发现——
 *
 * 1. `Ready` 一个分支同时覆盖「有内容」和「扫到 0 条」，于是「还没有扫描到媒体」
 *    和三条媒体条目同时压在屏幕上；
 * 2. 工具栏用「搜索命中数 > 0」决定显隐，于是输入一个匹配不到的词就把搜索框
 *    连同查询词一起藏起来，用户既看不到自己输的字也没有入口清掉它。
 *
 * 所以分支判定被搬到 [LibraryPane] 里，由这里逐条钉住。
 */
class LibraryPaneTest {

    private fun entry(id: String, title: String, kind: MediaKind = MediaKind.AUDIO) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = kind,
    )

    private val audio = entry("a", "晴天")
    private val video = entry("v", "海边.mp4", MediaKind.VIDEO)
    private val library = MediaLibraryState.Ready(listOf(audio, video))

    private fun state(
        library: MediaLibraryState = this.library,
        filter: LibraryFilter = LibraryFilter.ALL,
        query: String = "",
    ) = buildLibraryUiState(library, filter, query)

    // -------------------------------------------------------------- 分支选择

    @Test
    fun `扫描中显示加载`() {
        assertEquals(LibraryPane.Loading, state(library = MediaLibraryState.Loading).pane)
    }

    @Test
    fun `缺权限显示授权引导`() {
        assertEquals(
            LibraryPane.NeedsPermission,
            state(library = MediaLibraryState.NeedsPermission).pane,
        )
    }

    @Test
    fun `扫描失败把仓库给的原文透出去`() {
        assertEquals(
            LibraryPane.Failure(MspText.Plain("读写被拒绝")),
            state(library = MediaLibraryState.Error(MspText.Plain("读写被拒绝"))).pane,
        )
    }

    @Test
    fun `扫描到零条显示还没有媒体`() {
        assertEquals(
            LibraryPane.NoMedia,
            state(library = MediaLibraryState.Ready(emptyList())).pane,
        )
    }

    @Test
    fun `搜索匹配不到时说的是没匹配到而不是没有文件`() {
        // 真机复现过：输入 zzz 之后提示的是「还没有扫描到媒体，
        // 把文件放进手机存储后重新扫描即可」——把「没搜到」说成了「没有文件」。
        assertEquals(LibraryPane.FilteredOut, state(query = "zzz").pane)
    }

    @Test
    fun `类型筛选把库挡光时也是没匹配到`() {
        val onlyVideo = MediaLibraryState.Ready(listOf(video))
        assertEquals(
            LibraryPane.FilteredOut,
            state(library = onlyVideo, filter = LibraryFilter.AUDIO).pane,
        )
    }

    @Test
    fun `有内容时渲染列表`() {
        assertEquals(LibraryPane.Content, state().pane)
        assertEquals(LibraryPane.Content, state(query = "晴天").pane)
        assertEquals(LibraryPane.Content, state(filter = LibraryFilter.VIDEO).pane)
    }

    // ------------------------------------------------- 回归 1：搜索框不能消失

    @Test
    fun `搜索无匹配时库计数不归零所以搜索框不会消失`() {
        val s = state(query = "zzz")
        assertTrue(s.entries.isEmpty())
        // 关键就是这个数**不是** 0：界面拿它决定要不要画工具栏（含搜索框）。
        assertEquals(2, s.libraryCount)
        // allCount 归零是应该的，它只说明「这次搜索没命中」，不该被当成「库里没东西」。
        assertEquals(0, s.allCount)
    }

    @Test
    fun `库计数与搜索无关`() {
        for (query in listOf("", "晴天", "zzz", "   ")) {
            assertEquals("query=$query", 2, state(query = query).libraryCount)
        }
    }

    // ------------------------------------------- 回归 2：空状态与列表不能同屏

    @Test
    fun `只有存在条目时才可能是列表分支`() {
        val libraries = listOf(
            MediaLibraryState.Loading,
            MediaLibraryState.NeedsPermission,
            MediaLibraryState.Ready(emptyList()),
            MediaLibraryState.Error(MspText.Plain("x")),
            library,
        )
        for (lib in libraries) {
            for (query in listOf("", "晴天", "zzz")) {
                for (filter in LibraryFilter.entries) {
                    val s = buildLibraryUiState(lib, filter, query)
                    // 列表只在 Content 分支里画，所以这条等价关系等价于
                    // 「空状态和列表永远不会同时出现」。只要有人把列表挪到
                    // 分支外面去（这正是原来的写法），这里立刻会红。
                    assertEquals(
                        "lib=$lib query=$query filter=$filter",
                        s.pane is LibraryPane.Content,
                        s.entries.isNotEmpty(),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------- 计数语义不变

    @Test
    fun `筛选标签上的数字跟着搜索走`() {
        val s = state(query = "晴天")
        assertEquals(1, s.counts[LibraryFilter.ALL])
        assertEquals(1, s.counts[LibraryFilter.AUDIO])
        assertEquals(0, s.counts[LibraryFilter.VIDEO])
        // 但「库里有东西吗」的答案不受影响。
        assertEquals(2, s.libraryCount)
    }
}
