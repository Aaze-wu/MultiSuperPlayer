package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 媒体库筛选/搜索的纯逻辑测试。
 *
 * 只测不依赖 `ContentResolver` 的那部分。「搜索怎么匹配」是用户能直接感知的行为，
 * 而它完全由这里的两三个函数决定，值得逐条钉住。
 */
class LibraryQueryTest {

    private fun entry(
        id: String,
        title: String,
        kind: MediaKind = MediaKind.AUDIO,
        artist: String? = null,
        album: String? = null,
        displayName: String? = null,
    ) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = title,
        kind = kind,
        artist = artist,
        album = album,
        displayName = displayName,
    )

    private val list = listOf(
        entry("a", "晴天", artist = "周杰伦", album = "叶惠美"),
        entry("b", "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera"),
        entry("c", "海边.mp4", kind = MediaKind.VIDEO, displayName = "海边.mp4"),
    )

    @Test
    fun `空关键词返回全部`() {
        assertEquals(list, list.filterByQuery(""))
        assertEquals(list, list.filterByQuery("   "))
    }

    @Test
    fun `按标题匹配`() {
        assertEquals(listOf("a"), list.filterByQuery("晴天").map { it.id })
        assertEquals(listOf("b"), list.filterByQuery("rhapsody").map { it.id })
    }

    @Test
    fun `标题匹配忽略大小写`() {
        assertEquals(listOf("b"), list.filterByQuery("BOHEMIAN").map { it.id })
    }

    @Test
    fun `按艺术家与专辑匹配`() {
        assertEquals(listOf("a"), list.filterByQuery("周杰伦").map { it.id })
        assertEquals(listOf("b"), list.filterByQuery("opera").map { it.id })
    }

    @Test
    fun `按文件名匹配`() {
        assertEquals(listOf("c"), list.filterByQuery("海边").map { it.id })
    }

    @Test
    fun `关键词首尾空白被忽略`() {
        assertEquals(listOf("a"), list.filterByQuery("  晴天  ").map { it.id })
    }

    @Test
    fun `正则元字符按普通字符处理`() {
        // 「(2009)」这类写法在标题里很常见，当正则用会抛异常或者什么都匹配不到。
        val withParens = list + entry("d", "无与伦比 (2007)")
        assertEquals(listOf("d"), withParens.filterByQuery("(2007)").map { it.id })
        // 单独的 "(" 不应该把整个搜索变成正则错误，只是搜不到而已。
        assertTrue(withParens.filterByQuery("(").isEmpty().not())
    }

    @Test
    fun `筛选器按类型过滤`() {
        assertEquals(3, list.count(LibraryFilter.ALL::accepts))
        assertEquals(2, list.count(LibraryFilter.AUDIO::accepts))
        assertEquals(1, list.count(LibraryFilter.VIDEO::accepts))
    }

    @Test
    fun `筛选与搜索可以叠加`() {
        val result = list.filterByQuery("海边").filter(LibraryFilter.AUDIO::accepts)
        assertTrue(result.isEmpty())
        val videos = list.filterByQuery("海边").filter(LibraryFilter.VIDEO::accepts)
        assertEquals(listOf("c"), videos.map { it.id })
    }

    @Test
    fun `筛选后为空与库本身为空是两种状态`() {
        // 库里只有视频时切到「音乐」标签：结果为空，但界面必须提示「没有匹配的内容」
        // 而不是「还没有扫描到媒体」——后者会让用户以为扫描失败。
        val state = LibraryUiState(
            library = com.multisuperplayer.core.data.library.MediaLibraryState.Ready(
                entries = listOf(entry("c", "海边.mp4", kind = MediaKind.VIDEO)),
            ),
            filter = LibraryFilter.AUDIO,
            entries = emptyList(),
            counts = mapOf(
                LibraryFilter.ALL to 1,
                LibraryFilter.AUDIO to 0,
                LibraryFilter.VIDEO to 1,
            ),
        )
        assertTrue(state.filteredOut)
        assertFalse(LibraryUiState().filteredOut)
    }
}
