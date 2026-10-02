package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多选规则的测试。
 *
 * 看起来只是一堆 `Set` 运算，但这里出过的两个真实问题都是「界面看起来坏了」：
 * 空 id 混进选择集 → 操作条说「已选 1 项」而屏幕上没有一行高亮；
 * 全选状态下再点全选 → 按钮没反应（因为集合变得和原来相等，界面不重组）。
 */
class LibrarySelectionRulesTest {

    private fun entry(id: String) = MediaEntry(
        id = id,
        uri = "content://media/$id",
        title = id,
        kind = MediaKind.AUDIO,
    )

    private val list = listOf(entry("a"), entry("b"), entry("c"))

    @Test
    fun `点击未选中的行会选中它`() {
        assertEquals(setOf("a"), LibrarySelectionRules.toggle(emptySet(), "a"))
    }

    @Test
    fun `点击已选中的行会取消它`() {
        assertEquals(setOf("b"), LibrarySelectionRules.toggle(setOf("a", "b"), "a"))
    }

    @Test
    fun `空 id 不进入选择集且返回同一个实例`() {
        val selected = setOf("a")
        assertSame(selected, LibrarySelectionRules.toggle(selected, ""))
        assertSame(selected, LibrarySelectionRules.toggle(selected, "   "))
    }

    @Test
    fun `全选把可见条目全部并进来`() {
        assertEquals(setOf("a", "b", "c"), LibrarySelectionRules.addAll(emptySet(), list))
    }

    @Test
    fun `全选保留筛选之外已有的选择`() {
        // 「全选」的语义是「把屏幕上这些也选上」，不是「清空重选」。
        assertEquals(setOf("x", "a", "b", "c"), LibrarySelectionRules.addAll(setOf("x"), list))
    }

    @Test
    fun `已经全选时返回同一个实例`() {
        val selected = setOf("a", "b", "c")
        assertSame(selected, LibrarySelectionRules.addAll(selected, list))
    }

    @Test
    fun `空列表的全选没有副作用`() {
        val selected = setOf("a")
        assertSame(selected, LibrarySelectionRules.addAll(selected, emptyList()))
    }

    @Test
    fun `取消选择只影响传入的这些行`() {
        assertEquals(setOf("x"), LibrarySelectionRules.removeAll(setOf("x", "a", "b"), list))
    }

    @Test
    fun `取消选择没有命中任何行时返回同一个实例`() {
        val selected = setOf("x")
        assertSame(selected, LibrarySelectionRules.removeAll(selected, list))
    }

    @Test
    fun `空选择集的取消操作是空转`() {
        val selected = emptySet<String>()
        assertSame(selected, LibrarySelectionRules.removeAll(selected, list))
    }

    @Test
    fun `全部选中时为真`() {
        assertTrue(LibrarySelectionRules.allSelected(setOf("a", "b", "c"), list))
    }

    @Test
    fun `只选中一部分时为假`() {
        assertFalse(LibrarySelectionRules.allSelected(setOf("a", "b"), list))
    }

    @Test
    fun `空列表永远是未全选`() {
        // 否则操作条按钮的文案会变成「取消选择」，而屏幕上根本没有东西可取消。
        assertFalse(LibrarySelectionRules.allSelected(setOf("a"), emptyList()))
        assertFalse(LibrarySelectionRules.allSelected(emptySet(), emptyList()))
    }

    @Test
    fun `resolve 保持列表顺序并丢掉已经不可见的条目`() {
        // 选择集里有 "z"，但当前列表里没有它（被筛掉了/文件被删了）：
        // 操作绝不能带上它，否则「加入了 3 项」里有一项用户根本没看见。
        val resolved = LibrarySelectionRules.resolve(list, setOf("c", "a", "z"))
        assertEquals(listOf("a", "c"), resolved.map { it.id })
    }

    @Test
    fun `resolve 在空选择集下给出空结果`() {
        assertTrue(LibrarySelectionRules.resolve(list, emptySet()).isEmpty())
    }
}
