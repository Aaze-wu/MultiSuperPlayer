package com.multisuperplayer.core.ui.list

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * 拖动中的瞬时状态。
 *
 * 几个对外可见的值分开存而不是合成一个 `data class DragUiState?`：拖动中每一帧
 * 都会改 [offsetY]，而合成一个对象意味着「位移变了」等于「整个拖动状态换了新实例」，
 * 于是读 [from] / [isPicked] 的地方（比如每一行的底色）会跟着一起重组。
 * 分开之后 [offsetY] 只被 `graphicsLayer` 的 lambda 读到，跨行判定也不会
 * 触发一次全列表的重组。
 *
 * ## 拖动改的是**数据顺序**，不是某一行的画法
 *
 * 拖动中每跨过一行，[from] 就跟着走一格，并且通过 [onMove] 让调用方把这一条
 * 挪过去（队列那里还真的会调 `move`）。因此被拎起来的那一行**始终落在
 * 列表可见区里**，`LazyColumn` 不会回收它的格子。
 *
 * 反过来做（数据不动、只用 `graphicsLayer` 把一个浮动副本画上去）看着更简单，
 * 但被拖的那一行一旦滚出可视区，它的组合就被销毁，挂在它身上的手势节点跟着没了——
 * 表现是「拖着拖着突然断了」，而剩下的滑动会交给列表之外的东西
 * （在底部面板里就是**把面板整块划走**）。这就是队列面板之前那个 bug。
 *
 * ## 为什么只由 [start] / [moveFingerBy] / [contentScrolledBy] / [end] / [cancel] 写
 *
 * 这些值描述的是「手指现在在哪儿」，除了手势本身没有任何东西知道答案。
 * 想改它们就通过那几个方法改：位移、手指位置、落点三者是**互相牵连**的
 * （列表自己滚一段之后，手指没动但落点得跟着变），谁都不能单独改一个。
 *
 * ## [fingerY] 只在协程里读
 *
 * 它每一帧都会被自动滚动改写（见 [ReorderDragAutoScroll]）。如果某个 composable
 * 在组合期间读它，就等于「每帧重组一次那个 composable」——所以它标成 `private set`
 * 并且只该被自动滚动那个协程读到。
 */
@Stable
class ReorderDragState {

    /**
     * 被拎起来的那一行**现在**在第几位；null 表示当前没有拖动。
     *
     * 注意它不是「按下时在第几位」（那是 [originIndex]）：跨过一行它就变一次，
     * 因为拖动期间数据是真的在换顺序的。
     */
    var from: Int? by mutableStateOf(null)

    /**
     * 手指相对**这一格**移动了多少像素（纵向）。
     *
     * 每跨过一行就减掉一整行（见 [snap]），所以它总在半行以内；到顶/到底之后
     * 还会被夹住。它只被 `graphicsLayer` 的 lambda 读到，改它只重绘那一层。
     */
    var offsetY: Float by mutableFloatStateOf(0f)

    /**
     * 按下时那一行在第几位。
     *
     * 松手时拿它和 [from] 比：一样就什么都不做。不平白多走一次 `move` 是有代价的——
     * 队列那边会让当前播放项重新缓冲一下，播放列表那边是一次没意义的写库。
     */
    var originIndex: Int by mutableIntStateOf(-1)
        private set

    /**
     * 手指在列表**视口**里的纵坐标（视口顶部是 0，不是屏幕坐标）。
     *
     * 自动滚动只看这一个值：贴不贴边是相对列表可见区说的，而列表可能只占屏幕
     * 中间那一条（比如播放队列是个底部面板）。
     */
    var fingerY: Float by mutableFloatStateOf(0f)
        private set

    /**
     * 按下时那一行有多高（像素），整个手势里不再重量。
     *
     * 量一次就够的前提是「手势期间行高不变」：重排由调用方保证每条数据都是同样的
     * 高度（队列那一行是固定 56dp，播放列表的条目字号固定），量出来的值就是这个
     * 手势里的行高单位。
     */
    private var rowHeightPx: Float = 0f

