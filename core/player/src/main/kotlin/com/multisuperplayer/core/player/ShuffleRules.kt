package com.multisuperplayer.core.player

import kotlin.random.Random

/**
 * 随机播放的**顺序维护规则**（纯函数，可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * Media3 自带 `shuffleModeEnabled` 只做一件事：按它内部那张乱序表
 * （`ShuffleOrder`）走。那张表在我们的用法里是**建好就不再动**的，于是实测行为是：
 *
 * - 一轮播完回到队首，之后又是**同一条路线**（同样是那几首连着）；
 * - 「下一首」是表里写死的，跟用户此刻听了什么无关。
 *
 * 这里补的就是这一步：一次**自然切歌**之后，把「还没播到的那些」重新洗一次，
 * 再通过 `player.setShuffleOrder(...)` 交回去。Media3 那个方法内部会重新
 * `maskWindowPosition`，也就是**保留当前条目和播放位置**，只换「后面怎么走」，
 * 所以边播边换是安全的。
 *
 * ## 不变式（每个函数都保证）
 *
 * 返回值是**完整排列**：长度等于队列长度，每个下标恰好出现一次。
 * 这不是审美问题——`ExoPlayerImpl.setShuffleOrder` 的第一句就是
 * `checkArgument(shuffleOrder.getLength() == mediaSourceHolderSnapshots.size())`，
 * 长度对不上直接抛 `IllegalArgumentException`。所以调用方还得再校验一次
 * 长度是否等于当前队列长度（队列可能在两帧之间被删过）。
 *
 * ## 术语
 *
 * 「排列」= 一条乱序表的完整内容，第 0 个元素就是「这一轮的队首」。
 * 已播的部分保持原序，**不做任何重排**——那部分是「历史」，重排会让
 * `skipToPrevious()` 跳到一首用户根本没听过的歌上。
 */
internal object ShuffleRules {

    /**
     * 一轮结束时，上一轮末尾的这么多首会被压到新一轮尾部。
     *
     * 目的只有一个：跨轮边界不要「刚听完又是它」。取 2 是权衡——
     * 再大就会让新一轮的开头几首变得可预测（可选曲目越少，随机感越弱）。
     */
    private const val TRAIL_SIZE = 2

    /**
     * 从 [currentIndex] 开始的排列：当前这一首在队首，其余打乱。
     *
     * 用于「刚打开随机播放」和「换了一条新队列」——这时还没有历史，
     * 用户期望的是「从现在这一首开始，剩下的随便放」。
     *
     * [currentIndex] 越界（比如队列为空时的 -1）时只返回其余项的打乱结果；
     * [length] 为 0 时返回空数组。
     */
    fun orderStartingAt(currentIndex: Int, length: Int, random: Random): IntArray {
        if (length <= 0) return IntArray(0)
        val first = currentIndex.takeIf { it in 0 until length }
        val rest = (0 until length).filterTo(ArrayList(length)) { it != first }
        rest.shuffle(random)
        if (first == null) return rest.toIntArray()
        val result = IntArray(length)
        result[0] = first
        for (i in rest.indices) result[i + 1] = rest[i]
        return result
    }

    /**
     * 自然切到 [currentIndex] 之后的**新排列**。
     *
     * 结构是「已播的历史（原序）+ 当前这一首 + 重新打乱的未播部分」。
     * 也就是说：当前这一首左边是听过的，右边是接下来要听的——而且右边每次都是新洗的。
     *
     * 当前这一首已经是**最后一个**（右边空了）时，说明这一轮播完了：除它以外
     * 全部重新洗牌，开始新一轮。
     *
     * 新一轮里**当前这一首排在最后一个**，这一点是必须的：Media3 走完表里最后
     * 一首之后是绕回 `getFirstIndex()`（`RepeatMode.ALL`），而不是绕回「当前项」。
     * 若把当前这首放在队首，绕回时它正好被跳过——新一轮实际只剩 n-1 首，而且
     * 下一轮的队首又被它钉死。设备上实测到的症状就是：同几首总在每轮开头出现、
     * 一定步数内重复听到同一首。
     *
     * 另外，「上一轮最后听过的那些」会被压到新一轮的**尾部**。一轮 n 首必然在
     * 边界处重复（数学上躲不掉），但「刚听完就又是它」是能被感知成故障的，
     * 所以边界两侧不重合。
     *
     * [permutation] 不合法（长度 ≤ 1、含重复、缺项、找不到 [currentIndex]）时，
     * 退化成 [orderStartingAt]，永远不返回一条坏表。
     */
    fun orderAfterAdvance(permutation: IntArray, currentIndex: Int, random: Random): IntArray {
        val length = permutation.size
        if (length <= 1) return permutation.copyOf()
        val position = permutation.indexOf(currentIndex)
        if (position < 0 || !isPermutation(permutation)) {
            return orderStartingAt(currentIndex, length, random)
        }

        val upcoming = ArrayList<Int>(length - position - 1)
        for (i in position + 1 until length) upcoming.add(permutation[i])
        if (upcoming.isEmpty()) {
            // 这一轮播完了：开始新一轮。
            // 刚听完的这几首（当前这一首 + 它左边的 TRAIL_SIZE 首）压到新一轮尾部。
            // 注意取的是 `position` **左边**，不是表的尾部——表的尾部是还没播到的曲目。
            val recentCount = minOf(TRAIL_SIZE, position)
            val recent = HashSet<Int>(recentCount * 2 + 2)
            for (i in position - recentCount until position) recent.add(permutation[i])
            recent.add(currentIndex)
            val fresh = ArrayList<Int>(length)
            val delayed = ArrayList<Int>(recentCount)
            for (value in permutation) {
                if (value == currentIndex) continue
                if (value in recent) delayed.add(value) else fresh.add(value)
            }
            fresh.shuffle(random)
            delayed.shuffle(random)
            val renewed = IntArray(length)
            var cursor = 0
            for (value in fresh) renewed[cursor++] = value
            for (value in delayed) renewed[cursor++] = value
            renewed[length - 1] = currentIndex
            if (length > 2 && renewed.contentEquals(permutation)) {
                // 前段只剩两格时，重洗有 1/4 的概率恰好洗回原序。
                // 这不只是「看起来没变」：调用方会因新旧相同而跳过安装，
                // 于是这一轮实际走的还是上一轮的路线——随机播放就整轮失效了。
                val swap = renewed[0]
                renewed[0] = renewed[1]
                renewed[1] = swap
            }
            return renewed
        }

        upcoming.shuffle(random)
        val result = IntArray(length)
        for (i in 0 until position) result[i] = permutation[i]
        result[position] = currentIndex
        for (i in upcoming.indices) result[position + 1 + i] = upcoming[i]
        return result
    }

    /**
     * 是否是一条合法排列（0..n-1 各出现一次）。
     *
     * 从 Media3 那边读回来的表理论上总是合法的，但这是**跨库边界**的数据：
     * 万一将来 Media3 换了实现、或者我们自己在 `cloneAnd*` 之后读错了，
     * 直接把坏表交回去会让播放器跳到不存在的条目上，症状是「随机播放时莫名停下」。
     */
    fun isPermutation(values: IntArray): Boolean {
        val seen = BooleanArray(values.size)
        for (value in values) {
            if (value < 0 || value >= values.size) return false
            if (seen[value]) return false
            seen[value] = true
        }
        return true
    }
}
