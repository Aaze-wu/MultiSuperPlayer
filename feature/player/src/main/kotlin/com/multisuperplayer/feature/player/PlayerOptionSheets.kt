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
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.player.MspVideoSize
import com.multisuperplayer.core.player.PlaybackSpeedOptions
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
