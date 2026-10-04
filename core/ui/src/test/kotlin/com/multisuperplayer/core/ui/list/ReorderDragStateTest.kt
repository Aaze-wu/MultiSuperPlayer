package com.multisuperplayer.core.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动状态（[ReorderDragState]）的测试。
 *
 * 纯算术那一层在 `ReorderDragRulesTest` 里（「位移换算成跨几行」）。这里锁的是
 * 状态机自己那几件事，都是**只有状态知道**的：
 *
 * 1. 跨过一行之后位移要扣掉一整行（不扣就会跳两格）；
 * 2. 到顶/到底之后位移被夹在半行以内（不夹那一行会飘出列表）；
 * 3. 跨过几行就报几次 `onMove`，而且是**逐格**报（预览是一格一格挪的）；
 * 4. 松手只在真的换了位置时提交一次；手势被收走时一次都不提交。
 *
 * 手势能不能真的把手指交给这个状态（`pointerInput` 那一层）测不了：
 * `core:ui` 没有 compose-ui-test 依赖，而且那本来要靠真机确认。
 */
class ReorderDragStateTest {

    /** 被测的行高。用整数只是为了读起来清楚，实现里没有任何「必须是整数」的假设。 */
    private val row = 56f

    private fun state(itemCount: Int, index: Int = 0, fingerY: Float = 100f) =
        ReorderDragState().apply {
            start(index = index, fingerY = fingerY, rowHeightPx = row, itemCount = itemCount)
        }

    @Test
    fun `开始拖动时记住起点`() {
        val drag = state(itemCount = 5, index = 2, fingerY = 300f)

        assertEquals(2, drag.from)
        assertEquals(2, drag.originIndex)
        assertEquals(0f, drag.offsetY)
        assertEquals(300f, drag.fingerY)
        assertTrue(drag.isPicked(2))
        assertFalse(drag.isPicked(1))
    }

    @Test
    fun `跨过一行之后位移扣掉一整行`() {
        val drag = state(itemCount = 5)

        drag.moveFingerBy(row)

        // 数据本身往前挪了一格，所以「相对这一格」的位移要归零，而不是留一整行。
        assertEquals(1, drag.from)
        assertEquals(0f, drag.offsetY)
    }

    @Test
    fun `半行以内不换位`() {
        val drag = state(itemCount = 5)

        drag.moveFingerBy(row / 3f)
        assertEquals(0, drag.from)

        // 超过半行才换：和 `ReorderDragRules` 的约定一致。
        drag.moveFingerBy(row / 3f)
        assertEquals(1, drag.from)
    }

    @Test
    fun `一次跨两行会逐格报两次`() {
        val drag = state(itemCount = 5)
        val moves = mutableListOf<Pair<Int, Int>>()
        drag.onMove = { from, to -> moves += from to to }

        drag.moveFingerBy(row * 2f)

        assertEquals(2, drag.from)
        assertEquals(0f, drag.offsetY)
        // 逐格报：跨两格时不能跳过中间那一格，否则预览里的「谁跟谁换了」对不上。
        assertEquals(listOf(0 to 1, 1 to 2), moves)
    }

    @Test
    fun `往回跨也逐格报`() {
        val drag = state(itemCount = 5, index = 3)
        val moves = mutableListOf<Pair<Int, Int>>()
        drag.onMove = { from, to -> moves += from to to }

        drag.moveFingerBy(-140f)

        assertEquals(0, drag.from)
        // 拖回原位之后多出来的那一点位移被夹在半行以内（不然那一行会飘出列表）。
        assertEquals(row / 2f, drag.offsetY)
        assertEquals(listOf(3 to 2, 2 to 1, 1 to 0), moves)
    }

