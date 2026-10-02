package com.multisuperplayer.feature.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 播放画面的手势层。
 *
 * 判定规则全部来自 [PlayerGestures]（纯函数、有单测），这里只负责「把指针事件接上」。
 * 这样分的理由：判定规则里那些边界（中线归哪边、平局算哪个方向、拖满一屏变多少、
 * 时长未知时怎么办）只能在单元测试里钉死，靠手试是试不出来的。
 *
 * ## 支持的手势
 *
 * - 竖直拖动：左半边亮度、右半边音量，一边拖一边下给系统。
 * - 水平拖动：进度。拖动中只显示「会跳到哪儿」，**松手才真正跳**。
 * - 长按：按住期间用加速倍速播放，松手立刻恢复。
 * - 单击 / 双击（左右各快退快进 10 秒）——在第二个 `pointerInput` 里。
 *
 * ## 为什么竖直和水平必须写在**同一个**检测器里
 *
 * 它们都是「拖动」，都会在越过 slop 之后 `consume()` 掉事件。写成两个并排的
 * `pointerInput` 时，一次斜着划的手势会同时被两个检测器判定成立：于是**既改了
 * 音量又跳了进度**，各改多少还取决于两个检测器收到事件的先后顺序——表现为
 * 「有时候音量自己变了」，几乎无法复现。所以这里自己读事件流，在越过 slop 的
 * 那一刻**一次性锁定**方向（[PlayerGestures.axisFor]），此后整条手势只认那个方向。
 *
 * ## 谁抢谁
 *
 * 这个修饰符加在画面那一层（控制层的下面）。Compose 命中测试到控制条上的按钮
 * 之后不会再往下找画面，所以点在按钮上不会顺手改音量；反过来，手指按在画面上
 * 拖动时，控制条上的 `Slider` 也收不到事件。
 *
 * @param positionMs 拖动开始那一刻的播放位置。**每次拖动都重读**，见下面的注释。
 * @param durationMs 总时长。≤ 0（还没探测出来）时水平拖动什么也不做：跳到一个
 *   猜出来的位置比「没反应」更糟。
 * @param onTap null = 这一层不处理单击。竖屏下不需要（控制条本来就在画面外面、
 *   一直可见），传 null 可以让轻点完全不产生任何效果。
 * @param onSeekPreview 水平拖动的中间结果，用来显示「会跳到 00:52」。
 * @param onSeekCommit 松手时真正跳转。拖动中**不**跳是有意的：一边拖一边跳会让
 *   画面不停地闪，而且内核每次 seek 都是有代价的。
 * @param onLevelChange 竖直拖动的中间结果，用来画提示泡。
 *   松手**不**回调 null：提示泡应该在手指抬起之后再多留一会儿，
 *   「什么时候消失」是计时器的事，写在这里会让逻辑分叉成两处。
 * @param onSpeedBoost 长按加速的开始/结束（true = 按下，false = 松手）。
 */
