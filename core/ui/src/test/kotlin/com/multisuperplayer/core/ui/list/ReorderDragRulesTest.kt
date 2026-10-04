package com.multisuperplayer.core.ui.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拖动排序落点换算的测试。
 *
 * 每一组测的都是**真实会发生的一种拖拽**（手抖、拖半行、拖出列表、行高还没测出来），
 * 而不是把实现抄一遍。
 *
 * 「算出来的下标喂给 `move` 之后列表真的动了」这一层不在这里：那需要同时看见
 * 本对象和调用方的 `move`，锁在两个 feature 各自的测试里
 * （`feature:player` 的播放队列、`feature:library` 的播放列表详情）。
 * 但**挪这一下本身**（[ReorderDragRules.move]）在这里测：三个调用点共用它，
 * 而它和真实数据是两份，掉了/重了一条都不报错。
 */
class ReorderDragRulesTest {

    /** 被测的行高。用整数只是为了读起来清楚，实现里没有任何「必须是整数」的假设。 */
    private val row = 56f

    @Test
    fun `没动就不换位`() {
        assertEquals(0, ReorderDragRules.rowDelta(0f, row))
        // 一两个像素的位移是手指落下时的自然抖动，也算「没动」。
        assertEquals(0, ReorderDragRules.rowDelta(1f, row))
        assertEquals(0, ReorderDragRules.rowDelta(-1f, row))
    }

    @Test
    fun `正好半行算换位`() {
        // 边界取「算」而不是「不算」：手指停在正好半行的位置时，用户的心智是
        // 「我已经拖过去一半了」，此时不换位看起来像卡住。
        assertEquals(1, ReorderDragRules.rowDelta(28f, row))
        assertEquals(-1, ReorderDragRules.rowDelta(-28f, row))
    }

    @Test
    fun `不到半行不换位`() {
        // 手抖。这一组是这套规则存在的**主要原因**：没有它，列表会在轻微滑动里
        // 来回换位，想点的行会从手指底下跑掉。
        assertEquals(0, ReorderDragRules.rowDelta(27f, row))
        assertEquals(0, ReorderDragRules.rowDelta(-27f, row))
    }

    @Test
    fun `上下对称`() {
        // 负数侧写成 `(rows + 0.5f).toInt()` 的话 `-0.5` 会算成 0（向零取整），
        // 于是「往上拖」比「往下拖」需要多拖半行——只有手感差异，真机上很难发现。
        val offsets = listOf(0f, 10f, 28f, 40f, 56f, 84f, 112f, 300f)
        offsets.forEach { offset ->
            assertEquals(
                "位移 $offset 与 -$offset 的跨行数必须对称",
                ReorderDragRules.rowDelta(offset, row),
                -ReorderDragRules.rowDelta(-offset, row),
            )
        }
    }

    @Test
    fun `跨过多行按行数算`() {
        assertEquals(2, ReorderDragRules.rowDelta(84f, row))
        assertEquals(2, ReorderDragRules.rowDelta(112f, row))
        assertEquals(3, ReorderDragRules.rowDelta(140f, row))
        assertEquals(-3, ReorderDragRules.rowDelta(-140f, row))
    }

