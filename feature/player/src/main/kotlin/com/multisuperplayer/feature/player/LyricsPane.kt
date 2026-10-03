package com.multisuperplayer.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument

/**
 * 音频页的歌词面板。
 *
 * ## 当前行用 [SubtitleDocument.cueFocusIndexAt]，其他行不用
 *
 * 「高亮哪一行」必须用 `cueFocusIndexAt`（最后一个已经开始的行），而不是 `cueAt`：
 * LRC 只记录每行的开始时间、没有结束概念，用 `cueAt` 会让高亮在每句末尾闪一下或者
 * 整句消失——这正是那两个方法的 KDoc 里写明的分工。
 *
 * ## 自动滚动的两个细节
 *
 * 1. 只在**行号变化**时滚（`LaunchedEffect(activeIndex)`），不是每 200ms 滚一次。
 * 2. 用户正在手动滑动时不抢。手指还在屏幕上就把列表拽回去，表现为
 *    「怎么滑都被弹回当前行」——用 `isScrollInProgress` 挡掉这一次，
 *    松手之后下一次换行会自然接上。
 */
@Composable
internal fun LyricsPane(
    document: SubtitleDocument,
    positionMs: Long,
    mode: SubtitleDisplayMode,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    timelineOffsetMs: Long = 0L,
) {
    val listState = rememberLazyListState()
    // 高亮行和逐字高亮用同一个「纠偏后」的时刻，否则条子和高亮会差半秒。
    val cuePositionMs = subtitleCuePosition(positionMs, timelineOffsetMs)
    val activeIndex = document.cueFocusIndexAt(cuePositionMs)

    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        if (listState.isScrollInProgress) return@LaunchedEffect
        listState.animateScrollToItem((activeIndex - ANCHOR_OFFSET).coerceAtLeast(0))
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 不给 key：字幕 cue 的 index 由解析器给，万一重复会让 LazyColumn 直接
        // 抛异常（"Key was already used"），而按位置做 key 在「列表只增不改」
        // 的字幕上没有任何损失。
        itemsIndexed(items = document.cues) { index, cue ->
            LyricLine(
                cue = cue,
                isActive = index == activeIndex,
                positionMs = cuePositionMs,
                mode = mode,
                onClick = { onSeekTo(cue.startMs) },
            )
        }
    }
}

/**
 * 一行歌词。
 *
 * @param isActive 只有当前行算逐字高亮。给每一行都算一遍既浪费，也会让整页
 *   到处在闪——用户根本不知道该看哪一行。
 */
@Composable
private fun LyricLine(
    cue: SubtitleCue,
    isActive: Boolean,
    positionMs: Long,
    mode: SubtitleDisplayMode,
    onClick: () -> Unit,
) {
    val lines = cueLinesFor(cue, mode)
    if (lines.isEmpty) return

    val scheme = MaterialTheme.colorScheme
    val textColor = if (isActive) scheme.onSurface else scheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(
                if (isActive) scheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent,
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CueTextBlock(
            lines = lines,
            // 非当前行传 null：不参与逐字高亮。
            cue = if (isActive) cue else null,
            positionMs = positionMs,
            originalStyle = if (isActive) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            originalColor = textColor,
            // 已唱的用主色、未唱的用弱化色：这是歌词播放器的通行画法，
            // 用户不用学就知道扫到哪儿了。
            karaokeBaseColor = scheme.onSurfaceVariant,
            karaokeHighlightColor = scheme.primary,
            translationStyle = MaterialTheme.typography.bodyMedium,
            translationColor = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 让当前行落在视口偏上的位置，而不是正好贴在最上面。
 *
 * 贴在最上面的话，下一句还没出现就已经在视口外了，滚动看起来是「跳」而不是「走」。
 */
private const val ANCHOR_OFFSET = 2
