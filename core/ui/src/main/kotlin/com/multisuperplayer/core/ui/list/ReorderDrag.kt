package com.multisuperplayer.core.ui.list

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * 正在拖动的三个瞬时状态。
 *
 * 三个值分开存而不是合成一个 `data class DragUiState?`：拖动中每一帧都会改
 * [offsetY]，而合成一个对象意味着「位移变了」等于「整个拖动状态换了新实例」，
 * 于是读 [from] / [target] 的地方（比如每一行的底色）会跟着一起重组。
 * 分开之后 [offsetY] 只被 `graphicsLayer` 的 lambda 读到，落点预判也不会
 * 触发一次全列表的重组。
 *
 * ## 为什么只由 [reorderDrag] 写
 *
 * 这三个值描述的是「手指现在在哪儿」，除了手势本身没有任何东西知道答案。
 * 属性是 public 的（跨模块要用），但请只在手势回调里改它们。
 */
@Stable
class ReorderDragState {

    /** 被拎起来的那一行的下标；null 表示当前没有拖动。 */
    var from: Int? by mutableStateOf(null)

    /** 手指相对按下位置移动了多少像素（纵向）。 */
    var offsetY: Float by mutableFloatStateOf(0f)

    /** 松手会落到的位置；没有拖动时是 -1。 */
    var target: Int by mutableIntStateOf(-1)

    /** 这一行是不是被拎起来的那一行。 */
    fun isPicked(index: Int): Boolean = from == index

    /**
     * 这一行是不是当前的落点。
     *
     * 没有拖动时一律为 false：那时 [target] 是 -1，但**真正要看的是 [from]**——
     * 手松开之后 [target] 还留着上一次的值，加了 `from != null` 这个条件，
     * 就不会在松手后留下一条永远高亮着的行。
     */
    fun isTarget(index: Int): Boolean = from != null && target == index

    /** 回到「没有拖动」的状态。 */
    fun clear() {
        from = null
        target = -1
        offsetY = 0f
    }
}

/**
 * 建一个拖动状态。
 *
 * 刻意**没有** `rememberSaveable` 版本：转屏或进程重建时手势必然已经中断
 * （手指要么抬起来了，要么这次交互已经结束），把「正在拖第几行」存下来只会在
 * 重建后画出一个没有手指的幽灵行。
 */
@Composable
fun rememberReorderDragState(): ReorderDragState = remember { ReorderDragState() }

/**
 * 长按整行拖动重排。
 *
 * ## 为什么抽成公共 modifier
 *
 * 播放列表页有**两处**要用它：列表页的「播放列表清单」和详情页的「列表内的条目」。
 * 各写一份的代价不是多几十行，而是以后改手感（长按要多久、落点要提前几格）
 * 只改到其中一处，两个页面拖着不一样，而那种差异没人会去复现。
 *
 * ## 行高是量出来的，不是写死的
 *
 * 落点换算需要「一行有多高」（见 [ReorderDragRules]），而这个高度由每一行自己的
 * 内容（标题、副标题、尾部按钮）决定。写死一个常数迟早会和真实高度差几个像素——
 * 差一点的表现不是报错，而是「还没拖过半行就换位了」。所以按下时从
 * [LazyListState.layoutInfo] 里读**这一行真实的 size**，而且按 [itemKey] 找
 * 而不是按下标找（列表上面可能插着横幅和提示行，下标对不上）。
 *
 * @param itemKey 这一行的 `LazyColumn` key，必须和 `items(key = ...)` 用的一致。
 * @param itemCount 数据条数（不是 `LazyColumn` 的 item 数）。
 * @param onMove 松手且真的换了位置时调用**一次**；参数是数据下标。
 */
fun Modifier.reorderDrag(
    state: ReorderDragState,
    index: Int,
    itemKey: Any,
    listState: LazyListState,
    itemCount: Int,
    onMove: (from: Int, to: Int) -> Unit,
): Modifier = this
    .zIndex(if (state.isPicked(index)) 1f else 0f)
    .graphicsLayer {
        // 只有被拎起来的那一行跟着手指走。写在 lambda 里，位移变化只会
        // 让这一层重绘，不会重组整行。
        translationY = if (state.isPicked(index)) state.offsetY else 0f
    }
    // 键里带 index：列表换了顺序之后每个行的 index 都变了，而这里捕获的
    // index 是「按下时那一行在第几格」，不重装就会用上一次的位置去算落点。
    // 带 index 做键会让它自动重装。
    .pointerInput(index, itemCount) {
        // 每次拖动开始时量一次就够了：手势期间数据不会被改
        // （重排发生在松手之后），量出来的值就是这个手势里的行高。
        var rowHeightPx = 0f
        detectDragGesturesAfterLongPress(
            onDragStart = {
                rowHeightPx = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == itemKey }
                    ?.size
                    ?.toFloat()
                    ?: 0f
                state.from = index
                state.target = index
                state.offsetY = 0f
            },
            onDragEnd = {
                val from = state.from
                val target = state.target
                state.clear()
                // 只有真正跨过了一行才落盘：没动的那一下也要写一次整条列表的话，
                // 列表会被无谓地重写，还会让 LazyColumn 收到一次内容一样的新数据
                // 而闪一下。
                if (from != null && !ReorderDragRules.isNoOp(from, target)) onMove(from, target)
            },
            onDragCancel = { state.clear() },
        ) { change, dragAmount ->
            change.consume()
            state.offsetY += dragAmount.y
            state.target = ReorderDragRules.targetIndex(
                from = index,
                dragOffsetY = state.offsetY,
                rowHeightPx = rowHeightPx,
                size = itemCount,
            )
        }
    }

/**
 * 拖动中这一行的底色；null 表示「不参与拖动，用默认底色」。
 *
 * 被拎起来的那一行用**不透明**的容器色：它要盖住下面的行，半透明的底色会
 * 透出下面那行的文字，看起来像两行叠在一起。落点用一层浅色就够了——
 * 它的作用只是提前告诉用户「松手会落在这里」。
 *
 * 底色必须画在**行自己的 Surface** 上（见 `MediaEntryRow` 的 `containerColor`）：
 * 在外面再包一个带背景的 `Box` 会被行自己的不透明底色盖住，
 * 表现就是「拖到哪儿都看不出会落在哪儿」。
 */
@Composable
fun reorderItemColor(state: ReorderDragState, index: Int): Color? {
    val colors = MaterialTheme.colorScheme
    return when {
        state.isPicked(index) -> colors.surfaceVariant
        state.isTarget(index) -> colors.primary.copy(alpha = 0.14f)
        else -> null
    }
}
