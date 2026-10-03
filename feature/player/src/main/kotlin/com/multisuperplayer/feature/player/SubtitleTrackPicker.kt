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
import com.multisuperplayer.core.player.MspTrackInfo
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
    onSelectEmbedded: (MspTrackInfo) -> Unit,
    onNudgeTimeline: (Long) -> Unit,
    onResetTimeline: () -> Unit,
    onUseAuto: () -> Unit,
    onRescan: () -> Unit,
    onPickFile: () -> Unit,
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
                onSelectEmbedded = onSelectEmbedded,
            )

            // 「重新扫描」和「选择字幕文件」并排放，是因为它们是同一件事的两种找法：
            // 一个是让应用去猜（扫媒体文件所在目录），一个是人来指定。
            //
            // 两个按钮的 enabled 刻意不同：扫描期间「重新扫描」要禁用（避免并发扫描），
            // 而**手动选文件必须一直可点**——自动发现失败、扫到一半卡住、目录根本
            // 看不见（SAF 授权被回收）这些情况下，手动这条路是唯一的出口。
            // 把它一起禁用掉，用户就只能关面板、看着一份说明文字发呆。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRescan, enabled = !state.isLoading) {
                    Text(stringResource(R.string.msp_player_rescan))
                }
                TextButton(onClick = onPickFile) {
                    Text(stringResource(R.string.msp_player_pick_file))
                }
            }

            SubtitleSyncSection(
                state = state,
                onNudge = onNudgeTimeline,
                onReset = onResetTimeline,
            )

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
            val embedded = state.embeddedTrack
            when {
                embedded != null -> Column {
                    Text(
                        text = embedded.displayLabel(
                            stringResource(
                                R.string.msp_player_embedded_track,
                                embedded.indexInGroup + 1,
                            ),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 只给「什么格式、什么语言」，不给条数：内嵌轨是边播边读的，
                    // 读到的总数只有播完才知道。给一个一直在涨的数字，
                    // 用户会以为字幕不完整。
                    Text(
                        text = embedded.describeDetails().string(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }

                attached != null -> Column {
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

                else -> Text(
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

/**
 * 候选为空时要不要画那句「这个文件夹里没有找到可用的字幕文件」。
 *
 * 抽成纯函数是为了能测：这句话是一个**断言**（「查过了，这个文件夹里没有能用的字幕」），
 * 而 [SubtitleUiState.issue] 不为空时，屏幕上已经有一句话在讲真正的原因
 * （「无法确定这个文件所在的文件夹，没法自动找同名字幕」/「读不到这个文件所在的文件夹」
 * /「查找字幕时出错」），两句话并排出现就是自相矛盾——上面说「我不知道是哪个文件夹」，
 * 下面说「那个文件夹里没有」。用户会照后者去反复改字幕文件名，而问题在权限或来路上。
 *
 * `issue == NoSubtitles` 时也是同理：同一件事说两遍不如说一遍。
 */
internal fun showsNoUsableSubtitleHint(state: SubtitleUiState): Boolean =
    state.candidates.isEmpty() &&
        state.embeddedTracks.isEmpty() &&
        !state.isLoading &&
        state.issue == null

/**
 * 微调的四个步长。两档而不是一档：0.5 秒够把明显偏了的拉近，0.1 秒才够把
 * 「已经差不多」的调到贴合。只留一档必然有一半人用不顺——嫌粗的人会以为
 * 这个功能没用，嫌细的人要按十几次。
 */
private val SUBTITLE_SYNC_STEPS = listOf(-500L, -100L, 100L, 500L)

/**
 * 字幕时间轴微调。
 *
 * ## 只在真的挂着一条字幕时出现
 *
 * 没挂字幕时调它没有任何可观察的结果，用户会以为按键坏了；反过来说，一句
 * 「没有可用字幕」的提示旁边摆一排 ±秒 按钮也很奇怪。
 *
 * ## 为什么放在面板里而不是播放页上
 *
 * 它是一个**纠偏**动作：只有在画面上看出字幕对不上时才会去做，做的时候需要
 * 看着画面反复微调。面板是上面的浮层，调的时候画面还在后面放着；而把四个
 * 按钮摆到控制栏里，每一天正常的播放都要多挨四个按钮。
 */
@Composable
private fun SubtitleSyncSection(
    state: SubtitleUiState,
    onNudge: (Long) -> Unit,
    onReset: () -> Unit,
) {
    val attached = state.attached != null || state.embeddedTrack != null
    if (!attached || state.displayMode == SubtitleDisplayMode.OFF) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.msp_player_subtitle_sync),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            // 当前值必须一直可见：这个数字是「字幕为什么对不上」的唯一线索，
            // 藏起来就会出现「我什么时候调过它」。
            Text(
                text = stringResource(
                    R.string.msp_player_subtitle_sync_step,
                    formatSubtitleOffset(state.timelineOffsetMs),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = if (state.timelineOffsetMs == 0L) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUBTITLE_SYNC_STEPS.forEach { step ->
                TextButton(onClick = { onNudge(step) }) {
                    Text(
                        stringResource(
                            R.string.msp_player_subtitle_sync_step,
                            formatSubtitleOffset(step),
                        ),
                    )
                }
            }
            TextButton(onClick = onReset, enabled = state.timelineOffsetMs != 0L) {
                Text(stringResource(R.string.msp_player_subtitle_sync_reset))
            }
        }

        Text(
            text = stringResource(R.string.msp_player_subtitle_sync_hint),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CandidateList(
    state: SubtitleUiState,
    onUseAuto: () -> Unit,
    onSelectSource: (SubtitleSource) -> Unit,
    onSelectEmbedded: (MspTrackInfo) -> Unit,
) {
    // 「自动选择」那一行现在有两种可能的赢家（外挂文件 / 内嵌轨），两种都要能报出名字，
    // 否则自动挑了内嵌轨时这一行会显示成「按片名从候选里挑一条最吻合的」——一个
    // 与正在发生的事不符的说明，而真正挂着的那条在列表里看不出选中态。
    val autoPicked = if (state.autoSelected) {
        state.embeddedTrack?.let {
            it.displayLabel(stringResource(R.string.msp_player_embedded_track, it.indexInGroup + 1))
        } ?: state.attached?.fileName
    } else {
        null
    }

    Column {
        SourceRow(
            title = stringResource(R.string.msp_player_auto_select),
            details = autoPicked
                ?.let { MspText.Res(R.string.msp_player_auto_selected, it).string() }
                ?: MspText.Res(R.string.msp_player_auto_pick_desc).string(),
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

        // 内嵌轨放在外挂候选之后，并用一行小标题隔开：这两类是**不同来源**的
        // 字幕，平铺在一个列表里会看起来像同一目录扫出来的多个文件，
        // 而内嵌轨根本没有文件名——用户会去找一个不存在的文件。
        if (state.embeddedTracks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.msp_player_embedded_section),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )

            state.embeddedTracks.forEach { track ->
                SourceRow(
                    title = track.displayLabel(
                        stringResource(R.string.msp_player_embedded_track, track.indexInGroup + 1),
                    ),
                    details = track.describeDetails().string(),
                    selected = !state.autoSelected && state.embeddedTrack?.id == track.id,
                    onClick = { onSelectEmbedded(track) },
                )
            }
        }

        if (showsNoUsableSubtitleHint(state)) {
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