    @Test
    fun `到底之后位移夹在半行以内`() {
        val drag = state(itemCount = 5, index = 4)
        val moves = mutableListOf<Pair<Int, Int>>()
        drag.onMove = { from, to -> moves += from to to }

        drag.moveFingerBy(row * 4f)

        // `from` 不能再往外走了（没有第 5 格），但手指的位移要留下来一点，
        // 这样那一行还贴在边上、手指退回来时立刻就跟上。
        assertEquals(4, drag.from)
        assertEquals(row / 2f, drag.offsetY)
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `到顶之后位移夹在半行以内`() {
        val drag = state(itemCount = 5, index = 1)

        drag.moveFingerBy(-row * 4f)

        assertEquals(0, drag.from)
        assertEquals(-row / 2f, drag.offsetY)
    }

    @Test
    fun `列表自己滚动时被拖的那一行跟着走`() {
        val drag = state(itemCount = 6, index = 2, fingerY = 200f)
        val moves = mutableListOf<Pair<Int, Int>>()
        drag.onMove = { from, to -> moves += from to to }

        drag.contentScrolledBy(row)

        assertEquals(3, drag.from)
        assertEquals(listOf(2 to 3), moves)
        // 手指在屏幕上没动：贴不贴边是看手指的位置，不能被自动滚动带跑。
        assertEquals(200f, drag.fingerY)
    }

    @Test
    fun `松手时只提交一次起点到终点`() {
        val drag = state(itemCount = 5, index = 1)
        val drops = mutableListOf<Pair<Int, Int>>()
        drag.onDrop = { origin, final -> drops += origin to final }

        drag.moveFingerBy(row * 2f)
        drag.end()

        // 拖动中一格一格挪过两格，但落盘只有一次：结果和逐格写两次完全一样，
        // 而后者会让队列里的当前播放项无谓地重新缓冲。
        assertEquals(listOf(1 to 3), drops)
        assertNull(drag.from)
        assertEquals(-1, drag.originIndex)
        assertEquals(0f, drag.offsetY)
    }

    @Test
    fun `没换位就松手不提交`() {
        val drag = state(itemCount = 5, index = 1)
        val drops = mutableListOf<Pair<Int, Int>>()
        drag.onDrop = { origin, final -> drops += origin to final }

        // 手指落下的自然抖动，或者拖出去又拖回来了。
        drag.moveFingerBy(row / 4f)
        drag.moveFingerBy(-row / 4f)
        drag.end()

        assertTrue(drops.isEmpty())
        assertNull(drag.from)
    }

    @Test
    fun `手势被收走时不提交并且通知调用方`() {
        val drag = state(itemCount = 5, index = 1)
        val drops = mutableListOf<Pair<Int, Int>>()
        var cancels = 0
        drag.onDrop = { origin, final -> drops += origin to final }
        drag.onCancel = { cancels++ }

        drag.moveFingerBy(row * 2f)
        drag.cancel()

        // 用户没松手，这一下不算数。
        assertTrue(drops.isEmpty())
        assertEquals(1, cancels)
        assertNull(drag.from)
    }

    @Test
    fun `没有拖动时松手不提交`() {
        val drag = ReorderDragState()
        val drops = mutableListOf<Pair<Int, Int>>()
        var cancels = 0
        drag.onDrop = { origin, final -> drops += origin to final }
        drag.onCancel = { cancels++ }

        drag.end()
        drag.end()
        drag.cancel()

        assertTrue(drops.isEmpty())
        // `cancel` 是幂等的：它的活是「把预览收掉」，多收一次没有副作用。
        // （手势那边只会在 `start` 之后的取消分支里调它，所以这里也不会白喊。）
        assertEquals(1, cancels)
        assertNull(drag.from)
    }

    @Test
    fun `行高还没量出来时只画动数据不动`() {
        val drag = ReorderDragState()
        // 行还没被布局过（或者列表是空的）就会走到这里：不能拿 0 去当行高算，
        // 否则「除以零」算出来的落点会跳到列表外面去。
        drag.start(index = 1, fingerY = 0f, rowHeightPx = 0f, itemCount = 5)

        drag.moveFingerBy(row * 3f)

        assertEquals(1, drag.from)
        // 不夹：连行高都不知道，夹在半行以内就成了「夹在 0 以内」。
        assertEquals(row * 3f, drag.offsetY)
    }

    @Test
    fun `拖完一次还能接着拖下一次`() {
        val drag = state(itemCount = 5, index = 0)
        val drops = mutableListOf<Pair<Int, Int>>()
        drag.onDrop = { origin, final -> drops += origin to final }

        drag.moveFingerBy(row)
        drag.end()
        // 第二次从新的位置开始：`originIndex` 必须是新的，不能还留着上一次的。
        drag.start(index = 3, fingerY = 100f, rowHeightPx = row, itemCount = 5)
        drag.moveFingerBy(-row)
        drag.end()

        assertEquals(listOf(0 to 1, 3 to 2), drops)
    }

    @Test
    fun `只有一行时拖不动`() {
        val drag = state(itemCount = 1)
        val moves = mutableListOf<Pair<Int, Int>>()
        drag.onMove = { from, to -> moves += from to to }

        drag.moveFingerBy(row * 2f)

        // 数据不动，也不报「换了位」；位移还是留下半行，所以那一行会跟着手指偏一点
        // （看得出自己真的拖上了，而不是以为没反应）。
        assertEquals(0, drag.from)
        assertTrue(moves.isEmpty())
        assertEquals(row / 2f, drag.offsetY)
    }
}