@Composable
internal fun Modifier.playerGestures(
    enabled: Boolean,
    controller: PlayerWindowController?,
    positionMs: Long,
    durationMs: Long,
    onTap: (() -> Unit)?,
    onDoubleTap: (PlayerGestures.Side) -> Unit,
    onSeekPreview: (PlayerSeekHint) -> Unit,
    onSeekCommit: (Long) -> Unit,
    onLevelChange: (PlayerLevelHint) -> Unit,
    onSpeedBoost: (Boolean) -> Unit,
): Modifier {
    // 拖动**开始那一刻**的位置/时长必须现读。
    //
    // 直接把参数捕进 pointerInput 的闭包里是错的：那个 lambda 只在 key 变化时
    // 重启协程，而位置每 200 毫秒就变一次——闭包里留下的是**第一次**组合时的
    // 旧位置，症状是「第二次拖动从上一部片子的位置开始算」，而且不会有任何报错。
    val latestPosition by rememberUpdatedState(positionMs)
    val latestDuration by rememberUpdatedState(durationMs)

    // 回调同理：`pointerInput` 的协程只在 key 变化时重启，而每次重组都会生成新的
    // lambda 对象。直接捕参数的话，协程里留着的是**第一次**组合时的那个 lambda——
    // 功能相同的话看不出来，一旦它开始依赖新的东西（比如闭包住一个新的 ViewModel
    // 引用）就会变成「界面更新了、点击还在走旧逻辑」。
    val latestTap by rememberUpdatedState(onTap)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    val latestSeekPreview by rememberUpdatedState(onSeekPreview)
    val latestSeekCommit by rememberUpdatedState(onSeekCommit)
    val latestLevelChange by rememberUpdatedState(onLevelChange)
    val latestSpeedBoost by rememberUpdatedState(onSpeedBoost)

    return this
        .pointerInput(enabled, controller) {
            if (!enabled || controller == null) return@pointerInput
            val slop = viewConfiguration.touchSlop
            val longPressTimeout = viewConfiguration.longPressTimeoutMillis

            awaitEachGesture {
                val startPosition = latestPosition
                val duration = latestDuration

                // `requireUnconsumed = false` 是**必须**的，不能改成默认值。
                //
                // 同一个节点上还挂着一个 detectTapGestures，它拿到 down 的第一件
                // 事就是 consume 掉（它要靠这个阻止下层的滚动容器跟着滚）。若这里
                // 要求「这个事件没被人碰过才接管」，这次 down 永远会被判成已消费，
                // 整个拖动层等于没写——而且失败是静默的：不报错、不警告，只是拖什么
                // 都没反应。（`detectDragGestures` 内部也是这么写的。）
                //
                // 「按在控制条上不该改变亮度/音量」不靠这里判，靠命中测试：控制条是
                // 与画面**平级、更靠上**的兄弟节点，Compose 命中到它之后不会再往下
                // 把事件交给画面。
                val down = awaitFirstDown(requireUnconsumed = false)
                var released = false

                // ---- 阶段 1：要么定下方向，要么按满一个长按 ----
                val axis = withTimeoutOrNull(longPressTimeout) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            // 手指抬起而方向还没定 → 这是一次点击，交给点击层。
                            released = true
                            break
                        }
                        val decided = PlayerGestures.axisFor(
                            dx = change.position.x - down.position.x,
                            dy = change.position.y - down.position.y,
                            slop = slop,
                        )
                        if (decided != PlayerGestures.DragAxis.NONE) {
                            return@withTimeoutOrNull decided
                        }
                    }
                    // 走到这里只有上面那个 break：没定方向就松手了。
                    // 结尾这句 null 同时把 lambda 的类型钉成 `DragAxis?`
                    // （超时返回的也是它）。
                    null
                }

                if (released) return@awaitEachGesture

                if (axis == null) {
                    // 既没动够、也没松手 = 按满了一个长按。
                    //
                    // 判断「还在按着」不能靠返回值：超时和「手指抬起」都从上面那个
                    // lambda 里出来，返回值区分不了，所以才另设了 `released` 标记。
                    latestSpeedBoost(true)
                    try {
                        // 按住期间什么都不做，但事件必须一直读干净：留在队列里的
                        // 运动事件会在松手之后被一次读完，表现为「松手后又跳了一下」。
                        awaitPointerEnd(down.id)
                    } finally {
                        // 用 finally 而不是顺序执行：这条协程会因为「离开播放页」
                        // 或「参数变化导致 pointerInput 重启」被取消，而取消点正好
                        // 卡在 awaitPointerEnd 里面。不写 finally 的话，用户按住时
                        // 按返回键离开，播放速度就永久停在 2× 了。
                        latestSpeedBoost(false)
                    }
                    return@awaitEachGesture
                }

                // ---- 阶段 2：只认已经锁定的那个方向 ----
                var isVolume = false
                var startLevel = 0f
                if (axis == PlayerGestures.DragAxis.VERTICAL) {
                    isVolume = PlayerGestures.sideOf(down.position.x, size.width.toFloat()) ==
                        PlayerGestures.Side.RIGHT
                    startLevel = if (isVolume) {
                        controller.currentSystemVolume()
                    } else {
                        controller.currentBrightness()
                    }
                }
                var target = startPosition

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    // 消费掉，点击层见到已消费的事件就会取消这次点击。
                    change.consume()
                    // 用「相对按下点的**累计**位移」而不是每次事件的增量：和
                    // levelAfterDrag 的参数语义一致，也天然支持用户拖到一半再拖回去。
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (axis == PlayerGestures.DragAxis.VERTICAL) {
                        val range = if (isVolume) {
                            PlayerGestures.LevelRange.Volume
                        } else {
                            PlayerGestures.LevelRange.Brightness
                        }
                        val level = PlayerGestures.levelAfterDrag(
                            start = startLevel,
                            totalDy = dy,
                            height = size.height.toFloat(),
                            range = range,
                        )
                        if (isVolume) {
                            controller.setSystemVolume(level)
                        } else {
                            controller.setBrightness(level)
                        }
                        latestLevelChange(
                            PlayerLevelHint(
                                isVolume = isVolume,
                                percent = PlayerGestures.levelPercent(level),
                            ),
                        )
                    } else if (duration > 0L) {
                        target = PlayerGestures.seekTarget(
                            startPositionMs = startPosition,
                            totalDx = dx,
                            width = size.width.toFloat(),
                            durationMs = duration,
                        )
                        latestSeekPreview(
                            PlayerSeekHint(
                                deltaMs = target - startPosition,
                                targetMs = target,
                                durationMs = duration,
                            ),
                        )
                    }
                }

                // 松手才跳。竖直方向没有「提交」这一步：亮度和音量已经一边拖一边
                // 下给系统了，回滚没有意义。
                if (axis == PlayerGestures.DragAxis.HORIZONTAL && duration > 0L) {
                    latestSeekCommit(target)
                }
            }
        }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onTap = latestTap?.let { callback -> { callback() } },
                onDoubleTap = { offset ->
                    latestDoubleTap(PlayerGestures.sideOf(offset.x, size.width.toFloat()))
                },
                // 必须给一个**非 null** 的空实现。
                //
                // onLongPress 为 null 时 detectTapGestures 会把长按超时设成
                // Long.MAX_VALUE（也就是永远不判长按），于是一次三秒的按住松手后
                // 会照常触发 onTap——横屏下表现为「刚按完，控制条自己跳出来了」。
                //
                // 长按本身由上面那层处理，这个空实现的作用只是：和上面共用同一份
                // 超时判定，并在长按期间把这段事件消费掉（阻止下层滚动）。
                onLongPress = {},
            )
        }
}

/**
 * 等到这一根手指抬起（或者事件流里再也看不到它）。
 *
 * 用在「长按加速」：按住期间什么都不做，但必须一直把事件读干净，否则剩下的
 * 运动事件会堆在队列里，等松手之后再被一次读完。
 */
private suspend fun AwaitPointerEventScope.awaitPointerEnd(id: PointerId) {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id } ?: return
        if (!change.pressed) return
    }
}
