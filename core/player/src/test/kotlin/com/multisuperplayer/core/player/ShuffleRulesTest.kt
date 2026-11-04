package com.multisuperplayer.core.player

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 随机播放的顺序维护规则。
 *
 * 每条测试都对着一个**用户能直接感觉到**的病，而不是「函数返回了什么」：
 *
 * - 打开随机播放后紧接着听到的又是刚放完的那一首 ⇒ [ShuffleRules.orderStartingAt] 没把当前项放队首；
 * - 一轮播完又走回同一条路线（同样几首连着）⇒ 一轮结束时没重新洗牌；
 * - 随机播放里「上一首」跳到一首根本没听过的歌 ⇒ 重洗时把**已播历史**也洗了；
 * - 随机播放时莫名停下 / 反复播同一首 ⇒ 交回去的表不是排列（长度或项数不对），
 *   Media3 会抛异常或走到不存在的条目上。
 *
 * 随机的东西测起来靠「性质」而不是「值」：断言排列合法、前缀不变、
 * 以及**换 seed 之后结果真的会变**（这正是「下一首不可预知」的含义）。
 */
class ShuffleRulesTest {

    private val seeds = 0L..49L

    // -------------------------------------------------------------- 从当前项开始

    @Test
    fun `当前这一首排在队首其余打乱`() {
        for (seed in seeds) {
            val order = ShuffleRules.orderStartingAt(3, 5, Random(seed))
            assertEquals(3, order[0], "seed=$seed：当前项必须第一个走")
            assertContentEqualsSorted(order, 5)
        }
    }

    @Test
    fun `当前项越界时仍然给出一条合法排列`() {
        // 队列为空时 `currentMediaItemIndex` 是 -1；旧实现在这种情况下会
        // 建出一条含 -1 的表，Media3 直接抛 IllegalArgumentException。
        assertTrue(ShuffleRules.orderStartingAt(-1, 0, Random(1)).isEmpty())
        val order = ShuffleRules.orderStartingAt(-1, 4, Random(1))
        assertEquals(4, order.size)
        assertContentEqualsSorted(order, 4)
    }

    @Test
    fun `只有一条时不需要洗`() {
        assertContentEquals(intArrayOf(0), ShuffleRules.orderStartingAt(0, 1, Random(3)))
    }

    @Test
    fun `换一个随机源队首之后的顺序就会变`() {
        // 「下一首不可预知」的判据：同一个当前项、不同 seed，队首之后的第一首
        // 不能永远是同一个。固定的话，用户听两轮就摸出规律了。
        val followers = seeds.map { ShuffleRules.orderStartingAt(0, 8, Random(it))[1] }.toSet()
        assertTrue(followers.size > 1, "队首之后的第一首始终是 $followers，等于没随机")
    }

    // -------------------------------------------------------------- 自然切歌之后

    @Test
    fun `已播历史原样保留只有未播部分重洗`() {
        // 表：2 → 0 → 3 → 1 → 4，现在切到 3（第 2 位）。
        // 历史是 [2, 0]，未播是 [1, 4]，重洗只发生在这两个里。
        val permutation = intArrayOf(2, 0, 3, 1, 4)
        for (seed in seeds) {
            val next = ShuffleRules.orderAfterAdvance(permutation, 3, Random(seed))
            assertContentEquals(intArrayOf(2, 0), next.copyOfRange(0, 2), "seed=$seed：历史被洗了")
            assertEquals(3, next[2], "seed=$seed：当前项的位置不能动")
            assertEquals(setOf(1, 4), next.copyOfRange(3, 5).toSet(), "seed=$seed：未播项变了")
            assertContentEqualsSorted(next, 5)
        }
    }

    @Test
    fun `切歌之后接下来那一首会变`() {
        val permutation = intArrayOf(0, 1, 2, 3, 4, 5)
        val followers = seeds.map {
            ShuffleRules.orderAfterAdvance(permutation, 1, Random(it))[3]
        }.toSet()
        assertTrue(followers.size > 1, "每次切歌都排同一个下一首，等于没重洗")
    }