    /** 数据条数（不是 `LazyColumn` 的 item 数，上面可能插着横幅）。 */
    private var itemCount: Int = 0

    /**
     * 拖动中每跨过一行调一次；参数是**移动前/后**的数据下标。
     *
     * 由 [rememberReorderPreview] 装上，调用方不用管。挂在这里而不是当成参数传进来，
     * 是因为触发它的有两条路——手指（[moveFingerBy]）和自动滚动
     * （[contentScrolledBy]）——只有状态自己知道哪条路走了。
     */
    internal var onMove: ((from: Int, to: Int) -> Unit)? = null

    /** 松手且真的换了位置时调一次；参数是按下时的下标和松手时那一行在第几位。 */
    internal var onDrop: ((origin: Int, final: Int) -> Unit)? = null

    /** 手势被系统收走（来电、切后台、节点被销毁）时调一次。 */
    internal var onCancel: (() -> Unit)? = null

    /** 这一行是不是被拎起来的那一行。 */
    fun isPicked(index: Int): Boolean = from == index

    /**
     * 手势开始：[index] 那一行被拎了起来。
     *
     * @param fingerY 手指在视口里的纵坐标，用来判断有没有贴边。
     * @param rowHeightPx 这一行真实的高度，跨越换算的行高单位。
     * @param itemCount 数据条数，落点要被夹在它之内。
     */
    fun start(index: Int, fingerY: Float, rowHeightPx: Float, itemCount: Int) {
        from = index
        originIndex = index
        offsetY = 0f
        this.fingerY = fingerY
        this.rowHeightPx = rowHeightPx
        this.itemCount = itemCount
    }

    /** 手指移动了 [deltaY]：位移和手指位置一起走，看看要不要换位。 */
    fun moveFingerBy(deltaY: Float) {
        offsetY += deltaY
        fingerY += deltaY
        snap()
    }

    /**
     * 列表自己滚了 [deltaPx]（自动滚动）。
     *
     * 只有被拎起来的那一行要跟着走：手指在屏幕上并没有动，所以 [fingerY] 不变。
     * 这一步不能省——列表滚了而那一行不跟着动的话，它会慢慢从手指底下溜走，
     * 而落点又是按位移算的，于是「手指还按着不动，落点却在一格一格地变」。
     */
    fun contentScrolledBy(deltaPx: Float) {
        offsetY += deltaPx
        snap()
    }

    /**
     * 位移够不够换一格，够就换，并把「换过的部分」从位移里扣掉。
     *
     * ## 为什么要把位移扣掉
     *
     * 数据换了顺序之后，被拎起来的那一行**自己**就往前走了一整行，而它的绘制位置
     * 变成了「新格子 + [offsetY]」。如果位移不减掉这一整行，两边的位移会叠加：
     * 跨过一行的瞬间整个人往前跳两行。扣掉之后绘制位置连续，手感是「跟手」。
     *
     * 一次事件可能跨过好几行（帧丢得厉害，或者自动滚动一帧滚了很多），所以用
     * `rowDelta` 算出跨了几行而不是写死 `±1`，然后把每一格都按顺序报给调用方——
     * 预览是一格一格挪过去的，跨两格时跳过中间那一格会让「谁跟谁换了位」对不上。
     *
     * 到顶/到底之后手指还在往外走：残余位移被夹在半行以内，让这一行停在边上等
     * 手指回来。不夹的话它会一直飘到列表外面，看起来像被拖走了。
     */
    private fun snap() {
        val current = from ?: return
        var moved = ReorderDragRules.rowDelta(offsetY, rowHeightPx)
        val room = itemCount - 1 - current
        if (moved > room) moved = room
        if (moved < -current) moved = -current
        if (moved != 0) {
            from = current + moved
            offsetY -= moved * rowHeightPx
            // 一格一格地报：预览是逐格挪过去的。
            val step = if (moved > 0) 1 else -1
            repeat(abs(moved)) {
                val position = if (step > 0) current + it else current - it
                onMove?.invoke(position, position + step)
            }
        }
        val halfRow = rowHeightPx / 2f
        if (rowHeightPx > 0f) offsetY = offsetY.coerceIn(-halfRow, halfRow)
    }