    @Test
    fun `行高还没测出来时不换位`() {
        // 行高为 0 只可能来自「这一帧还没测量」，此时任何非零结果都会让列表跳一下。
        assertEquals(0, ReorderDragRules.rowDelta(100f, 0f))
        assertEquals(0, ReorderDragRules.rowDelta(100f, -56f))
        assertEquals(0, ReorderDragRules.rowDelta(100f, Float.NaN))
        assertEquals(0, ReorderDragRules.rowDelta(100f, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `位移不是有限数时不换位`() {
        assertEquals(0, ReorderDragRules.rowDelta(Float.NaN, row))
        assertEquals(0, ReorderDragRules.rowDelta(Float.POSITIVE_INFINITY, row))
    }

    @Test
    fun `落点按出发点加跨行数算`() {
        assertEquals(2, ReorderDragRules.targetIndex(from = 0, dragOffsetY = 112f, rowHeightPx = row, size = 5))
        assertEquals(0, ReorderDragRules.targetIndex(from = 2, dragOffsetY = -112f, rowHeightPx = row, size = 5))
    }

    @Test
    fun `往下拖出列表就落到最后一位`() {
        // 用户拖到列表外面时想要的是「放到最后」，不是「什么都没发生」。
        assertEquals(4, ReorderDragRules.targetIndex(from = 3, dragOffsetY = 560f, rowHeightPx = row, size = 5))
        assertEquals(4, ReorderDragRules.targetIndex(from = 0, dragOffsetY = 560f, rowHeightPx = row, size = 5))
    }

    @Test
    fun `往上拖出列表就落到第一位`() {
        assertEquals(0, ReorderDragRules.targetIndex(from = 4, dragOffsetY = -560f, rowHeightPx = row, size = 5))
    }

    @Test
    fun `只有一项时怎么拖都落在第零位`() {
        assertEquals(0, ReorderDragRules.targetIndex(from = 0, dragOffsetY = 560f, rowHeightPx = row, size = 1))
        assertEquals(0, ReorderDragRules.targetIndex(from = 0, dragOffsetY = -560f, rowHeightPx = row, size = 1))
    }

    @Test
    fun `空列表不会算出负数下标`() {
        // 正常走不到（空列表时界面显示空态），但下标为负会直接崩在 LazyColumn 上，
        // 而这一层是纯函数、拦住的成本只是这一个断言。
        assertEquals(0, ReorderDragRules.targetIndex(from = 0, dragOffsetY = 560f, rowHeightPx = row, size = 0))
        assertEquals(0, ReorderDragRules.targetIndex(from = -1, dragOffsetY = 0f, rowHeightPx = row, size = 0))
    }

    @Test
    fun `落点与出发点相同就是白拖`() {
        assertTrue(ReorderDragRules.isNoOp(3, 3))
        assertFalse(ReorderDragRules.isNoOp(3, 4))
        assertFalse(ReorderDragRules.isNoOp(3, 2))
    }

    // ---- 真的挪一下 ----

    @Test
    fun `往下挪一格`() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), ReorderDragRules.move(items, 0, 1))
        assertEquals(listOf("a", "c", "b"), ReorderDragRules.move(items, 1, 2))
    }

