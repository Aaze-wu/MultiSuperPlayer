package com.multisuperplayer.feature.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 播放画面的手势层。
 *
 * 手势的判定规则全部来自 [PlayerGestures]（纯函数、有单测），这里只负责
 * 「把指针事件接上」。这样分的理由：判定规则里那些边界（中线归哪边、拖满高度
 * 变多少、亮度能不能到 0）只能在单元测试里钉死，靠手试是试不出来的。
 *
 * ## 为什么是两层 `pointerInput` 而不是一个
 *
 * 「竖直拖动」和「点击/双击」是两套互不相干的判定：Compose 没有把它们合成一个
 * 检测器的 API（`detectTapGestures` 处理 up，`detectVerticalDragGestures` 处理
 * move）。写在两个 `pointerInput` 里，各读各的事件流：
 * - 拖动超过 touch slop 之后，点击检测器会自己取消这次点击，不会在松手时误触发「单击」；
 * - 反过来，一次轻点不会让拖动检测器越过 slop。
 *
 * ## 谁抢谁
 *
 * 这个修饰符加在**画面那一层**（控制层的下面）。Compose 命中的顺序是从上往下，
 * 所以上面的 `Slider`/按钮先拿到事件并且会消费掉它，[detectVerticalDragGestures]
 * 的触碰阈值判定见到已消费的事件会直接放弃——拖进度条不会顺手改音量。
 *
 * @param onTap null = 这一层不处理单击。竖屏下不需要（控制条本来就在画面外面
 *   一直可见），传 null 可以让轻点完全不产生任何效果。
 * @param onLevelChange 拖动的中间结果，用来画屏幕中间那个提示泡。
 *   松手**不**回调 null：那个提示泡应该在手指抬起之后再多留一会儿，
 *   「什么时候消失」是计时器的事，写在这里会让逻辑分叉成两处。
 */
internal fun Modifier.playerGestures(
    enabled: Boolean,
    controller: PlayerWindowController?,
    onTap: (() -> Unit)?,
    onDoubleTap: (PlayerGestures.Side) -> Unit,
    onLevelChange: (PlayerLevelHint) -> Unit,
): Modifier = this
    .pointerInput(enabled, controller) {
        if (!enabled || controller == null) return@pointerInput

        // 这几个局部变量在 pointerInput 的协程里活得和它一样久，所以能跨手势保存
        // 「这次拖动是从哪个值开始的」。
        var isVolume = false
        var startLevel = 0f
        var totalDy = 0f

        detectVerticalDragGestures(
            onDragStart = { offset ->
                isVolume = PlayerGestures.sideOf(offset.x, size.width.toFloat()) == PlayerGestures.Side.RIGHT
                startLevel = if (isVolume) controller.currentSystemVolume() else controller.currentBrightness()
                totalDy = 0f
                // 这里刻意不弹提示：手指刚落下还没动，弹一个「50%」出来很吵。
            },
            onVerticalDrag = { change, dragAmount ->
                change.consume()
                totalDy += dragAmount
                val range = if (isVolume) {
                    PlayerGestures.LevelRange.Volume
                } else {
                    PlayerGestures.LevelRange.Brightness
                }
                val level = PlayerGestures.levelAfterDrag(
                    start = startLevel,
                    totalDy = totalDy,
                    height = size.height.toFloat(),
                    range = range,
                )
                if (isVolume) controller.setSystemVolume(level) else controller.setBrightness(level)
                onLevelChange(
                    PlayerLevelHint(isVolume = isVolume, percent = PlayerGestures.levelPercent(level)),
                )
            },
        )
    }
    .pointerInput(enabled) {
        if (!enabled) return@pointerInput
        detectTapGestures(
            onTap = onTap?.let { callback -> { callback() } },
            onDoubleTap = { offset ->
                onDoubleTap(PlayerGestures.sideOf(offset.x, size.width.toFloat()))
            },
        )
    }
