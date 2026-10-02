package com.multisuperplayer.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.subtitle.SubtitleSource

/**
 * 字幕选择面板。
 *
 * ## 为什么「隐藏」是模式而不是一行「不使用字幕」
 *
 * 四档模式里已经包含「隐藏」，再单独放一个「不使用这条字幕」就会出现两个看起来
 * 一样、实际不一样的状态（一个全局、一个针对当前文件）。用户分不清，于是会出现
 * 「我明明关了它还在显示」。这是「一个布尔同时表示两件事」的翻版，
 * 所以这里只保留一维：模式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubtitleTrackPicker(
    state: SubtitleUiState,
    onDismiss: () -> Unit,
    onSelectMode: (SubtitleDisplayMode) -> Unit,
    onSelectSource: (SubtitleSource) -> Unit,
    onUseAuto: () -> Unit,
    onRescan: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "字幕", style = MaterialTheme.typography.titleLarge)

            ModeChips(current = state.displayMode, onSelect = onSelectMode)

            StatusBlock(state = state)

            HorizontalDivider()

            CandidateList(
                state = state,
                onUseAuto = onUseAuto,
                onSelectSource = onSelectSource,
            )

            TextButton(onClick = onRescan, enabled = !state.isLoading) {
                Text("重新扫描字幕")
            }
        }
    }
}

@Composable
private fun ModeChips(
    current: SubtitleDisplayMode,
    onSelect: (SubtitleDisplayMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubtitleDisplayMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == current,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        text = mode.label(),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                // 等分宽度：四档模式的名字长度不一样，不等分的话选中项左右跳动。
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusBlock(state: SubtitleUiState) {
    val scheme = MaterialTheme.colorScheme

    when {
        state.isLoading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text(
                text = if (state.phase == SubtitlePhase.SCANNING) "正在查找字幕…" else "正在读取字幕…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        else -> {
            val attached = state.attached
            if (attached != null) {
                Column {
                    Text(
                        text = attached.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${attached.describeAttached()} · ${state.cueCount} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = "当前没有挂上任何字幕。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }

    // 「选了仅译文但没有译文」必须解释一句。不解释的话用户看到的是原文，
    // 会认为「切了没反应」，然后去反复点那几个按钮。
    if (state.translationUnavailable) {
        Text(
            text = "这份字幕没有译文，已按原文显示。",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.tertiary,
        )
    }

    state.issue?.let { issue ->
        Text(
            text = issue.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }

    if (state.warnings.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            state.warnings.forEach { warning ->
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CandidateList(
    state: SubtitleUiState,
    onUseAuto: () -> Unit,
    onSelectSource: (SubtitleSource) -> Unit,
) {
    Column {
        SourceRow(
            title = "自动选择",
            details = state.attached
                ?.takeIf { state.autoSelected }
                ?.let { "已自动选中「${it.fileName}」" }
                ?: "按片名从候选里挑一条最吻合的",
            selected = state.autoSelected,
            onClick = onUseAuto,
        )

        state.candidates.forEach { source ->
            SourceRow(
                title = source.fileName,
                details = source.describeDetails(),
                // 只有「手动选中」才算选上：自动选中时这一行的选中态由上面那行表达，
                // 两行同时打勾会让人以为是两个不同的设置。
                selected = !state.autoSelected && state.attached?.uri == source.uri,
                onClick = { onSelectSource(source) },
            )
        }

        if (state.candidates.isEmpty() && !state.isLoading) {
            Text(
                text = "这个文件夹里没有找到可用的字幕文件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SourceRow(
    title: String,
    details: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // onClick = null：点击由整行的 clickable 处理，避免出现
        // 「点文字生效、点图标不生效」这种两种命中区。
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
