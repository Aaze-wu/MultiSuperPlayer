package com.multisuperplayer.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.translate.SubtitleExportFormat
import com.multisuperplayer.core.translate.SubtitleExportMode
import com.multisuperplayer.core.ui.text.string

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
    translation: TranslationUiState,
    exportMessage: MspText?,
    onDismiss: () -> Unit,
    onSelectMode: (SubtitleDisplayMode) -> Unit,
    onSelectSource: (SubtitleSource) -> Unit,
    onUseAuto: () -> Unit,
    onRescan: () -> Unit,
    onTranslateAll: () -> Unit,
    onTranslateUpTo: () -> Unit,
    onCancelTranslation: () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
    onExport: (SubtitleExportFormat, SubtitleExportMode) -> Unit,
    onDismissExportMessage: () -> Unit,
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
            Text(
                text = stringResource(R.string.msp_player_subtitles_title),
                style = MaterialTheme.typography.titleLarge,
            )

            ModeChips(current = state.displayMode, onSelect = onSelectMode)

            StatusBlock(state = state)

            HorizontalDivider()

            CandidateList(
                state = state,
                onUseAuto = onUseAuto,
                onSelectSource = onSelectSource,
            )

            TextButton(onClick = onRescan, enabled = !state.isLoading) {
                Text(stringResource(R.string.msp_player_rescan))
            }
            
            TranslationSection(
                translation = translation,
                subtitle = state,
                exportMessage = exportMessage,
                onTranslateAll = onTranslateAll,
                onTranslateUpTo = onTranslateUpTo,
                onCancel = onCancelTranslation,
                onRetryFailed = onRetryFailed,
                onOpenSettings = onOpenTranslationSettings,
                onExport = onExport,
                onDismissExportMessage = onDismissExportMessage,
            )
        }
    }
}

@Composable
private fun ModeChips(
    current: SubtitleDisplayMode,
    onSelect: (SubtitleDisplayMode) -> Unit,
) {
    // 用 FlowRow（宽度随文案、装不下就换行）而不是等分宽度的 Row。
    //
    // 中文档位名都是两个字，等分之后四个芯片正好摆平；英文是
    // Hidden / Original / Translation / Bilingual，同样等分就装不下了，
    // FilterChip 会把文字直接裁成「Translati」——一个只在英文/繁体下出现、
    // 看中文截图永远发现不了的缺陷。换行比缩小字号、截断或缩写都更稳妥。
    //
    // 等分原本是为了「选中项左右不跳动」，但每个芯片的文案是固定的，
    // 宽度本来就不会随选中状态变化，所以去掉 weight 并不会跳。
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SubtitleDisplayMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == current,
                onClick = { onSelect(mode) },
                label = {
                    Text(
                        text = mode.label().string(),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
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
                text = stringResource(
                    if (state.phase == SubtitlePhase.SCANNING) {
                        R.string.msp_player_scanning
                    } else {
                        R.string.msp_player_reading
                    },
                ),
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
                        text = stringResource(
                            R.string.msp_player_cues,
                            attached.describeAttached().string(),
                            state.cueCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.msp_player_none_attached),
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
            text = stringResource(R.string.msp_player_translation_missing_shown_original),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.tertiary,
        )
    }

    state.issue?.let { issue ->
        Text(
            text = issue.describe().string(),
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
            title = stringResource(R.string.msp_player_auto_select),
            details = (
                state.attached
                    ?.takeIf { state.autoSelected }
                    ?.let { MspText.Res(R.string.msp_player_auto_selected, it.fileName) }
                    ?: MspText.Res(R.string.msp_player_auto_pick_desc)
                ).string(),
            selected = state.autoSelected,
            onClick = onUseAuto,
        )

        state.candidates.forEach { source ->
            SourceRow(
                title = source.fileName,
                details = source.describeDetails().string(),
                // 只有「手动选中」才算选上：自动选中时这一行的选中态由上面那行表达，
                // 两行同时打勾会让人以为是两个不同的设置。
                selected = !state.autoSelected && state.attached?.uri == source.uri,
                onClick = { onSelectSource(source) },
            )
        }

        if (state.candidates.isEmpty() && !state.isLoading) {
            Text(
                text = stringResource(R.string.msp_player_no_usable_subtitle),
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
