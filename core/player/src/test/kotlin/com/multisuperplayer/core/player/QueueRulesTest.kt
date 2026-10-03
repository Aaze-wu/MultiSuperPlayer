package com.multisuperplayer.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * 播放队列的编辑算术。
 *
 * 每条测试对应一个**真实会发生的错误方向**，而且每一个都是「不崩、不报错、
 * 只是高亮跑到了隔壁」这类症状：
 *
 * - 删掉当前项**之前**的一项却不减下标 ⇒ 界面显示的当前条目变成下一条（错位一格）；
 * - 删掉**最后**一项而它正好是当前项时不退一格 ⇒ 当前下标指到列表外，那一行整个消失；
 * - `move` 用「插到 to 之前」而不是「先抽后插」⇒ 拖到相邻一格时列表不动；
 * - `indexAfterMove` 少一个等号 ⇒ 把最后一项拖到最前面之后，当前项的高亮错位。
 *
 * 最后一条测试把 [QueueRules.move] 和 [QueueRules.indexAfterMove] **对着同一次
 * 编辑**求答案：它们描述的是同一件事（「编辑之后，原来那一项在哪」），
 * 两者对不上就说明有一边写反了——而单看任何一个都看不出问题。
 */
class QueueRulesTest {

    private val abc = listOf("A", "B", "C", "D", "E")

    // ------------------------------------------------------------------ 删除

    @Test
    fun `删除中间一项之后顺序保持不变`() {
        assertEquals(listOf("A", "B", "D", "E"), QueueRules.removeAt(abc, 2))
    }

    @Test
    fun `删除首尾两项`() {
        assertEquals(listOf("B", "C", "D", "E"), QueueRules.removeAt(abc, 0))
        assertEquals(listOf("A", "B", "C", "D"), QueueRules.removeAt(abc, 4))
    }

    @Test
    fun `越界删除原样返回同一个实例`() {
        // 返回同一实例（而不是等值的新列表）让调用方能按 `!==` 判断
        // 「这一次什么都没发生」——拖拽期间下标过期是常有的事，
        // 那时候不该让列表和内核白白动一次。
        assertSame(abc, QueueRules.removeAt(abc, -1))
        assertSame(abc, QueueRules.removeAt(abc, 5))
        val empty = emptyList<String>()
        assertSame(empty, QueueRules.removeAt(empty, 0))
    }

    // ------------------------------------------------------------------ 移动

    @Test
    fun `移动是先把元素抽出来再插进去`() {
        // `[A,B,C,D,E]` 把 A 移到 2：抽掉 A 得到 `[B,C,D,E]`，插到下标 2 得到
        // `[B,C,A,D,E]`。不是 `[B,A,C,D,E]`（那是「插在原来第 2 项之前」的另一种语义）。
        assertEquals(listOf("B", "A", "C", "D", "E"), QueueRules.move(abc, 0, 1))
        assertEquals(listOf("B", "C", "A", "D", "E"), QueueRules.move(abc, 0, 2))
        assertEquals(listOf("A", "B", "C", "E", "D"), QueueRules.move(abc, 4, 3))
        assertEquals(listOf("E", "A", "B", "C", "D"), QueueRules.move(abc, 4, 0))
    }

    @Test
    fun `原地移动与越界移动都原样返回同一实例`() {
        // from == to 是拖动中最常见的输入（手指没真正跨过任何一行），
        // 那时候动一次列表会让内核重新排一遍播放列表，代价是 audible 的。
        assertSame(abc, QueueRules.move(abc, 2, 2))
        assertSame(abc, QueueRules.move(abc, -1, 3))
        assertSame(abc, QueueRules.move(abc, 1, 5))
    }

    @Test
    fun `移动不会丢也不会多出元素`() {
        for (from in abc.indices) {
            for (to in abc.indices) {
                val moved = QueueRules.move(abc, from, to)
                assertEquals(abc.size, moved.size, "从 $from 移到 $to 之后长度变了")
                assertEquals(abc.toSet(), moved.toSet(), "从 $from 移到 $to 之后元素变了")
            }
        }
    }

    // -------------------------------------------------------- 当前项的下标平移

    @Test
    fun `删掉当前项之前的一项时当前下标减一`() {
        assertEquals(2, QueueRules.indexAfterRemoval(currentIndex = 3, removedIndex = 1, newSize = 4))
    }

    @Test
    fun `删掉当前项之后的一项时当前下标不动`() {
        assertEquals(2, QueueRules.indexAfterRemoval(currentIndex = 2, removedIndex = 4, newSize = 4))
    }