    /**
     * 手势正常结束（手指抬起）：换过就提交一次，然后回到没有拖动。
     *
     * 提交用的是 [originIndex] 和 [from]：整个拖动只移动了被拎起来的那一条，
     * 所以「从起点一次移到终点」和拖动中一格一格挪过去的结果完全一样。
     */
    internal fun end() {
        val origin = originIndex
        val dropped = from
        clear()
        if (dropped != null && !ReorderDragRules.isNoOp(origin, dropped)) onDrop?.invoke(origin, dropped)
    }

    /** 手势被收走：什么都不提交，但要通知调用方把预览收掉。 */
    internal fun cancel() {
        clear()
        onCancel?.invoke()
    }

    /** 回到「没有拖动」的状态。 */
    fun clear() {
        from = null
        originIndex = -1
        offsetY = 0f
        rowHeightPx = 0f
        itemCount = 0
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
 * 手势从哪一下开始。
 *
 * 两种都有用武之地，而且**必须在调用方显式选**：队列面板的把手是「按下就拖」
 * （把手上没有别的动作，多按 500ms 只是白等），列表里的整行是「长按才拖」
 * （整行本身要能点开、能滚动，按下就拖会让单击变得几乎点不准）。
 */
enum class ReorderDragTrigger {

    /** 手指越过触摸阈值即开始拖。 */
    Immediate,

    /** 按住约 500ms 之后才开始拖。 */
    LongPress,
}

/**
 * 把整个列表变成拖拽源：手势挂在**列表自己**身上，按下时再去问「这一下落在哪一行」。
 *
 * ## 为什么手势不能挂在每一行上
 *
 * 挂在行上更直观，但有个致命问题：被拖的那一行被列表回收（滚出可视区之后
 * `LazyColumn` 会销毁它的组合）时，挂在它身上的 `pointerInput` 节点跟着没了，
 * 手势被判为取消。表现是「拖着拖着突然断了」，而手指还在往下滑——这次滑动
 * 就交给了列表之外的东西（在底部面板里就是**把面板整块划走**）。
 *
 * 挂在列表容器上就没这个问题：容器永远在，手势永远活得下来。代价是「按下的是
 * 哪一行」要自己算（按 y 在 [LazyListState.layoutInfo] 里找）。配合
 * [rememberReorderPreview] 让拖动真的改数据顺序，被拖的那一行也不会被回收。
 *
 * ## 为什么在 [PointerEventPass.Initial] 里消费
 *
 * 列表自己的滚动（`LazyColumn` 内部那个 `scrollable`）是我们这个节点的**子级**。
 * 指针事件在 Main 阶段是「先子后父」，父级的处理器总是后知后觉——等我们想接手时
 * 列表已经开始滚了。Initial 阶段是「先父后子」，在这里消费才抢得过滚动。
 *
 * 抢的时机也很讲究：**只在自己真的要拖的时候抢**。按下的位置不在把手上、
 * 或者长按还没成立，就一个事件都不消费，列表该怎么滚还怎么滚。
 *
 * ## 调用方要接的两根线
 *
 * 手势自己只改状态；「拖动中列表显示什么顺序」和「松手时提交给谁」由
 * [rememberReorderPreview] 接到状态上。两者必须一起用，否则拖动会改不动数据。
 *
 * @param itemCount 数据条数（不是 `LazyColumn` 的 item 数）。
 * @param dataIndexOf `LazyColumn` 的下标 → 数据下标；列表上面插着横幅/提示行时
 *   两者不一样。返回 null 表示这一格不是数据（按在它上面不拖）。
 * @param canStart 按下这一格数据的**这一处**能不能起拖。队列面板用它把拖拽限制在
 *   把手上（整行都能拖的话，「点一下切歌」会变得几乎点不准）。
 */
@Composable
fun Modifier.reorderDragSource(
    state: ReorderDragState,
    listState: LazyListState,
    itemCount: Int,
    trigger: ReorderDragTrigger,
    dataIndexOf: (lazyIndex: Int) -> Int?,
    canStart: (dataIndex: Int, position: Offset, itemSize: IntSize) -> Boolean = { _, _, _ -> true },
): Modifier {
    // 这两个 lambda 每次重组都是新实例，而它们会被捕获进 `pointerInput` 的块里；
    // 直接捕获旧实例的话，「随机播放打开之后把手还能拖」这类问题会一直存在。
    val currentDataIndexOf by rememberUpdatedState(dataIndexOf)
    val currentCanStart by rememberUpdatedState(canStart)
    return this.pointerInput(itemCount, trigger) {
        detectReorderDragSource(
            trigger = trigger,
            pressAt = { position ->
                val item = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }
                val dataIndex = item?.let { currentDataIndexOf(it.index) }
                // 宽度得从**容器**上拿：`LazyListState` 只报每一行的高度（`size`），
                // 而这里的行都是撑满宽度的，所以容器的宽度就是行的宽度。
                val itemSize = item?.let { IntSize(size.width, it.size) }
                if (item == null || dataIndex == null || itemSize == null ||
                    !currentCanStart(dataIndex, position, itemSize)
                ) {
                    null
                } else {
                    ReorderDragPress(
                        dataIndex = dataIndex,
                        fingerY = position.y,
                        rowHeightPx = item.size.toFloat(),
                    )
                }
            },
            onStart = { press ->
                state.start(
                    index = press.dataIndex,
                    fingerY = press.fingerY,
                    rowHeightPx = press.rowHeightPx,
                    itemCount = itemCount,
                )
            },
            onDragBy = state::moveFingerBy,
            onEnd = state::end,
            onCancel = state::cancel,
        )
    }
}

/**
 * 把这一行画成「被拎起来」：抬到别的行上面，并跟着手指走。
 *
 * 手势不在这里（见 [Modifier.reorderDragSource]），这里只负责这一行的样子。
 * 位移写在 `graphicsLayer` 的 lambda 里，每帧改它只重绘这一层、不重组整行。
 */
fun Modifier.reorderDragItem(state: ReorderDragState, index: Int): Modifier = this
    .zIndex(if (state.isPicked(index)) 1f else 0f)
    .graphicsLayer {
        translationY = if (state.isPicked(index)) state.offsetY else 0f
    }

/**
 * 拖动期间列表显示的顺序，以及「松手时提交给谁」。
 *
 * ## 为什么要有一份本地顺序
 *
 * 拖动中每一帧都可能改「谁在谁前面」，但**真实数据不能跟着动**：队列那一边动一下
 * 就是一次播放器调用，播放列表那一边动一下就是一次写库。所以拖动期间渲染这个
 * 副本，松手时（以及拖动中的每一次跨行）把「谁和谁换了位」告诉调用方，
 * 由调用方自己决定怎么落到真实数据上。
 *
 * ## 为什么松手之后不立刻丢掉副本
 *
 * 上层的顺序不是立刻回来的：队列要等播放器回调，播放列表要写库加重读。松手就丢掉
 * 的话，列表会先闪回旧顺序再跳到新顺序。所以副本留到上层给出新顺序
 * （[items] 变了内容）为止；拖动中收到的数据更新不算（那时用户看到的
 * 就是他手里这一份）。
 *
 * @param onCommit 松手时调一次；参数是**按下时**的下标和**松手时**的下标。
 */
@Composable
fun <T> rememberReorderPreview(
    items: List<T>,
    state: ReorderDragState,
    onCommit: (from: Int, to: Int) -> Unit,
): List<T> {
    var preview: List<T>? by remember { mutableStateOf(null) }
    val currentItems by rememberUpdatedState(items)
    val currentCommit by rememberUpdatedState(onCommit)

    SideEffect {
        state.onMove = { from, to ->
            val base = preview ?: currentItems
            // 逐格跨行时这里会被连着调好几次，每次都得基于**上一次**的结果算，
            // 所以读的是 `preview` 而不是 `items`。
            preview = ReorderDragRules.move(base, from, to)
        }
        state.onDrop = { from, to -> if (!ReorderDragRules.isNoOp(from, to)) currentCommit(from, to) }
        state.onCancel = { preview = null }
    }

    // 新一轮拖动开始：从当前顺序重新来过（上一轮如果没人接受，副本不能一直粘着）。
    LaunchedEffect(state.from != null) {
        if (state.from != null) preview = null
    }
    // 上层给出了新顺序：副本的使命结束。
    LaunchedEffect(items) {
        if (state.from == null) preview = null
    }

    return preview ?: items
}

/** 按下时算出来的落点信息（见 [detectReorderDragSource]）。 */
private class ReorderDragPress(
    val dataIndex: Int,
    val fingerY: Float,
    val rowHeightPx: Float,
)

/**
 * 拖拽手势本体。
 *
 * [pressAt] 在按下时被问一次「这一下归不归我管」，返回 null 就当场放手——
 * 一个事件都不消费，列表该怎么滚还怎么滚。
 */
private suspend fun PointerInputScope.detectReorderDragSource(
    trigger: ReorderDragTrigger,
    pressAt: (Offset) -> ReorderDragPress?,
    onStart: (ReorderDragPress) -> Unit,
    onDragBy: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val press = pressAt(down.position) ?: return@awaitEachGesture
        // 位移从哪儿开始算：长按那一路从**长按成立的位置**起算——按住那几百毫秒里
        // 手指可能已经游走了几像素，那段不该在拖起来的一瞬间弹出来；即时那一路
        // 从**按下的位置**起算，过阈值那一段同样是手在动，算进去才跟手。
        val startY = when (trigger) {
            ReorderDragTrigger.LongPress -> {
                val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                held.position.y
            }

            ReorderDragTrigger.Immediate -> {
                if (!awaitVerticalTouchSlop(down.id, down.position)) return@awaitEachGesture
                down.position.y
            }
        }
        onStart(press)
        // 位移用「相邻两次事件的绝对位置之差」算，**不用** `positionChange()`：
        // 后者取的是 `MotionEvent` 的**历史采样点**，一次事件里没有历史点
        // （手指采样率低、被系统合并成一个事件，或事件由 `input motionevent`
        // 这类工具逐条注入）它就恒为 0。表现正好是「长按成了、行也高亮了，
        // 就是拖不动」——而那时位置其实一直在变。
        var lastY = startY
        try {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial)
                    .changes
                    .firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // 消费掉：列表的滚动、行自己的点击都要知道这一下不归它们。
                change.consume()
                val y = change.position.y
                val dy = y - lastY
                lastY = y
                onDragBy(dy)
            }
            onEnd()
        } catch (cancellation: CancellationException) {
            // 被系统收走（来电、切后台、节点被销毁）：这一下不算数，
            // 但那一行不能留在半空中。
            onCancel()
            throw cancellation
        }
    }
}

