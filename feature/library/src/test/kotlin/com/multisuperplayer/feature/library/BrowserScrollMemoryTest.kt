package com.multisuperplayer.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「每个目录停在哪儿」的规则与边界。
 *
 * 这一份能单测、而 `LazyColumn` 的滚动本身不能（本模块没有 `compose-ui-test`），
 * 所以**所有会出错的判断都搬到了这里**：键怎么选、越界怎么夹、淘汰谁。
 * 组合那一层只负责「问一句 / 写一句」。
 */
class BrowserScrollMemoryTest {

    // ---- 记住与取回 ----

    @Test
    fun `没去过的目录问出来是 null，而不是顶格`() {
        val memory = BrowserScrollMemory()

        // 空手和「停在顶部」要分得开：前者交给 `rememberLazyListState` 的默认值，
        // 后者是一条真实记录。混成一个值会让「刚记下顶格」和「没记过」不可区分。
        assertNull(memory.recall("content://tree/root"))
    }

    @Test
    fun `不同目录各记各的，不会互相顶掉`() {
        val memory = BrowserScrollMemory()

        memory.remember("ref-parent", BrowseScrollPosition(index = 12, offset = 40))
        memory.remember("ref-child", BrowseScrollPosition(index = 3, offset = 0))

        // 从子目录返回父目录时要拿回**父目录自己**那个位置。
        // 共用一个字段（「当前滚动位置」）时这里必然错：子目录会把它覆盖掉。
        assertEquals(BrowseScrollPosition(12, 40), memory.recall("ref-parent"))
        assertEquals(BrowseScrollPosition(3, 0), memory.recall("ref-child"))
    }

    @Test
    fun `同一个目录再记一次以最新的为准`() {
        val memory = BrowserScrollMemory()

        memory.remember("ref", BrowseScrollPosition(index = 2, offset = 0))
        memory.remember("ref", BrowseScrollPosition(index = 8, offset = 15))

        assertEquals(BrowseScrollPosition(8, 15), memory.recall("ref"))
        assertEquals(1, memory.size)
    }

    @Test
    fun `超过上限时淘汰最久没碰过的那一层`() {
        val memory = BrowserScrollMemory(capacity = 3)
        memory.remember("a", BrowseScrollPosition(index = 1, offset = 0))
        memory.remember("b", BrowseScrollPosition(index = 2, offset = 0))
        memory.remember("c", BrowseScrollPosition(index = 3, offset = 0))

        // 重新碰一次 a：它变成「最近用过的」，此刻该被淘汰的是 b。
        memory.remember("a", BrowseScrollPosition(index = 4, offset = 0))
        memory.remember("d", BrowseScrollPosition(index = 5, offset = 0))

        assertEquals(3, memory.size)
        assertNull(memory.recall("b"))
        assertEquals(BrowseScrollPosition(4, 0), memory.recall("a"))
        assertEquals(BrowseScrollPosition(5, 0), memory.recall("d"))
    }

    // ---- 恢复时的夹取 ----

    @Test
    fun `条目变少时下标夹到最后一条，偏移清零`() {
        // 上次停在第 40 行（带 300 像素偏移），这次这个目录只剩 12 项。
        val restored = BrowseScrollPosition(index = 40, offset = 300).clampTo(itemCount = 12)

        // 夹到 11 是「列表的最后一条」。偏移必须清零：那 300 像素属于被换掉的行，
        // 留着会变成「第 11 行上方空出一大块」，看起来像列表没加载完。
        assertEquals(BrowseScrollPosition(index = 11, offset = 0), restored)
    }

    @Test
    fun `条目够多时原样恢复，偏移保留`() {
        val restored = BrowseScrollPosition(index = 5, offset = 120).clampTo(itemCount = 12)

        assertEquals(BrowseScrollPosition(index = 5, offset = 120), restored)
    }

    @Test
    fun `空目录恢复成顶格，不会拿到负数下标`() {
        assertEquals(
            BrowseScrollPosition.TOP,
            BrowseScrollPosition(index = 40, offset = 300).clampTo(itemCount = 0),
        )
        // 只有一条时也一样：`coerceIn(0, -1)` 会抛，必须是特判。
        assertEquals(
            BrowseScrollPosition.TOP,
            BrowseScrollPosition(index = 3, offset = 0).clampTo(itemCount = 1),
        )
    }

    @Test
    fun `负的下标和偏移都收成顶格`() {
        assertEquals(
            BrowseScrollPosition(index = 0, offset = 0),
            BrowseScrollPosition(index = -3, offset = 50).clampTo(itemCount = 12),
        )
        assertEquals(
            BrowseScrollPosition(index = 5, offset = 0),
            BrowseScrollPosition(index = 5, offset = -20).clampTo(itemCount = 12),
        )
    }
}