    @Test
    fun `一轮结束时当前项排在末尾让新一轮从别的歌开始`() {
        // 断言的是「绕回落到哪里」，而不是「表长什么样」：Media3 走完最后一首后
        // 绕回的是 `getFirstIndex()`，所以当前这一首必须待在**末尾**。
        // 放在队首时它绕回时正好被跳过 ⇒ 新一轮只剩 n-1 首，而且下一轮的队首
        // 又被它钉死（设备上就是这个症状：同几首总在每轮开头）。
        val permutation = intArrayOf(2, 0, 3, 1, 4)
        for (seed in seeds) {
            val next = ShuffleRules.orderAfterAdvance(permutation, 4, Random(seed))
            assertEquals(4, next[4], "seed=$seed：当前项必须留在末尾")
            assertTrue(next[0] != 4, "seed=$seed：新一轮的队首不能是刚放完的这一首")
            assertEquals(setOf(0, 1, 2, 3), next.copyOfRange(0, 4).toSet(), "seed=$seed：漏了或多了曲目")
            assertContentEqualsSorted(next, 5)
        }
    }

    @Test
    fun `跨轮边界不会紧接着重播刚听完的那一首`() {
        // 一轮 n 首在边界处必然重复，但「刚听完就又是它」会被感知成故障，
        // 所以上一轮末尾的两首压到新一轮尾部。
        // 前段只剩 `5 - 1(当前项) - 2(延后)` = 2 格，被延后的两首必须全部落在后半区。
        val permutation = intArrayOf(2, 0, 3, 1, 4)
        val freshSize = 2
        for (seed in seeds) {
            val next = ShuffleRules.orderAfterAdvance(permutation, 4, Random(seed))
            for (late in intArrayOf(1, 3)) {
                assertTrue(
                    next.indexOf(late) >= freshSize,
                    "seed=$seed：$late 是上一轮刚听完的，不该马上又听到：${next.toList()}",
                )
            }
        }
    }

    @Test
    fun `走到表尾时一定给出与上一轮不同的路线`() {
        // 表尾只剩当前项一项「未播」时，若重洗对剩下的部分是无操作，
        // 这条表会和上一轮一模一样。调用方靠「新旧是否相同」决定要不要安装，
        // 于是这一轮实际又走了上一轮的路线 —— 随机播放整轮失效、且不报任何错。
        val length = 5
        for (seed in seeds) {
            val start = ShuffleRules.orderStartingAt(0, length, Random(seed))
            val currentIndex = start[length - 1]
            val next = ShuffleRules.orderAfterAdvance(start, currentIndex, Random(seed))
            assertFalse(
                next.contentEquals(start),
                "seed=$seed：走到表尾后路线没有任何变化：${start.toList()}",
            )
            assertEquals(currentIndex, next[length - 1], "seed=$seed：当前项必须留在表尾")
            assertTrue(ShuffleRules.isPermutation(next), "seed=$seed：交回去的不是排列")
        }
    }

    @Test
    fun `切歌时把未播部分重新洗一遍`() {
        // 这就是「一轮播完后再洗牌」的正题：如果重洗只是把原表原样交回去，
        // 用户第二轮听到的顺序和第一轮一模一样（旧行为）。
        // 洗牌本身有极小概率恰好洗回原样（6 项里 1/120），所以这里断言的是
        // **绝大多数 seed 都会换一条路线**，而不是「一次都不许撞上」。
        val permutation = intArrayOf(0, 1, 2, 3, 4, 5)
        val originalTail = permutation.copyOfRange(1, 6)
        val routes = seeds.map { seed ->
            ShuffleRules.orderAfterAdvance(permutation, 0, Random(seed)).toList()
        }
        val repeated = routes.count { it.drop(1) == originalTail.toList() }
        assertTrue(repeated < 5, "$repeated 个 seed 洗出来还是原顺序，重洗没生效")
        assertTrue(routes.toSet().size > 10, "所有 seed 只洗出 ${routes.toSet().size} 种路线")
    }

    // -------------------------------------------------------------- 坏输入

    @Test
    fun `表里有重复项时退化成重新洗一副`() {
        val broken = intArrayOf(0, 0, 2, 2, 4)
        val next = ShuffleRules.orderAfterAdvance(broken, 2, Random(5))
        assertEquals(2, next[0])
        assertContentEqualsSorted(next, 5)
    }

    @Test
    fun `表里找不到当前项时退化成重新洗一副`() {
        val broken = intArrayOf(0, 1, 2)
        val next = ShuffleRules.orderAfterAdvance(broken, 7, Random(5))
        assertEquals(3, next.size)
        assertContentEqualsSorted(next, 3)
    }