/**
 * 纵向拖过触摸阈值了没有。
 *
 * 和 `awaitTouchSlopOrCancellation` 的区别只有一点：在 **Initial** 阶段判断。
 * 列表的滚动是我们这个节点的子级，Main 阶段它比我们先看到事件——在那里等阈值
 * 就是在等它先把这次滑动拿走。
 *
 * 只算纵向：横向滑动（比如切页、划走面板）不该把行拎起来。
 * 被别处消费掉（比如面板的关闭手势已经成立）也算失败，让给对面。
 */
private suspend fun AwaitPointerEventScope.awaitVerticalTouchSlop(
    pointerId: PointerId,
    downPosition: Offset,
): Boolean {
    val slop = viewConfiguration.touchSlop
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial)
            .changes
            .firstOrNull { it.id == pointerId }
            ?: return false
        if (!change.pressed || change.isConsumed) return false
        if (abs(change.position.y - downPosition.y) > slop) return true
    }
}

/**
 * 拖动中这一行的底色；null 表示「不参与拖动，用默认底色」。
 *
 * 被拎起来的那一行用**不透明**的容器色：它要盖住下面的行，半透明的底色会
 * 透出下面那行的文字，看起来像两行叠在一起。
 *
 * 没有「落点高亮」了：拖动期间被拎起来的那一行**就在落点上**（数据真的换了顺序），
 * 再画一个落点提示等于在同一格上画两层。
 *
 * 底色必须画在**行自己的 Surface** 上（见 `MediaEntryRow` 的 `containerColor`）：
 * 在外面再包一个带背景的 `Box` 会被行自己的不透明底色盖住，
 * 表现就是「拖到哪儿都看不出自己在拖」。
 */
