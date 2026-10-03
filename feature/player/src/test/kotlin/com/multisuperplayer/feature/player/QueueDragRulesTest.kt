package com.multisuperplayer.feature.player

import com.multisuperplayer.core.player.QueueRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列拖拽落点换算的测试。
 *
 * 每一组测的都是**真实会发生的一种拖拽**（手抖、拖半行、拖出列表、行高还没测出来），
 * 而不是把实现抄一遍。
 */
class QueueDragRulesTest {

    /** 被测的行高。用整数只是为了读起来清楚，实现里没有任何「必须是整数」的假设。 */
    private val row = 56f

    @Test
    fun `没动就不换位`() {
        assertEquals(0, QueueDragRules.rowDelta(0f, row))
        // 一两个像素的位移是手指落下时的自然抖动，也算「没动」。
        assertEquals(0, QueueDragRules.rowDelta(1f, row))
        assertEquals(0, QueueDragRules.rowDelta(-1f, row))
    }

    @Test
    fun `正好半行算换位`() {
        // 边界取「算」而不是「不算」：手指停在正好半行的位置时，用户的心智是
        // 「我已经拖过去一半了」，此时不换位看起来像卡住。
        assertEquals(1, QueueDragRules.rowDelta(28f, row))
        assertEquals(-1, QueueDragRules.rowDelta(-28f, row))
    }

    @Test
    fun `不到半行不换位`() {
        // 手抖。这一组是这套规则存在的**主要原因**：没有它，队列会在轻微滑动里
        // 来回换位，想点的行会从手指底下跑掉。
        assertEquals(0, QueueDragRules.rowDelta(27f, row))
        assertEquals(0, QueueDragRules.rowDelta(-27f, row))
    }

    @Test
    fun `上下对称`() {
        // 负数侧写成 `(rows + 0.5f).toInt()` 的话 `-0.5` 会算成 0（向零取整），
        // 于是「往上拖」比「往下拖」需要多拖半行——只有手感差异，真机上很难发现。
        val offsets = listOf(0f, 10f, 28f, 40f, 56f, 84f, 112f, 300f)
        offsets.forEach { offset ->
            assertEquals(
                "位移 $offset 与 -$offset 的跨行数必须对称",
                QueueDragRules.rowDelta(offset, row),
                -QueueDragRules.rowDelta(-offset, row),
            )
        }
    }

    @Test
    fun `跨过多行按行数算`() {
        assertEquals(2, QueueDragRules.rowDelta(84f, row))
        assertEquals(2, QueueDragRules.rowDelta(112f, row))
        assertEquals(3, QueueDragRules.rowDelta(140f, row))
        assertEquals(-3, QueueDragRules.rowDelta(-140f, row))
    }

    @Test
    fun `行高还没测出来时不换位`() {
        // 行高为 0 只可能来自「这一帧还没测量」，此时任何非零结果都会让列表跳一下。
        assertEquals(0, QueueDragRules.rowDelta(100f, 0f))
        assertEquals(0, QueueDragRules.rowDelta(100f, -56f))
        assertEquals(0, QueueDragRules.rowDelta(100f, Float.NaN))
        assertEquals(0, QueueDragRules.rowDelta(100f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `位移不是有限数时不换位`() {
        assertEquals(0, QueueDragRules.rowDelta(Float.NaN, row))
        assertEquals(0, QueueDragRules.rowDelta(Float.POSITIVE_INFINITY, row))
    }

    @Test
    fun `落点按出发点加跨行数算`() {
        assertEquals(2, QueueDragRules.targetIndex(from = 0, dragOffsetY = 112f, rowHeightPx = row, size = 5))
        assertEquals(0, QueueDragRules.targetIndex(from = 2, dragOffsetY = -112f, rowHeightPx = row, size = 5))
    }

    @Test
    fun `往下拖出列表就落到最后一位`() {
        // 用户拖到列表外面时想要的是「放到最后」，不是「什么都没发生」。
        assertEquals(4, QueueDragRules.targetIndex(from = 3, dragOffsetY = 560f, rowHeightPx = row, size = 5))
        assertEquals(4, QueueDragRules.targetIndex(from = 0, dragOffsetY = 560f, rowHeightPx = row, size = 5))
    }

    @Test
    fun `往上拖出列表就落到第一位`() {
        assertEquals(0, QueueDragRules.targetIndex(from = 4, dragOffsetY = -560f, rowHeightPx = row, size = 5))
    }

    @Test
    fun `只有一项时怎么拖都落在第零位`() {
        assertEquals(0, QueueDragRules.targetIndex(from = 0, dragOffsetY = 560f, rowHeightPx = row, size = 1))
        assertEquals(0, QueueDragRules.targetIndex(from = 0, dragOffsetY = -560f, rowHeightPx = row, size = 1))
    }

    @Test
    fun `空列表不会算出负数下标`() {
        // 正常走不到（空队列时面板显示空态），但下标为负会直接崩在 LazyColumn 上，
        // 而这一层是纯函数、拦住的成本只是这一个断言。
        assertEquals(0, QueueDragRules.targetIndex(from = 0, dragOffsetY = 560f, rowHeightPx = row, size = 0))
        assertEquals(0, QueueDragRules.targetIndex(from = -1, dragOffsetY = 0f, rowHeightPx = row, size = 0))
    }

    @Test
    fun `落点与出发点相同就是白拖`() {
        assertTrue(QueueDragRules.isNoOp(3, 3))
        assertFalse(QueueDragRules.isNoOp(3, 4))
        assertFalse(QueueDragRules.isNoOp(3, 2))
    }

    @Test
    fun `往下拖两行真的把这一项往后挪了两格`() {
        // 这一组把「像素 → 下标 → 队列」整条路锁住：单看 QueueDragRules 只能证明
        // 它算出了 2，而这个断言证明**第 0 项真的变成了第 2 项**
        // （`QueueRules.move` 的语义是「先抽出来再插入」，所以落点 2 表示
        // 「插到原本第 2 项的位置」，也就是往后挪两格）。
        val queue = listOf("A", "B", "C", "D", "E")
        // 112 = 正好两个行高。
        val to = QueueDragRules.targetIndex(from = 0, dragOffsetY = 112f, rowHeightPx = row, size = queue.size)
        assertEquals(2, to)
        assertEquals(listOf("B", "C", "A", "D", "E"), QueueRules.move(queue, 0, to))
    }
}
