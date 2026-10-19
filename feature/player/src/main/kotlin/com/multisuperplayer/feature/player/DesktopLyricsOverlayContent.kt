package com.multisuperplayer.feature.player

import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮窗上的按钮回调。
 *
 * 用一个普通类装一组 lambda，而不是给 composable 加八个参数：拖动那三个必须
 * 一起传（拆开之后很容易只接一半，症状是「能拖但松手不记忆」），而按钮那几个
 * 又都属于同一件事。
 *
 * 拖动那三个回调的坐标是**手指在屏幕上的绝对坐标**（`MotionEvent.rawX/rawY`），
 * 不是窗口内坐标、也不是增量——为什么必须是屏幕绝对坐标见
 * [DesktopLyricsWindow.dragMove]。
 */
internal class DesktopLyricsActions(
    val onDragStart: (Float, Float) -> Unit,
    val onDragMove: (Float, Float) -> Unit,
    val onDragEnd: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onLock: () -> Unit,
    /** 正数放大、负数缩小，见 [stepDesktopLyricsFontSize]。 */
    val onFontStep: (Int) -> Unit,
    val onOpenApp: () -> Unit,
)

/**
 * 桌面歌词的整个可视内容。
 *
 * 没有用 `MspTheme`：浮窗浮在**别的应用**上面，配色不能跟着应用主题走
 * （浅色主题下白字会糊掉、深色主题下黑底会看不见），所以固定「半透明黑底 +
 * 带阴影的白字」这一套，在任何背景下都读得清。透明度只作用于**背景**。
 */
@Composable
internal fun DesktopLyricsOverlayContent(
    state: DesktopLyricsUiState,
    actions: DesktopLyricsActions,
) {
    // 宽度由**窗口**给定（92% 屏幕，见 [DesktopLyricsWindow.fixedWidthPx]），
    // 这里只填满它：用 `widthIn(max = ...)` 时窗口会随那一句缩胀，切歌就是在
    // 屏幕上来回伸缩；而位置按左上角存 ⇒ 宽度一变视觉中心就漂，看着像
    // 「它自己挪了」。高度仍然包裹内容（有没有译文决定是两行还是三行）。
    val fontSize = state.style.fontSize.sp
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val dragTracker = remember { WindowDragTracker(touchSlop) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = state.style.opacity.alpha))
            // 锁定之后窗口带着 `FLAG_NOT_TOUCHABLE`，事件根本到不了这里，
            // 所以这里不用再看 `state.locked`。
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        dragTracker.down(event.rawX, event.rawY)

                    MotionEvent.ACTION_MOVE -> when (
                        dragTracker.move(event.rawX, event.rawY)
                    ) {
                        WindowDragEvent.START -> {
                            // 起点用「按下的位置」：用「跨过阈值的位置」会把按下到
                            // 过阈值这段位移永久吞掉。
                            actions.onDragStart(dragTracker.downX, dragTracker.downY)
                            actions.onDragMove(event.rawX, event.rawY)
                        }

                        WindowDragEvent.MOVE ->
                            actions.onDragMove(event.rawX, event.rawY)

                        WindowDragEvent.NONE, WindowDragEvent.END -> Unit
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (dragTracker.up() == WindowDragEvent.END) actions.onDragEnd()
                }
                // 必须消费：Compose 里没有节点消费 DOWN 时 `AndroidComposeView.onTouchEvent`
                // 返回 false，`PointerInteropFilter` 拿到一个 DOWN 之后就不再往下收了
                // （实测整个手势只有一条 `interop 0`，拖动完全不响应）。
                //
                // 代价是 DOWN / UP 都被标成「已消费」，所以下面按钮的点击手势
                // **不能依赖 consumed 标记**——见 [OverlayButton]。
                true
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (state.rows.isEmpty()) {
            // 门禁正常情况下不会让这种状态出现，留一行是防止「窗口在、内容空」时
            // 变成一个纯粹的黑色方块——那看起来像卡死。
            Text(
                text = state.title.orEmpty(),
                style = lyricStyle(fontSize * 0.85f, Color.White.copy(alpha = 0.6f)),
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        } else {
            state.rows.forEach { row ->
                val (size, color, weight) = when (row.role) {
                    DesktopLyricsRowRole.CURRENT ->
                        Triple(fontSize.toFloat(), Color.White, FontWeight.SemiBold)
                    DesktopLyricsRowRole.TRANSLATION ->
                        Triple(fontSize * 0.8f, Color.White.copy(alpha = 0.9f), FontWeight.Normal)
                    DesktopLyricsRowRole.NEXT ->
                        Triple(fontSize * 0.75f, Color.White.copy(alpha = 0.45f), FontWeight.Normal)
                }
                Text(
                    text = row.text,
                    style = lyricStyle(size, color, weight),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }

        // 锁定之后就整块穿透（窗口层面加 FLAG_NOT_TOUCHABLE），这里不画按钮：
        // 画了也点不到，反而让人以为没锁上。解锁在应用里的播放页。
        if (!state.locked) {
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlayButton(
                    icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = null,
                    onClick = actions.onTogglePlay,
                )
                OverlayButton(
                    icon = Icons.Filled.TextDecrease,
                    label = null,
                    onClick = { actions.onFontStep(-1) },
                )
                OverlayButton(
                    icon = Icons.Filled.TextIncrease,
                    label = null,
                    onClick = { actions.onFontStep(+1) },
                )
                OverlayButton(
                    icon = Icons.Filled.Lock,
                    label = null,
                    onClick = actions.onLock,
                )
            }
        }
    }
}

/**
 * 歌词文本的统一样式：白色 + 一圈柔光阴影。
 *
 * 阴影是必须的——浮窗背景只有 40%~100% 的黑，底下可能是白底网页，
 * 纯白字在浅色半透明底上直接消失。描边（`drawStyle = Stroke`）要画两遍文字，
 * 阴影一层就够。
 */
private fun lyricStyle(
    size: Float,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
): TextStyle = TextStyle(
    fontSize = size.sp,
    color = color,
    fontWeight = weight,
    shadow = Shadow(color = Color.Black.copy(alpha = 0.85f), blurRadius = 8f),
)

/** 浮窗上的小圆钮。图标按钮没有文案，所以不需要任何翻译资源。 */
@Composable
private fun OverlayButton(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            // 用 clickable / detectTapGestures 都会失效：父节点上的 `pointerInteropFilter`
            // 把 DOWN、UP 都标成「已消费」（它必须这么做，否则后续事件根本收不到），
            // 而 Compose 的点手器正是靠 consumed 标记来取消点击的。
            //
            // 所以这里自己实现：不看 consumed，只看「按下后有没有移动超过 touchSlop」。
            .pointerInput(onClick) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes
                            .firstOrNull { it.id == down.id } ?: break
                        // 用与按下点的绝对距离判断，不用 positionChange()：
                        // 后者在事件没有历史采样点时会恒为 0。
                        if ((change.position - down.position).getDistance() > slop) moved = true
                        if (!change.pressed) {
                            if (!moved) onClick()
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (label == null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(16.dp),
            )
        } else {
            Text(text = label, color = Color.White.copy(alpha = 0.92f), fontSize = 11.sp)
        }
    }
}

