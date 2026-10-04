package com.multisuperplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspVideoSize
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.selectedAudioTrack
import com.multisuperplayer.core.ui.text.string

/**
 * 倍速选择。
 *
 * 用 `FilterChip` 一行铺开、可以横向滑动，而不是一条连续滑块：能选的档位是有限的
 * （见 [PlaybackSpeedOptions]），滑块会让用户以为可以取任意值，而实际取到的会被
 * 吸附到最近的档位上——那种「拖了但没变」的感觉比少几个可选项糟糕得多。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.msp_player_speed),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 高亮用 `nearestPreset` 而不是相等比较：设置里可能存着一个不在档位表
                // 里的值（改过档位表、或者从别处写进去），那时候必须有一个是选中的，
                // 否则整行都是未选中状态，用户看不出「现在是几倍速」。
                val selected = PlaybackSpeedOptions.nearestPreset(current)
                PlaybackSpeedOptions.PRESETS.forEach { preset ->
                    FilterChip(
                        selected = preset == selected,
                        onClick = { onSelect(preset) },
                        label = { Text(PlaybackSpeedOptions.format(preset)) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.msp_player_speed_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * 画面比例选择。
 *
 * 每一项都带一句**它是干嘛的**，而不是只给一个词：这四种模式的名字本身不足以
 * 让人分清「适应」和「原始」（在片源分辨率恰好等于屏幕时它们结果一样），
 * 而选错之后画面会明显不对，用户却不知道该选哪个才对。
 *
 * 「原始」那一项还要带上片源分辨率：选它之后画面可能只占屏幕一小块，
 * 把 `1920×1080` 写出来，用户才知道这是**预期的**，而不是播放器坏了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerAspectRatioSheet(
    current: AspectRatioMode,
    videoSize: MspVideoSize,
    onSelect: (AspectRatioMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.msp_player_aspect),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                // 分辨率（`1920×1080`）是数字，与语言无关；只有「未知」要走资源。
                text = stringResource(
                    R.string.msp_player_source_line,
                    VideoFit.sourceLabel(videoSize) ?: MspText.unknown().string(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AspectRatioMode.entries.forEach { mode ->
                val selected = mode == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 用 `selectable` 而不是 `clickable`：它会把 `selected` 语义
                        // 一起交给无障碍服务（TalkBack 会读「已选中」），
                        // RadioButton 的 onClick 传 null 是为了不让点击区域裂成两块。
                        .selectable(selected = selected, onClick = { onSelect(mode) })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = mode.label.string(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Text(
                            text = mode.description.string(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 音轨选择。
 *
 * ## 为什么第一行是「自动」
 *
 * 「自动」不是礼貌性的兜底选项，它是**唯一能表达自适应**的一项：DASH/HLS 的同语言
 * 多码率会让内核在几条轨之间自己切换，那时候「选中了第 2 条」是假的（见
 * `List<MspTrackInfo>.selectedAudioTrack`）。所以只要内核没有明确选中单独一条，
 * 选中的就是这一行。
 *
 * ## 为什么副标题要说声道数
 *
 * 见 [describeAudioDetails]：同语言的多条轨只有声道数能把它们分开。
 *
 * ## 为什么不带「应用/确定」按钮
 *
 * 点一行就切、切完留在面板里：用户要在这里**听**效果（国语配音对不对、5.1 是不是
 * 只有左声道有声音），切完就关掉的话他每次都要重新点开一次。关掉面板由下滑或点外部完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerAudioTrackSheet(
    tracks: List<MspTrackInfo>,
    onSelect: (MspTrackInfo) -> Unit,
    onUseAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.msp_player_audio_track),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val selected = tracks.selectedAudioTrack()
            RadioRow(
                selected = selected == null,
                onClick = onUseAuto,
                title = stringResource(R.string.msp_player_audio_auto),
                detail = null,
            )
            tracks.forEach { track ->
                RadioRow(
                    selected = track.id == selected?.id,
                    onClick = { onSelect(track) },
                    // 「音轨 3」这个兜底名按**容器里的下标**编号，不是列表下标：
                    // 列表里可能只画了音频轨，用户拿这个号去别的播放器里对照时
                    // 说的必须是同一个号。
                    title = track.displayLabel(
                        stringResource(R.string.msp_player_audio_track_n, track.indexInGroup + 1),
                    ),
                    detail = track.describeAudioDetails().string(),
                )
            }
            Text(
                text = stringResource(R.string.msp_player_audio_track_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * 一条单选行：`RadioButton` + 主标题 +（可选的）副标题。
 *
 * 抽出来是因为音轨面板里两种行（「自动」和每条轨）长得一样，只有副标题有没有的
 * 区别；而画面比例那一套的行是 `AspectRatioMode` 驱动的，形状不同，不去强行合并。
 */
@Composable
private fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    detail: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 用 `selectable` 而不是 `clickable`：它会把 `selected` 语义一起交给
            // 无障碍服务（TalkBack 会读「已选中」），RadioButton 的 onClick 传 null
            // 是为了不让点击区域裂成两块。
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
