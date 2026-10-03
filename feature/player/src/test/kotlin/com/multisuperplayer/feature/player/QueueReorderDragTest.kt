package com.multisuperplayer.feature.player

import com.multisuperplayer.core.player.QueueRules
import com.multisuperplayer.core.ui.list.ReorderDragRules
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「手指位移 → 队列下标 → 队列真的变了」这条路的锁。
 *
 * 落点算术本身在 `core:ui` 的 `ReorderDragRulesTest` 里（那是两个列表共用的纯函数），
 * 这里只锁**两件事接得上**：`ReorderDragRules` 算出的落点含义，必须和
 * [QueueRules.move] 的下标语义一致。
 *
 * 为什么值得单独锁：两边的注释都写着「先抽出来再插入」，读起来完全一致，
 * 但一个把落点理解成「插到第 to 项之前」的实现会得到 `[B,A,C,D]` 而不是
 * `[B,C,A,D]`——差一格，界面上表现为「拖过头一行」，看起来像手感问题而不是错误。
 */
class QueueReorderDragTest {

    private val row = 56f

    @Test
    fun `往下拖两行真的把这一项往后挪了两格`() {
        val queue = listOf("A", "B", "C", "D", "E")
        // 112 = 正好两个行高。
        val to = ReorderDragRules.targetIndex(from = 0, dragOffsetY = 112f, rowHeightPx = row, size = queue.size)
        assertEquals(2, to)
        assertEquals(listOf("B", "C", "A", "D", "E"), QueueRules.move(queue, 0, to))
    }

    @Test
    fun `往上拖两行真的把这一项往前挪了两格`() {
        val queue = listOf("A", "B", "C", "D", "E")
        val to = ReorderDragRules.targetIndex(from = 4, dragOffsetY = -112f, rowHeightPx = row, size = queue.size)
        assertEquals(2, to)
        assertEquals(listOf("A", "B", "E", "C", "D"), QueueRules.move(queue, 4, to))
    }

    @Test
    fun `拖出列表得到的是首尾两端而不是原地不动`() {
        val queue = listOf("A", "B", "C", "D", "E")
        val toEnd = ReorderDragRules.targetIndex(from = 0, dragOffsetY = 4_000f, rowHeightPx = row, size = queue.size)
        assertEquals(listOf("B", "C", "D", "E", "A"), QueueRules.move(queue, 0, toEnd))

        val toStart = ReorderDragRules.targetIndex(from = 4, dragOffsetY = -4_000f, rowHeightPx = row, size = queue.size)
        assertEquals(listOf("E", "A", "B", "C", "D"), QueueRules.move(queue, 4, toStart))
    }

    @Test
    fun `没跨过任何一行时队列原样不动`() {
        // 手抖的那一下：落点算出来等于出发点，界面必须**跳过**这一次 move。
        // 队列那边白跑一次会让当前播放项重新缓冲，听感上是一次莫名其妙的停顿。
        val queue = listOf("A", "B", "C", "D", "E")
        val to = ReorderDragRules.targetIndex(from = 2, dragOffsetY = 20f, rowHeightPx = row, size = queue.size)
        assertEquals(2, to)
        assertEquals(true, ReorderDragRules.isNoOp(2, to))
        assertEquals(
            "move 到自己身上本来也是空转，但界面不该走到这一步",
            queue,
            QueueRules.move(queue, 2, to),
        )
    }
}
