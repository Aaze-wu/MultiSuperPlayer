package com.multisuperplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.ui.list.ReorderDragRules

/**
 * 播放队列面板：看现在排了什么，直接跳到某一条，删掉、重排。
 *
 * ## 为什么点击就切歌，而不是「选中 + 确认」
 *
 * 这一页的用途是「我不想听这首，换下一个」。多一次确认在这里是纯负担。
 *
 * ## 为什么这一行不显示时长
 *
 * 队列里的条目在拖动排序时会移动位置，每一行的高度必须**完全一致**
 * （[QUEUE_ROW_HEIGHT]），否则落点换算（[ReorderDragRules]）就会算错行数。
 * 让每一行只有一行标题 + 一行副标题，高度就是定值，不用去管每条媒体的
 * 时长文字长短、封面有没有解析出来这些差异。
 *
 * @param queue 当前队列，顺序就是播放顺序。
 * @param currentIndex 正在播放的那一条的下标（可能不在 0..size-1 里，见下）。
 * @param shuffleEnabled 随机播放开着的时候**不允许**手动排序：那时列表顺序
 *   已经不是播放顺序了，拖出来的位置和实际听到的顺序对不上——允许它，
 *   用户会得到「拖了但顺序还是乱的」这个无法解释的结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerQueueSheet(
    queue: List<MediaEntry>,
    currentIndex: Int,
    shuffleEnabled: Boolean,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.msp_player_queue),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.msp_player_queue_count, queue.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                // 只剩一条时禁用：删掉队列里唯一的条目等于 stopAndClear，
                // 也就是「点一下清空队列，播放停掉、画面变成『没有正在播放的内容』」。
                // 那个动作值得在**列表里**逐条确认，不该由一个顺手的按钮完成。
                TextButton(onClick = onClear, enabled = queue.size > 1) {
                    Text(
                        text = stringResource(R.string.msp_player_queue_clear),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (queue.isEmpty()) {
                // 正常走不到这里（控制条上的队列芯片在空队列时不出现），留一个
                // 说明总比画一个空盒子好——万一将来别处能打开它，至少读得出原因。
                Text(
                    text = stringResource(R.string.msp_player_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }

            QueueList(
                queue = queue,
                currentIndex = currentIndex,
                shuffleEnabled = shuffleEnabled,
                onPlay = onPlay,
                onRemove = onRemove,
                onMove = onMove,
            )

            Text(
                text = stringResource(
                    if (shuffleEnabled) {
                        R.string.msp_player_queue_shuffle_locked
                    } else {
                        R.string.msp_player_queue_drag_hint
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * 队列列表本体（滚动 + 拖拽重排）。
 *
 * 拖拽状态全部在这里，**不往上交给面板**：面板只知道「谁该移到哪儿了」
 * （列表在手势结束时调一次 [onMove]），拖动过程中那些中间值
 * （位移多少像素、现在悬在哪一行上）每秒会变几十次，让面板跟着重组没有意义。
 */
@Composable
private fun QueueList(
    queue: List<MediaEntry>,
    currentIndex: Int,
    shuffleEnabled: Boolean,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val rowHeightPx = with(density) { QUEUE_ROW_HEIGHT.toPx() }
    // 渐变盖在列表上时用的底色：用 `surface` 的**不透明**值——渐变要假装内容是从
    // 面板里淡出来的，而底部面板的底色就是 `surface`。半透明的话，被盖住的那半行
    // 会透出来，反而更像渲染错。
    val scrimColor = MaterialTheme.colorScheme.surface

    // 拖动中的三项状态：从哪一行起的、累计位移了多少、现在悬在哪一行上。
    // 用 `remember` 而不是 `rememberSaveable`：转屏时手势必然已经中断，
    // 恢复一个「正在被拖的行」只会让界面看起来卡在半空。
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragTarget by remember { mutableIntStateOf(-1) }

    Box(modifier = Modifier.fillMaxWidth().heightIn(max = QUEUE_LIST_MAX_HEIGHT)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(queue) { index, entry ->
                val dragging = dragFrom == index
                QueueRow(
                    index = index,
                    entry = entry,
                    isCurrent = index == currentIndex,
                    isDropTarget = dragFrom != null && dragTarget == index && !dragging,
                    shiftY = if (dragging) dragOffsetY else 0f,
                    elevated = dragging,
                    dragEnabled = !shuffleEnabled,
                    onPlay = { onPlay(index) },
                    onRemove = { onRemove(index) },
                    // 拖拽只在把手上生效（`pointerInput` 挂在这个 Box 上，不是整行）：
                    // 整行都是拖拽区的话，单击切歌会变得几乎点不准——手指落下时
                    // 总会先移动几个像素。
                    dragHandleModifier = if (shuffleEnabled) {
                        Modifier
                    } else {
                        Modifier.pointerInput(index, queue.size) {
                            detectDragGestures(
                                onDragStart = {
                                    dragFrom = index
                                    dragTarget = index
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    val from = dragFrom
                                    val target = dragTarget
                                    dragFrom = null
                                    dragOffsetY = 0f
                                    dragTarget = -1
                                    // 落点和出发点一样时什么都不做：`moveMediaItem`
                                    // 即使移到自己身上也会让当前项重新缓冲一下。
                                    if (from != null && !ReorderDragRules.isNoOp(from, target)) {
                                        onMove(from, target)
                                    }
                                },
                                // 手势被系统抢走（来电、切后台）时**不改队列**：
                                // 用户没松手，这一下不算数。状态还是要清掉，
                                // 不然那一行会永远歪在半空中。
                                onDragCancel = {
                                    dragFrom = null
                                    dragOffsetY = 0f
                                    dragTarget = -1
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                                dragTarget = ReorderDragRules.targetIndex(
                                    from = index,
                                    dragOffsetY = dragOffsetY,
                                    rowHeightPx = rowHeightPx,
                                    size = queue.size,
                                )
                            }
                        }
                    },
                )
            }
        }

        QueueEdgeFade(
            state = listState,
            edge = Alignment.TopCenter,
            colors = listOf(scrimColor, Color.Transparent),
        )
        QueueEdgeFade(
            state = listState,
            edge = Alignment.BottomCenter,
            colors = listOf(Color.Transparent, scrimColor),
        )
    }
}