    @Test
    fun `删掉当前项本身时下标留在原地`() {
        // 内核删掉当前项之后会往前走一格，也就是「原来第 4 项」顶上来变成了第 3 项
        // ——那正是 currentIndex 这个值，所以这里不该加减。
        assertEquals(2, QueueRules.indexAfterRemoval(currentIndex = 2, removedIndex = 2, newSize = 4))
    }

    @Test
    fun `删掉最后一项而它正好是当前项时退一格`() {
        // 这一条是唯一需要夹取的地方：没有下一项可跳，内核会退到前一项。
        // 不退的话当前下标指向列表之外，界面上那一行高亮整个消失。
        assertEquals(3, QueueRules.indexAfterRemoval(currentIndex = 4, removedIndex = 4, newSize = 4))
    }

    @Test
    fun `删空之后当前下标是零`() {
        assertEquals(0, QueueRules.indexAfterRemoval(currentIndex = 0, removedIndex = 0, newSize = 0))
    }

    @Test
    fun `移动当前项本身时它落到目标位置`() {
        assertEquals(4, QueueRules.indexAfterMove(currentIndex = 1, from = 1, to = 4))
        assertEquals(0, QueueRules.indexAfterMove(currentIndex = 1, from = 1, to = 0))
    }

    @Test
    fun `移动别的项时当前下标按是否跨过它来平移`() {
        // 当前在 2。[A,B,C,D,E]
        // 把 0 移到 4：`[B,C,D,E,A]`，C 从 2 变成 1。
        assertEquals(1, QueueRules.indexAfterMove(currentIndex = 2, from = 0, to = 4))
        // 把 4 移到 0：`[E,A,B,C,D]`，C 从 2 变成 3。
        assertEquals(3, QueueRules.indexAfterMove(currentIndex = 2, from = 4, to = 0))
        // 把 4 移到 3：`[A,B,C,E,D]`，C 还在 2。
        assertEquals(2, QueueRules.indexAfterMove(currentIndex = 2, from = 4, to = 3))
        // 把 0 移到 1：`[B,A,C,D,E]`，C 还在 2。
        assertEquals(2, QueueRules.indexAfterMove(currentIndex = 2, from = 0, to = 1))
    }

    @Test
    fun `插进让出来的那个洞算插在当前项前面`() {
        // 这一条专门钉 `to <= withoutSource` 里的那个等号：
        // `[A,B,C]` 把 C（2）移到 1 —— 抽掉 C 得到 `[A,B]`，插到 1 得到 `[A,C,B]`，
        // B 从 1 变成 2。少了等号会算成 1，于是界面高亮 C 而不是 B。
        assertEquals(2, QueueRules.indexAfterMove(currentIndex = 1, from = 2, to = 1))
    }

    @Test
    fun `两个函数对同一次编辑给出同一个答案`() {
        // 这是这一组里最重要的一条：`move` 改的是列表，`indexAfterMove` 说的是
        // 「原来那一项去哪了」。两者必须描述同一件事——如果 move 用的是
        // 「先插后抽」，两个函数就会对不上，而单独看任何一个都看不出问题。
        val items = listOf("A", "B", "C", "D", "E")
        for (from in items.indices) {
            for (to in items.indices) {
                for (current in items.indices) {
                    val tracked = items[current]
                    val moved = QueueRules.move(items, from, to)
                    assertEquals(
                        moved.indexOf(tracked),
                        QueueRules.indexAfterMove(current, from, to),
                        "把 $from 移到 $to 时，原来在 $current 的「$tracked」应当落在同一个位置",
                    )
                }
            }
        }
    }

    @Test
    fun `删除的下标与列表自身算出来的一致`() {
        // 同上的思路：`removeAt` 改列表、`indexAfterRemoval` 说下标，两者必须一致。
        val items = listOf("A", "B", "C", "D", "E")
        for (removed in items.indices) {
            for (current in items.indices) {
                val tracked = items[current]
                val remaining = QueueRules.removeAt(items, removed)
                val actual = QueueRules.indexAfterRemoval(current, removed, remaining.size)
                val expected = if (removed == current) {
                    // 删掉的就是当前项：内核会往前走一格——也就是**原来排在它后面
                    // 那一条**顶上来；它是最后一条时没有下一项可跳，退到前一条。
                    // 这一句就是「删掉正在播的那首歌之后是谁在播」的完整答案。
                    remaining.getOrNull(removed) ?: remaining.last()
                } else {
                    // 否则当前项还在列表里，下标必须能原样找到它。
                    tracked
                }
                assertEquals(
                    expected,
                    remaining.getOrNull(actual),
                    "删 $removed（当前在 $current）之后，新的当前项应当是「$expected」",
                )
            }
        }
    }
}