    @Test
    fun `长度为零或一原样返回`() {
        assertTrue(ShuffleRules.orderAfterAdvance(intArrayOf(), 0, Random(1)).isEmpty())
        assertContentEquals(intArrayOf(0), ShuffleRules.orderAfterAdvance(intArrayOf(0), 0, Random(1)))
    }

    // -------------------------------------------------------------- 排列判定

    @Test
    fun `排列判定`() {
        assertTrue(ShuffleRules.isPermutation(intArrayOf(0, 1, 2)))
        assertTrue(ShuffleRules.isPermutation(intArrayOf(2, 0, 1)))
        assertTrue(ShuffleRules.isPermutation(intArrayOf()))
        assertFalse(ShuffleRules.isPermutation(intArrayOf(0, 0)))
        assertFalse(ShuffleRules.isPermutation(intArrayOf(0, 2)))
        assertFalse(ShuffleRules.isPermutation(intArrayOf(-1, 0)))
    }

    // -------------------------------------------------------------- 整轮模拟

    @Test
    fun `一整轮里每一首都只播一次不会重复或遗漏`() {
        // 「随机播放」最基本的那条承诺：一轮之内不重复。表本身是排列就等价于
        // 这一条，但把它按「真的走一遍」写出来，是因为走位（`(pos + 1) % n`）
        // 和重洗的时机同时出错时，光看排列合法性看不出来。
        val size = 6
        var order = ShuffleRules.orderStartingAt(0, size, Random(21))
        var current = order[0]
        val played = mutableListOf(current)
        for (step in 1 until size) {
            val position = order.indexOf(current)
            current = order[(position + 1) % size]
            played += current
            order = ShuffleRules.orderAfterAdvance(order, current, Random(step.toLong()))
        }
        assertEquals(size, played.toSet().size, "一轮里出现了重复：$played")
        assertEquals((0 until size).toSet(), played.toSet())
        // 走完一整轮之后仍然是一条合法表（否则下一条会被跳到不存在的位置）。
        assertTrue(ShuffleRules.isPermutation(order))
    }

    @Test
    fun `连续多轮每轮都播满且轮首换成了别的歌`() {
        // 单轮测试漏掉的正是这条：轮末把当前项放队首时，绕回会跳过它，
        // 于是第二轮只有 n-1 首、第三轮的轮首又固定成同一首。
        // 这里按 Media3 的真实走位（`(position + 1) % n`，末尾绕回队首）连走多轮。
        // 一轮的边界是「表被换成新一轮、当前项落在表尾」，下一轮从新表的第 0 格起走。
        // 所以每轮恰好在表里走 `size` 格，current 的格号序列是 0,1,…,size-1，绕回落到 0。
        val size = 5
        var order = ShuffleRules.orderStartingAt(0, size, Random(21))
        var current = order[0]
        var seed = 1L
        repeat(8) { round ->
            val played = mutableListOf(current)
            for (step in 1 until size) {
                val position = order.indexOf(current)
                current = order[(position + 1) % size]
                played += current
                order = ShuffleRules.orderAfterAdvance(order, current, Random(seed++))
            }
            assertEquals(size, played.toSet().size, "第 ${round + 1} 轮里有重复：$played")
            assertEquals((0 until size).toSet(), played.toSet(), "第 ${round + 1} 轮漏了曲目：$played")
            assertEquals(size - 1, order.indexOf(current), "第 ${round + 1} 轮结束时当前项不在表尾")
            // 绕回：落到新表第 0 格，且不能是刚听完的那几首。
            val nextFirst = order[(order.indexOf(current) + 1) % size]
            assertTrue(nextFirst != current, "第 ${round + 1} 轮绕回时又落到刚才那一首")
            assertTrue(
                nextFirst !in played.takeLast(3),
                "第 ${round + 1} 轮绕回时落到了刚听完的曲目：$played 之后是 $nextFirst",
            )
            order = ShuffleRules.orderAfterAdvance(order, nextFirst, Random(seed++))
            current = nextFirst
        }
    }

    /** 排序后应当是 `0 until length` 的一条完整排列。 */
    private fun assertContentEqualsSorted(order: IntArray, length: Int) {
        assertEquals((0 until length).toList(), order.sorted(), "不是合法排列：${order.toList()}")
    }
}