/**
 * 队列里的一行。
 *
 * 高度固定（[QUEUE_ROW_HEIGHT]）是**功能要求**，不是审美偏好：拖拽的落点换算
 * 用的是「跨过几行」（[ReorderDragRules]），行高不一致就意味着算出来的位置和
 * 眼睛看到的位置不是一回事。
 */
@Composable
private fun QueueRow(
    index: Int,
    entry: MediaEntry,
    isCurrent: Boolean,
    isDropTarget: Boolean,
    shiftY: Float,
    elevated: Boolean,
    dragEnabled: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    dragHandleModifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        // 被拖起来的那一行必须是不透明的：它要盖住下面的行，半透明会糊成一片。
        elevated -> colors.surfaceVariant
        isDropTarget -> colors.primary.copy(alpha = 0.14f)
        isCurrent -> colors.primary.copy(alpha = 0.10f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(QUEUE_ROW_HEIGHT)
            // zIndex 决定同一时刻谁画在上面（拖动中的那一行要盖住邻居），
            // translationY 决定它的位置。两者都在 Row 的修饰符上，因为这些都是
            // 「画」的事，改它们不该重新测量这一行。
            .zIndex(if (elevated) 1f else 0f)
            .graphicsLayer { translationY = shiftY }
            .background(background)
            .clickable(onClick = onPlay)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.msp_player_queue_playing),
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = (index + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题可能为空（没有艺术家也没有专辑）：空的 `subtitle` 是空串，
            // 直接画出来会让这一行的两行文字只剩一行，行高还是固定的——
            // 看着像标题没对齐。
            if (entry.subtitle.isNotEmpty()) {
                Text(
                    text = entry.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.msp_player_queue_remove),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }

        // 把手。随机播放时**照样画**、只是变淡：直接不画的话，那一行会少一截，
        // 用户会以为这个版本的队列没有排序功能；画出来但淡掉，配上下面的说明
        // 就是「现在不能拖」。
        Box(
            modifier = Modifier.size(40.dp).then(dragHandleModifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DragHandle,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = if (dragEnabled) 1f else 0.3f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * 队列上下两条边缘渐变。
 *
 * 和歌词区那两条同一个作用（见 `LYRIC_EDGE_FADE`）：列表滚动时视口必然切在
 * 某一行中间，硬切看着像渲染错了。只有 `background`、没有指针输入，
 * 所以不吃命中测试——被盖住的那半行还是能点。
 *
 * ## 为什么自己去读列表状态
 *
 * 「能不能继续滚」这个状态每滚一帧就变一次。如果把这个判断写在 [QueueList] 里
 * （它的作用域里读 `listState`），整张列表每帧都会跟着重组一次。放在这里，
 * 每帧重组的就只有这一条渐变。
 */
@Composable
private fun BoxScope.QueueEdgeFade(
    state: LazyListState,
    edge: Alignment,
    colors: List<Color>,
) {
    val atTop = edge == Alignment.TopCenter
    val visible = if (atTop) state.canScrollBackward else state.canScrollForward
    if (!visible) return
    Box(
        modifier = Modifier
            .align(edge)
            .fillMaxWidth()
            .height(QUEUE_EDGE_FADE)
            .background(Brush.verticalGradient(colors)),
    )
}

/**
 * 队列里一行的高度。
 *
 * 固定值，理由见 [QueueRow]：拖拽的落点换算依赖它。
 */
private val QUEUE_ROW_HEIGHT = 56.dp

/**
 * 列表区的最大高度。
 *
 * `ModalBottomSheet` 的内容不限高的话，队列长了会把整块屏幕顶满，用户看不到
 * 「这是一张可以划走的底部面板」。限制在六行出头：既有「下面还有很多」的暗示，
 * 又留出划走它的地方。
 */
private val QUEUE_LIST_MAX_HEIGHT = 320.dp

/**
 * 队列两端那条渐变的高度。
 *
 * 比歌词区的那条矮：一行标题加一行副标题只有 56dp，28dp 的渐变会把半行都吃掉。
 */
private val QUEUE_EDGE_FADE = 16.dp
