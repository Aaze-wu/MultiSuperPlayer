package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「列目录的结论」翻成界面状态这一步的单元测试。
 *
 * 这一步的价值全在**区分几种看起来一样的状态**：
 * 空目录、读不到、只有被开关挡住的隐藏项——三种在屏幕上都是「什么都没有」，
 * 所以任何一个判断写错，用户看到的都是一句错误的话，而代码毫无异常。
 */
class BrowserContentTest {

    @Test
    fun `空目录与读不到是两种结果`() {
        val empty = BrowserContent.of(BrowserListing.Ready(emptyList()), BrowserSort.NAME_ASC, false)
        assertTrue(empty is BrowserContent.Ready)
        val ready = empty as BrowserContent.Ready
        assertTrue(ready.empty)
        // 真正的空目录不该说「被隐藏开关挡住了」。
        assertFalse(ready.onlyHidden)

        // 读不到是另一档：界面要给出「去开权限 / 重新授权」的动作。
        assertTrue(
            BrowserContent.of(BrowserListing.Unreadable, BrowserSort.NAME_ASC, false) is BrowserContent.Unreadable,
        )
        // 位置已经不是目录了（外部被删/改名）：界面该退回上一层。
        assertTrue(
            BrowserContent.of(BrowserListing.NotADirectory, BrowserSort.NAME_ASC, false) is BrowserContent.NotADirectory,
        )
    }

    @Test
    fun `全是被隐藏项时只有 onlyHidden 成立`() {
        val entries = listOf(hiddenFile(".nomedia"), hiddenFile(".thumbnails"))

        val ready = BrowserContent.of(BrowserListing.Ready(entries), BrowserSort.NAME_ASC, showHidden = false)
            as BrowserContent.Ready

        assertTrue(ready.empty)
        assertEquals(2, ready.hiddenCount)
        // 这一条就是 `onlyHidden` 存在的全部理由：目录不是空的，
        // 只是开关关着。合并进 `empty` 会让用户去别处找文件。
        assertTrue(ready.onlyHidden)
    }

    @Test
    fun `隐藏项数量按未过滤的原始表算`() {
        // 如果 hiddenCount 从过滤之后的列表算，它恒为 0，`onlyHidden` 永不成立——
        // 于是「全是被隐藏项」的目录和真正的空目录说着同一句话。
        val entries = listOf(hiddenFile(".hidden"), file("visible.mp4"))

        val filtered = BrowserContent.of(BrowserListing.Ready(entries), BrowserSort.NAME_ASC, false)
            as BrowserContent.Ready

        assertEquals(1, filtered.hiddenCount)
        assertEquals(listOf("visible.mp4"), filtered.entries.map { it.name })
        assertFalse(filtered.empty)
        assertFalse(filtered.onlyHidden)

        // 打开开关时隐藏项变成正常条目，但 hiddenCount 不变：
        // 它描述的是**这个目录里有多少隐藏项**，不是「当前视图里显示了多少」。
        val shown = BrowserContent.of(BrowserListing.Ready(entries), BrowserSort.NAME_ASC, true)
            as BrowserContent.Ready

        assertEquals(1, shown.hiddenCount)
        assertEquals(2, shown.entries.size)
        assertFalse(shown.onlyHidden)
    }

    @Test
    fun `开关打开时列出的隐藏项不会被误报成 onlyHidden`() {
        val ready = BrowserContent.of(BrowserListing.Ready(listOf(hiddenFile(".hidden"))), BrowserSort.NAME_ASC, true)
            as BrowserContent.Ready

        assertFalse(ready.empty)
        assertFalse(ready.onlyHidden)
        assertEquals(1, ready.hiddenCount)
    }

    @Test
    fun `排序与隐藏开关都透传到结果`() {
        val entries = listOf(file("b.mp4"), hiddenFile(".hidden"), file("a.mp4"))

        val ready = BrowserContent.of(BrowserListing.Ready(entries), BrowserSort.NAME_DESC, showHidden = true)
            as BrowserContent.Ready

        // 降序 + 全部显示：`b` / `a` / `.hidden`。
        // 这里同时验证了两件容易漏的事：开关真的传下去了，而且过滤与排序的作用顺序
        // 和 `BrowserRules` 一致（先过滤再排序）。
        assertEquals(listOf("b.mp4", "a.mp4", ".hidden"), ready.entries.map { it.name })
    }

    @Test
    fun `截断标记来自排序阶段`() {
        val entries = List(MAX_ENTRIES_PER_DIRECTORY + 1) { file("f%05d.mp4".format(it)) }

        val ready = BrowserContent.of(BrowserListing.Ready(entries), BrowserSort.NAME_ASC, false)
            as BrowserContent.Ready

        assertTrue(ready.truncated)
        assertEquals(MAX_ENTRIES_PER_DIRECTORY, ready.entries.size)
    }

    @Test
    fun `Idle 与 Loading 都不是 Ready-空`() {
        // 「还没进任何目录」（要让用户选来源）和「正在列目录」（要显示加载）
        // 和「这个目录是空的」是**三句不同的话**。都当成空列表的话，
        // 来源页会在进目录之前先闪一下「这里是空的」。
        val emptyReady = BrowserContent.Ready(emptyList(), truncated = false, hiddenCount = 0)

        // 两边都显式升到共同的父类型：JUnit 4 的 `assertNotEquals` 不吃类型实参。
        assertNotEquals(BrowserContent.Idle as BrowserContent, emptyReady as BrowserContent)
        assertNotEquals(BrowserContent.Loading as BrowserContent, emptyReady as BrowserContent)
    }

    // ------------------------------------------------------------ 工具

    private fun file(name: String): BrowserEntry = BrowserEntry(
        ref = "/x/$name",
        name = name,
        isDirectory = false,
        kind = MediaKind.VIDEO,
    )

    private fun hiddenFile(name: String): BrowserEntry = file(name)
}
