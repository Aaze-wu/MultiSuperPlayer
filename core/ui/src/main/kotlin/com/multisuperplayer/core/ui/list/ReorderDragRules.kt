package com.multisuperplayer.core.ui.list

/**
 * 拖动排序的落点换算。
 *
 * ## 为什么是一组纯函数
 *
 * 「手指移动了多少像素 = 应该插到第几位」是一道**可以拿例子钉住的算术**，而它的
 * 边界（拖到列表外面、行高不是整数、只拖了六成行高就松手）恰恰是手感问题：
 * 留在 composable 里就只能靠真机试出来「好像有点飘」，改一次要重新装一次。
 * 抽出来之后每个边界都能单测（见 `ReorderDragRulesTest`），界面那边只剩
 * 「把手指的位移喂进来、把手势结束时的落点交给调用方的 `move`」。
 *
 * 放在 `core:ui` 而不是某个 feature 里，是因为**好几处列表都用它**：播放队列
 * （`feature:player`，按把手拖动）、播放列表详情里的条目、以及播放列表**清单**
 * （后两处都在 `feature:library`，长按整行拖动，手势封装见
 * `Modifier.reorderDragSource`，落点由 `ReorderDragState` 使用）。
 * 各抄一份的话，手感修一处、别处不会跟着变，而那种差异没人会去复现。
 *
 * ## 全部按「跨过几行」算，不按像素比
 *
 * 用行高当单位意味着换一档字号（或者换一个屏幕密度）不需要重新调参数：
 * 「往上拖过一行就换一格」在任何设备上都成立，而「拖 56 像素换一格」只在
 * 这个行高下成立。
 */
object ReorderDragRules {

    /**
     * 位移跨过了几行。正数往下、负数往上。
     *
     * **半个行高才算换位**：少于半个行高时手指只是抖了一下，此时换位会让列表
     * 在轻微滑动中来回抖（用户想点的行会从他手指底下跑掉）。正好半个行高算换位
     * （四舍五入到远离零的那一侧），负数侧用同样的规则——写成
     * `(rows + 0.5f).toInt()` 的话 `-0.5` 会算成 `0`，于是「往上和往下不对称」，
     * 只有半行高以内的手感差异，很难在真机上发现。
     *
     * 行高给成 0、负数或者 NaN 时返回 0：这些值只可能来自「还没测量出来」，
     * 而此时任何非零结果都会让列表跳一下。
     */
    fun rowDelta(dragOffsetY: Float, rowHeightPx: Float): Int {
        if (!rowHeightPx.isFinite() || rowHeightPx <= 0f || !dragOffsetY.isFinite()) return 0
        val rows = dragOffsetY / rowHeightPx
        return if (rows >= 0f) {
            (rows + 0.5f).toInt()
        } else {
            -(-rows + 0.5f).toInt()
        }
    }

    /**
     * 从 [from] 出发拖了 [dragOffsetY] 之后，应该落到哪一位。
     *
     * 结果**夹在列表里**（`0..size-1`）：拖到列表外面时用户想要的是「放到最后」，
     * 而不是「什么都没发生」。往反方向拖过头同理（放到最前）。
     */
    fun targetIndex(from: Int, dragOffsetY: Float, rowHeightPx: Float, size: Int): Int {
        if (size <= 0) return 0
        val raw = from + rowDelta(dragOffsetY, rowHeightPx)
        return raw.coerceIn(0, size - 1)
    }

    /**
     * 这一拖到底改了没有。
     *
     * 单独列出来是因为调用方在拖动结束时要判断「要不要真的改顺序」：落点和出发点
     * 一样时**什么都不要做**，否则会白跑一次落盘（队列那边还会让当前播放项
     * 重新缓冲一下——一次没意义的抖动）。
     */
    fun isNoOp(from: Int, to: Int): Boolean = from == to

    /**
     * 把 [from] 那一条拎出来插到 [to]（两者都是**当前**列表里的下标）。
     *
     * 抽成纯函数是为了让「拖动期间那份预览顺序」能被单测钉住：它和真实数据是
     * 两份，掉了哪一条、重复了哪一条都不会报错，只会让界面显示一个不存在的顺序，
     * 而那种错在真机上看起来像「松手之后顺序自己变了」。
     *
     * 下标越界或者 `from == to` 时**原样返回**（不复制）：拖动中的跨行是逐格报的，
     * 中间难免出现「这一步没意义」，此时返回同一个列表实例还能顺带省掉一次
     * 无谓的重组。
     */
    fun <T> move(items: List<T>, from: Int, to: Int): List<T> {
        if (from == to) return items
        if (from !in items.indices || to !in items.indices) return items
        return items.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * 手指贴在列表边缘时，列表该用多快的速度自动滚（像素/秒）。
     *
     * 正数往后滚（内容往上走，露出下面的条目）、负数往前滚、`0f` 不滚。
     * 只有落进顶部/底部 [edgePx] 那条带子里才返回非零值，并且**越靠边越快**
     * （线性加速到 [maxPxPerSecond]）：全程匀速会让「只想滚一点点」变成做不到
     * 的事，而只有「最快那一档」又会让长列表一路冲过头。
     *
     * 带子比半个视口还高时按半个视口夹住。不夹的话上下两条带子会重叠，同一个
     * 位置既算贴顶又算贴底，结果取决于先判断哪一边——那在真机上表现为
     * 「拖着拖着列表突然往反方向滚」。
     *
     * 手指跑到视口外面（拖到了列表之外）时差值会超过带宽，靠 `coerceAtMost`
     * 收到满速，而不是继续无限加速。
     *
     * 返回**速度**而不是位移，是因为位移要乘以帧间隔：不同设备的帧率不一样，
     * 按帧给固定像素会让 120Hz 的机器滚得比 60Hz 快一倍。
     */
    fun autoScrollPxPerSecond(
        fingerY: Float,
        viewportHeightPx: Float,
        edgePx: Float,
        maxPxPerSecond: Float,
    ): Float {
        if (!fingerY.isFinite() || !viewportHeightPx.isFinite() || !edgePx.isFinite() ||
            !maxPxPerSecond.isFinite()
        ) {
            return 0f
        }
        if (viewportHeightPx <= 0f || edgePx <= 0f || maxPxPerSecond <= 0f) return 0f
        val zone = if (edgePx > viewportHeightPx / 2f) viewportHeightPx / 2f else edgePx
        val intoBottom = fingerY - (viewportHeightPx - zone)
        if (intoBottom > 0f) return maxPxPerSecond * (intoBottom / zone).coerceAtMost(1f)
        val intoTop = zone - fingerY
        if (intoTop > 0f) return -maxPxPerSecond * (intoTop / zone).coerceAtMost(1f)
        return 0f
    }
}