@Composable
fun reorderItemColor(state: ReorderDragState, index: Int): Color? =
    if (state.isPicked(index)) MaterialTheme.colorScheme.surfaceVariant else null

/** 自动滚动的默认手感。 */
object ReorderDragAutoScrollDefaults {

    /** 手指进入离边缘这么近以内就开始自动滚。 */
    val EDGE = 72.dp

    /**
     * 最贴边时的滚动速度。
     *
     * 大约「一秒滚一屏」：再快就没法在滚过头之前把落点对准，再慢则长列表要
     * 一直贴着边等。
     */
    val MAX_SPEED = 900.dp
}

/**
 * 拖到列表边缘时自动滚动。
 *
 * 用法：挂在**列表容器**上（包着 `LazyColumn` 的那个 `Box`），和 `LazyColumn`
 * 用同一个 [listState]、同一个拖动 [state]。放在容器而不是每一行里，是因为
 * 「滚了没有」只有容器知道。
 *
 * ## 为什么用 [scrollBy] 的返回值去喂拖动状态
 *
 * 自动滚动和手指位移是**两条独立的输入**，都会改被拎起来那一行的位置。区别是
 * 「滚了多少」有一个列表自己才知道的上限（内容到头了就滚不动），而这时**不能**
 * 让那一行继续动——否则它会从手指底下溜走，落点却还在变。所以这里拿
 * `scrollBy` 的返回值（真正滚掉的像素）去调 [ReorderDragState.contentScrolledBy]：
 * 滚不动时它返回 0，那一行也就不动。
 *
 * ## 速度要乘帧间隔，不能按帧给固定像素
 *
 * 同一段代码在 60Hz 和 120Hz 上跑，按帧给固定像素的话后者会滚快一倍。
 *
 * ## 没有拖动时这里一个帧回调都不占
 *
 * [ReorderDragState.from] 一变它就重组、`LaunchedEffect` 重开。要是改成常驻
 * 循环空转（用 `withFrameNanos` 一直转），那这个列表只要在屏幕上就会一直吃满
 * 垂直同步。
 */
