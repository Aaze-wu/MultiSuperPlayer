package com.multisuperplayer.core.player

/**
 * 播放队列的编辑规则。
 *
 * ## 为什么单独一份纯函数
 *
 * 「删掉第 3 首之后，正在播的那首变成第几首」是一个**很容易写反、而且写反了
 * 看不出来**的算术题：列表本身还是列表，界面照样画得出来，只是「当前项」
 * 那一行的高亮跑到了隔壁，或者干脆整行不见了。
 *
 * 更麻烦的是它必须和**内核**对同一件事的理解一致：真正的编辑动作是
 * `player.removeMediaItem()` 做的，内核自己会算一遍新的当前下标；
 * 如果我们这边算出来的 `_queue` 和内核的播放列表差了哪怕一格，
 * `syncCurrentEntry()` 就会把**另一条**媒体当成当前条目显示出来
 * （而且下一条命令会基于这个错的假设继续错下去）。
 *
 * 所以这里的每一条都是「两边必须同意的那份算术」，并且逐条被单测钉住。
 *
 * ## 泛型而不是 `MediaEntry`
 *
 * 和 `PlaylistRules` 不同：那边直接收 `PlaylistItem`，因为它要按 `mediaId` 去重、
 * 要处理快照。这里只做「同一个列表换一下顺序」，元素是什么都成立——
 * 而写成泛型让单测可以用几个字母当元素，把「第 3 项跑到哪去了」
 * 直接读出来，不用先造一堆 `MediaEntry`。
 */
object QueueRules {

    /**
     * 删掉 [index] 那一项，返回新列表。
     *
     * 越界时**原样返回同一个实例**，而不是抛异常或者夹到最近的位置：
     * 夹取会让用户在拖拽/连点期间「删掉了一条他没指的」，
     * 而返回同一实例让调用方能按 `!==` 判断「这一次什么都没发生，不用命令内核」。
     */
    fun <T> removeAt(items: List<T>, index: Int): List<T> {
        if (index !in items.indices) return items
        val result = items.toMutableList()
        result.removeAt(index)
        return result
    }

    /**
     * 把 [from] 那一项移到 [to]。
     *
     * 语义是**先抽出来、再插进去**（`remove` 之后再 `add(to, …)`），
     * 和 Media3 `moveMediaItem` 内部用的 `Util.moveItems` 一致。
     *
     * 「插到 to 之前还是之后」是这里唯一会把人绕晕的地方，用例子说清：
     * `[A,B,C,D]` 把 A（0）移到 2 —— 先把 A 抽出来得到 `[B,C,D]`，
     * 再把 A 插到下标 2，结果是 `[B,C,A,D]`，**不是** `[B,A,C,D]`。
     * 后者是「插到原来第 2 项之前」，是另一种语义（`add(to, …)` 在
     * `removeAt(from)` **之前**做才会得到它）。两者都「看起来对」，
     * 但界面上的下标是用户看着列表给的，只有前者和拖拽的直觉一致。
     *
     * `from == to` 与越界都原样返回：拖动时手指可能没真正跨过任何一行，
     * 那时不该让列表（以及内核）白白动一次。
     */
    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from == to) return items
        if (from !in items.indices || to !in items.indices) return items
        val result = items.toMutableList()
        result.add(to, result.removeAt(from))
        return result
    }

    /**
     * 删掉 [removedIndex] 之后，原来在 [currentIndex] 的那一项落在哪一格。
     *
     * @param newSize 删完之后列表的长度，用来把结果夹进合法范围。
     *   它会出现在边界上：删掉的是**最后一项**、而当前项正好也是它时，
     *   内核会退到前一项（没有下一项可跳），我们这边也必须退——
     *   不退就会得到一个指向列表末尾之外的当前下标。
     */
    fun indexAfterRemoval(currentIndex: Int, removedIndex: Int, newSize: Int): Int {
        if (newSize <= 0) return 0
        val shifted = if (removedIndex < currentIndex) currentIndex - 1 else currentIndex
        return shifted.coerceIn(0, newSize - 1)
    }

    /**
     * 移动 [from] 到 [to] 之后，原来在 [currentIndex] 的那一项落在哪一格。
     *
     * 分三段：
     * 1. **被移动的就是当前项**：它直接落到 [to]。
     * 2. 当前项在 [from] 之后：被抽走的那个位置在它前面，所以先整体前移一格。
     * 3. 再按「插入点是不是在它前面（或正好是它）」决定要不要往后让一格。
     *
     * 第 3 步用的是 `to <= withoutSource` 而不是 `<`：抽掉之后那个「让出来的洞」
     * 本身就占了 `withoutSource` 这个下标，插进那个洞等于插在它前面。
     * 少了这个等号，`[A,B,C]` 把 C 移到 0 时会算成「B 还在 1」，
     * 而实际列表是 `[C,A,B]`——B 在 2。
     */
    fun indexAfterMove(currentIndex: Int, from: Int, to: Int): Int {
        if (from == to) return currentIndex
        if (currentIndex == from) return to
        val withoutSource = if (from < currentIndex) currentIndex - 1 else currentIndex
        return if (to <= withoutSource) withoutSource + 1 else withoutSource
    }
}
