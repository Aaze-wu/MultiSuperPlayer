package com.multisuperplayer.feature.player

/**
 * 歌词自动滚动的「滚到哪」规则。
 *
 * ## 为什么不能按「第几行」来滚
 *
 * 原来那句 `animateScrollToItem(activeIndex - ANCHOR_OFFSET)` 假设**每一行一样高**，
 * 于是「让当前行落在视口上方第 2 行」就等于「当前行在视口里大概中间偏上」。可歌词
 * 的行高不是常数：
 *
 *  * 一句长歌词会折成 2~4 行（视口窄、字号大、大屏小屏都不一样）；
 *  * 双语模式下每一行歌词下面还跟着一行译文；
 *  * 当前行用的是 `titleMedium`，其余行是 `bodyLarge`——同一个 cue 在「变成当前行」
 *    的那一刻会**长高**。
 *
 * 于是锚点行一旦变高，当前行就被推到视口下沿之外：用户看到的是「这一句还有一段
 * 藏在下面」。更糟的一档是上面那两行也很长——当前行可以整行都在视口下方，屏幕上
 * 只剩上文的尾巴。这两种表现都不是「滚得不好看」，而是**当前该唱的那句看不见**。
 *
 * ## 正确的问题
 *
 * 别问「滚到第几行」，要问「**这一行现在在哪、多高**，还差多少像素才让它完整地
 * 待在视口里」。`LazyListState` 恰好只提供像素级的那一步（`animateScrollBy`），
 * `animateScrollToItem` 没有任何「居中 / 对齐」选项——所以这条判断只能自己写。
 *
 * ## 两条不变量
 *
 * 1. 放得进内容区 ⇒ 滚完以后这一行**整行可见**（居中，上下各留一半余量）；
 * 2. 放不进 ⇒ 顶边对齐（贴着内容区上沿）。一首歌里最长的那句真到了整块放不下的
 *    时候，让用户从**第一个字**开始读是唯一还有用的选择；居中只会把开头也切掉。
 *
 * ## 为什么是纯函数
 *
 * 和本模块其他 `*Rules` 同一条理由：`:feature:player` 没有 `compose-ui-test`，
 * `LazyListState` / `LazyListItemInfo` 在 JVM 单测里造不出来。判断留在 composable 里
 * 就等于没测——而且这类错误在界面上是「少看半句」，不会报任何异常。
 */
internal object LyricsScrollRules {

    /**
     * 把 [itemOffset] 处的歌词行挪进视口所需的**滚动量**，可以直接喂给
     * `LazyListState.animateScrollBy`：**正数 = 往下滚**（内容上移、行的偏移变小），
     * 负数 = 往回滚。
     *
     * ## 为什么这里必须处理符号
     *
     * 「这一行要在屏幕上往下移动 305px」与「列表要往下滚 305px」是**两个相反的操作**：
     * `animateScrollBy(+x)` 会让所有行的 offset **减少** x。把「行的位移」直接喂进去
     * 就会往反方向滚，症状不是崩溃也不是一片空白，而是——每换一句，列表就朝错误方向
     * 走半个屏幕，前几句被一路顶出视口。界面上看起来只是「开头那几句莫名其妙没了」，
     * 所以符号归一律收在这个纯函数里，调用点与单测共用同一套符号。
     *
     * 坐标系与 `LazyListLayoutInfo` / `LazyListItemInfo` 一致：都是相对视口起点的
     * 像素，所以 *同时* 平移视口与 item 时结果不变（见单测）。
     *
     * @param viewportStart 视口起点（`LazyListLayoutInfo.viewportStartOffset`，带内边距时
     *   是负数，如 -63）。
     * @param viewportEnd 视口终点（`viewportEndOffset`，通常就是视口高度）。
     * @param beforeContentPadding 内容区上内边距（`beforeContentPadding`）：歌词区
     *   `compact` 与否取 24dp / 8dp，居中必须在**内容区**里居中，否则「贴着上沿」
     *   其实还压着一段留白。
     * @param afterContentPadding 内容区下内边距。
     * @param itemOffset 这一行当前的主轴起点（`LazyListItemInfo.offset`）。
     * @param itemSize 这一行**实测**的高度（`LazyListItemInfo.size`）。还没被组合出来
     *   时没有值——调用方必须保证传进来的是量到的数字，不能拿「一行大概多高」去猜。
     * @return 需要滚动的像素（正 = 往下滚）。0 表示「已经在位」，调用方不必再动画一次。
     *   视口 / 内容区 / 行高退化（≤ 0）时一律返回 0：这几种输入只会在「还没量出来」
     *   的那一帧出现，那里什么都不能做。
     */
    fun anchorDelta(
        viewportStart: Int,
        viewportEnd: Int,
        beforeContentPadding: Int,
        afterContentPadding: Int,
        itemOffset: Int,
        itemSize: Int,
    ): Int {
        val bandStart = viewportStart + beforeContentPadding
        val bandEnd = viewportEnd - afterContentPadding
        val bandHeight = bandEnd - bandStart
        if (bandHeight <= 0 || itemSize <= 0) return 0
        val desiredTop = if (itemSize >= bandHeight) {
            // 整块放不下：顶边对齐，让开头可见（见类 KDoc 第 2 条不变量）。
            bandStart
        } else {
            bandStart + (bandHeight - itemSize) / 2
        }
        // 行要从 itemOffset 走到 desiredTop ⇒ 滚动量取反：往下滚会让 offset 变小。
        return itemOffset - desiredTop
    }
}