@Composable
fun ReorderDragAutoScroll(
    state: ReorderDragState,
    listState: LazyListState,
    edge: Dp = ReorderDragAutoScrollDefaults.EDGE,
    maxSpeed: Dp = ReorderDragAutoScrollDefaults.MAX_SPEED,
) {
    if (state.from == null) return
    val density = LocalDensity.current
    val edgePx = with(density) { edge.toPx() }
    val maxPxPerSecond = with(density) { maxSpeed.toPx() }
    LaunchedEffect(listState, edgePx, maxPxPerSecond) {
        var previousFrameNanos = 0L
        while (true) {
            val frameNanos = withFrameNanos { it }
            val seconds = if (previousFrameNanos == 0L) {
                0f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceAtMost(MAX_FRAME_SECONDS)
            }
            previousFrameNanos = frameNanos
            if (seconds <= 0f) continue
            val speed = ReorderDragRules.autoScrollPxPerSecond(
                fingerY = state.fingerY,
                viewportHeightPx = listState.layoutInfo.viewportSize.height.toFloat(),
                edgePx = edgePx,
                maxPxPerSecond = maxPxPerSecond,
            )
            if (speed == 0f) continue
            val scrolled = listState.scrollBy(speed * seconds)
            if (scrolled != 0f) state.contentScrolledBy(scrolled)
        }
    }
}

/**
 * 两帧之间最多按多少秒算。
 *
 * 掉帧（或者应用被切到后台再切回来）时两帧可能隔几百毫秒，照实乘上去就是
 * 「抖一下滚过去半屏」。夹到一帧多一点（16.7ms 的三倍）就只是慢了一拍，
 * 用户不会看见列表突然跳。
 */
private const val MAX_FRAME_SECONDS = 0.05f
