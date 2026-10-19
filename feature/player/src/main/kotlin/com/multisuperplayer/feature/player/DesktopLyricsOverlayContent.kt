package com.multisuperplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
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
 * 坐标一律是**手指相对窗口左上角的绝对位置**（不是每次事件的增量）：窗口本身
 * 在跟着手指移动，增量式的坐标会自己把移动量扣掉，见 [detectWindowDrag]。
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
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.92f).dp
    val fontSize = state.style.fontSize.sp
    Column(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = state.style.opacity.alpha))
            .pointerInput(state.locked) {
                if (!state.locked) {
                    detectWindowDrag(
                        onStart = actions.onDragStart,
                        onMove = actions.onDragMove,
                        onEnd = actions.onDragEnd,
                    )
                }
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
            // 用 clickable 而不是 IconButton：后者会带上 m3 的最小触摸尺寸与
            // 水波涟漪，在这块小小的半透明面板上显得很突兀。
            .pointerInput(onClick) { detectTapGestures(onTap = { onClick() }) },
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

/**
 * 拖动浮窗。
 *
 * ## 为什么不用 `detectDragGestures`
 *
 * 它给的是每次事件的 `dragAmount`（即 `position - previousPosition`）。窗口跟着
 * 手指在移动，本地坐标系本身在变，增量里已经把「窗口挪了多少」扣掉了；更糟的是
 * `previousPosition` 在 Android 后端取自 `MotionEvent` 的历史采样点，某些设备上
 * 一次事件里没有历史点 ⇒ 增量恒为 `0`，表现是「长按成立但拖不动」。
 *
 * 所以这里只用**绝对位置**：`down.position` 与之后的 `change.position`。
 * 窗口挪动导致的那部分偏差由调用方拿「已经应用了多少位移」补偿回来，见
 * [DesktopLyricsWindow.dragMove]。
 *
 * 拖动一旦成立就 `consume()`：底下那层「点一下回应用」的 `clickable` 因此
 * 不会在拖完之后再触发一次跳转。
 */
private suspend fun PointerInputScope.detectWindowDrag(
    onStart: (Float, Float) -> Unit,
    onMove: (Float, Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var dragging = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (!dragging) {
                if ((change.position - down.position).getDistance() >= viewConfiguration.touchSlop) {
                    dragging = true
                    // 起点取「按下的位置」而不是「跨过阈值的位置」：用后者会把
                    // 按下到过阈值之间的这段位移永久吞掉。
                    onStart(down.position.x, down.position.y)
                    onMove(change.position.x, change.position.y)
                }
            } else {
                change.consume()
                onMove(change.position.x, change.position.y)
            }
        }
        if (dragging) onEnd()
    }
}