    @Test
    fun `往上挪一格`() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), ReorderDragRules.move(items, 1, 0))
        assertEquals(listOf("a", "c", "b"), ReorderDragRules.move(items, 2, 1))
    }

    @Test
    fun `挪过几格之后名单一条不多一条不少`() {
        // 拖动的预览顺序和真实数据是两份。掉了或重了一条都不会报错，
        // 只会让界面显示一个不存在的顺序（松手后看起来就是「顺序自己变了」）。
        val items = listOf("a", "b", "c", "d", "e")
        val moved = ReorderDragRules.move(items, 0, 4)
        assertEquals(listOf("b", "c", "d", "e", "a"), moved)
        assertEquals(items.size, moved.size)
        assertEquals(items.toSet(), moved.toSet())
    }

    @Test
    fun `拖动中逐格挪和松手时一次挪到位是同一个结果`() {
        // 拖动中每跨一行就报一次「谁和谁换了位」（逐步），松手时只报一次
        // 「从哪到哪」（一次到位）。这两条路必须落到同一个顺序上，否则
        // 预览里看到的和松手之后得到的不是一回事——真机上那正是
        // 「松手之后顺序自己变了」。
        val items = listOf("a", "b", "c", "d", "e")
        for (from in items.indices) {
            for (to in items.indices) {
                var stepped = items
                val step = if (to >= from) 1 else -1
                var cursor = from
                while (cursor != to) {
                    stepped = ReorderDragRules.move(stepped, cursor, cursor + step)
                    cursor += step
                }
                assertEquals("从 $from 到 $to", ReorderDragRules.move(items, from, to), stepped)
            }
        }
    }

    @Test
    fun `原地挪或者越界时原样返回`() {
        val items = listOf("a", "b", "c")
        // 同一个实例：拖动中难免有「这一步没意义」，返回新列表会白触发一次重组。
        assertSame(items, ReorderDragRules.move(items, 1, 1))
        assertSame(items, ReorderDragRules.move(items, -1, 2))
        assertSame(items, ReorderDragRules.move(items, 0, 3))
        assertSame(items, ReorderDragRules.move(items, 3, 0))
        val empty = emptyList<String>()
        assertSame(empty, ReorderDragRules.move(empty, 0, 0))
    }

    // ---- 自动滚动 ----

    /** 视口高。取一个和真机一个量级的值（手机竖屏可滚动区大约就这么高）。 */
    private val viewport = 800f

    /** 贴边带的高度。 */
    private val edge = 72f

    /** 速度是算出来的小数，比较一律给容差——注意必须显式写成三参数版本，
     * `assertEquals(0f, x)` 走的不是浮点重载（Kotlin 不做隐式加宽），很容易
     * 变成「拿装箱后的 Float 对象比相等」，那样一个 -0.0f 就能让断言莫名其妙地红。*/
    private fun assertSpeed(expected: Float, actual: Float) {
        assertEquals("速度不对", expected, actual, TOLERANCE)
    }

    private fun speed(fingerY: Float) = ReorderDragRules.autoScrollPxPerSecond(
        fingerY = fingerY,
        viewportHeightPx = viewport,
        edgePx = edge,
        maxPxPerSecond = 1200f,
    )

    @Test
    fun `手指在视野中间时不滚`() {
        // 不滚是默认状态：整套自动滚动只在手指贴近边缘时才存在，
        // 否则「拖一下列表自己动起来」会显得像失控。
        assertSpeed(0f, speed(400f))
        assertSpeed(0f, speed(edge))
        assertSpeed(0f, speed(viewport - edge))
    }

    @Test
    fun `贴底边往下滚，贴顶边往上滚`() {
        assertTrue("贴底边必须往后滚（正）", speed(viewport - 1f) > 0f)
        assertTrue("贴顶边必须往前滚（负）", speed(1f) < 0f)
    }

    @Test
    fun `越靠边滚得越快`() {
        // 只有「最快那一档」的话长列表会一路冲过头，只有匀速的话「只想滚一点」
        // 就做不到，所以必须是渐变的。这里只断言单调，不锁具体系数。
        val speeds = listOf(viewport - edge + 1f, viewport - edge / 2f, viewport - 1f).map(::speed)
        speeds.forEach { assertTrue("贴底时每一档都该是正的，实际 $speeds", it > 0f) }
        assertTrue("越靠底边越快，实际 $speeds", speeds[0] < speeds[1] && speeds[1] < speeds[2])

        val upSpeeds = listOf(edge - 1f, edge / 2f, 1f).map(::speed)
        assertTrue(
            "越靠顶边越快（负得越多），实际 $upSpeeds",
            upSpeeds[0] > upSpeeds[1] && upSpeeds[1] > upSpeeds[2],
        )
    }

    @Test
    fun `正好贴到边就满速`() {
        assertSpeed(1200f, speed(viewport))
        assertSpeed(-1200f, speed(0f))
    }

    @Test
    fun `手指拖到视口外也不会超过满速`() {
        // 手指被拖到列表外面时差值会一路变大。不夹住的话列表会疯转，
        // 用户完全跟不上它滚到哪里了。
        assertSpeed(1200f, speed(viewport + 4000f))
        assertSpeed(-1200f, speed(-4000f))
    }

    @Test
    fun `上下两条带子不会重叠到反向滚`() {
        // 带子比半个视口还高时（小平板/横屏上真的会这样）两条带子会重叠，
        // 同一个位置既算贴顶又算贴底——真机上的表现是「拖着拖着自己往反方向滚」。
        // 夹到半屏之后，最中间那个点两侧都必须安静。
        val short = 100f
        fun shortSpeed(fingerY: Float) = ReorderDragRules.autoScrollPxPerSecond(
            fingerY = fingerY,
            viewportHeightPx = short,
            edgePx = 80f,
            maxPxPerSecond = 1200f,
        )
        assertSpeed(0f, shortSpeed(short / 2f))
        assertTrue(shortSpeed(short / 2f + 1f) > 0f)
        assertTrue(shortSpeed(short / 2f - 1f) < 0f)
    }

    @Test
    fun `尺寸还没测出来时不滚`() {
        // 第一帧 `layoutInfo` 还是空的，视口高度是 0。此时算出任何速度都会让
        // 列表在手指一落下就开始自己走。
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(10f, 0f, edge, 1200f))
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(10f, -800f, edge, 1200f))
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(Float.NaN, viewport, edge, 1200f))
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(10f, Float.NaN, edge, 1200f))
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(10f, viewport, Float.NaN, 1200f))
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(10f, viewport, edge, Float.NaN))
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(10f, viewport, 0f, 1200f))
        assertSpeed(0f, ReorderDragRules.autoScrollPxPerSecond(10f, viewport, edge, 0f))
    }

    private companion object {
        /** 浮点比较的容差。算出来的是几百像素/秒的量级，1e-3 足够严。 */
        const val TOLERANCE = 1e-3f
    }
}
