package com.multisuperplayer.feature.library

/**
 * 目录列表停在哪一行、那一行被滚上去多少像素。
 *
 * 只有两个整数，**不持有** `LazyListState`：那个对象绑在**一次**组合上，
 * 而这里要能跨目录存下来、在另一次组合里重新变成初始值。
 * 字段名故意与 `LazyListState.firstVisibleItemIndex` /
 * `firstVisibleItemScrollOffset` 一一对应——转换只有一处，不该有两套坐标。
 */
data class BrowseScrollPosition(
    val index: Int,
    val offset: Int,
) {
    companion object {
        /** 顶格。没滚过的目录就是这个值。 */
        val TOP = BrowseScrollPosition(index = 0, offset = 0)
    }
}

/**
 * 把记下来的位置收进「这个目录现在有多少条」的范围里。
 *
 * 位置是**上一次**的事实：再回来时这个目录可能已经变了（删了文件、换了排序、
 * 关了隐藏开关），记下的第 40 行可能根本不存在。把越界的下标直接交给
 * `rememberLazyListState` 不会崩，但会落在一片空白上——屏幕上看像
 * 「这个文件夹打不开」，而实际上只是列表比记忆里短了。
 *
 * 两条规则：
 *
 * 1. 下标夹进 `0..itemCount-1`；
 * 2. **下标被改了就把偏移清零**。偏移的语义是「第 N 行上方藏了多少像素」，
 *    属于被换掉的那一行；留着它只会得到「第 N 行上方空出一大块」。
 */
internal fun BrowseScrollPosition.clampTo(itemCount: Int): BrowseScrollPosition {
    if (itemCount <= 0) return BrowseScrollPosition.TOP
    val clamped = index.coerceIn(0, itemCount - 1)
    return BrowseScrollPosition(
        index = clamped,
        offset = if (clamped == index) offset.coerceAtLeast(0) else 0,
    )
}

/**
 * 「每个目录停在哪儿」。键是目录的 `ref`。
 *
 * ## 为什么键是 `ref` 而不是路径
 *
 * `ref` 就是「这一层是怎么进来的」那个不透明标识（见 `BrowserTrail`）：SAF 目录
 * 是文档 uri，文件系统目录是绝对路径。用它当键，同一层不管从哪条路进来都落到
 * 同一条记录上；而按路径拼出来的键在 SAF 那边根本拼不出来——SAF 没有可靠的
 * 父指针，这正是 `BrowserTrail` 存在的理由。
 *
 * ## 为什么要个上限
 *
 * 一路翻下去可以经过几百个目录，而会回看的只有最近那几个。不设上限就是一张
 * 只涨不落的表，活到进程结束。超了按**最久没碰过**淘汰（`LinkedHashMap` 记
 * 插入序，重新写入前先删一次就等于挪到末尾），够用且不用自己写 LRU。
 * 被淘汰的那几层返回时会回到顶部——和「压根没记过」表现一致，不会出错。
 *
 * ## 它不是状态容器
 *
 * 外面没有 `State`/`StateFlow` 包着它，界面写入它**不会**触发重组。
 * 所以 `DirectoryList` 可以每次滚动都写一次，而不必挑「离开组合那一刻」去写
 * （那个时机不可靠，见 `DirectoryList` 的注释）。只在主线程读写。
 */
internal class BrowserScrollMemory(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val positions = LinkedHashMap<String, BrowseScrollPosition>()

    fun remember(ref: String, position: BrowseScrollPosition) {
        positions.remove(ref)
        positions[ref] = position
        while (positions.size > capacity) {
            val eldest = positions.keys.firstOrNull() ?: break
            positions.remove(eldest)
        }
    }

    fun recall(ref: String): BrowseScrollPosition? = positions[ref]

    /** 只给测试看：现在记住了几个目录。 */
    val size: Int get() = positions.size

    private companion object {
        /**
         * 20 层。正常找片子不会比这更深，而一条记录只有两个 `Int`。
         * 不放更宽是因为「翻过几百个目录还能精确回到原位」这件事，
         * 用户感知不到，却要一直占着内存。
         */
        const val DEFAULT_CAPACITY = 20
    }
}
