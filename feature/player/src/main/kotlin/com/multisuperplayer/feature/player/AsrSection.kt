package com.multisuperplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.asr.AsrModelProgress
import com.multisuperplayer.core.asr.AsrProgress
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.ui.text.string

/**
 * 字幕面板里的「语音识别」一块：生成字幕（本机识别）。
 *
 * ## 为什么这里只有一个按钮
 *
 * 识别需要一条模型，而模型可能还没下载。把「下模型」和「生成字幕」拆成两个入口，
 * 用户会在「生成字幕」上撞到一个「请先下载模型」的提示，然后不知道该去哪下。
 * 所以这里合成一个动作：模型没装好时按钮自己就说清楚要下多少（`AsrModelInfo.sizeText()`），
 * 点了就是「下载 + 识别」一条路。
 *
 * ## 为什么进度可以只剩文字
 *
 * 下载和识别各有一步拿不到分母（见 [AsrUiState]），这时画不确定态进度条，
 * 而**不画一个 0% 的确定态**——后者会让用户盯着一个不动的条以为卡死了。
 * 相应地，文字里也只在分母已知时才出现时间。
 */
@Composable
internal fun AsrSection(
    asr: AsrUiState,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    HorizontalDivider()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.msp_player_asr_title),
            style = MaterialTheme.typography.titleMedium,
        )

        Text(
            text = stringResource(R.string.msp_player_asr_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (asr) {
            is AsrUiState.Idle -> IdleBlock(asr, onGenerate)

            is AsrUiState.Downloading -> RunningBlock(
                fraction = asr.progress?.fraction,
                body = downloadText(asr.progress),
                onCancel = onCancel,
            )

            is AsrUiState.Transcribing -> RunningBlock(
                // 分母已知才画确定态：`totalMs == 0`（还在探测音轨）时它算出来的
                // 分数是 0，画成 0% 和「真的还没开始」分不出来。
                fraction = asr.progress?.takeIf { it.isDeterminate }?.fraction,
                body = transcribeText(asr.progress),
                onCancel = onCancel,
            )

            is AsrUiState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = asr.message.string(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissFailure) {
                    Text(stringResource(R.string.msp_player_got_it))
                }
            }
        }
    }
}

@Composable
private fun IdleBlock(state: AsrUiState.Idle, onGenerate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!state.installed) {
            Text(
                // 先把体积说清楚：不打招呼就用流量下几十上百 MB 是另一回事。
                text = MspText.Res(R.string.msp_player_asr_need_model, state.model.sizeText()).string(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onGenerate) {
            if (state.installed) {
                Text(stringResource(R.string.msp_player_asr_generate))
            } else {
                Text(
                    MspText.Res(
                        R.string.msp_player_asr_download_generate,
                        state.model.sizeText(),
                    ).string(),
                )
            }
        }
    }
}

@Composable
private fun RunningBlock(fraction: Float?, body: MspText, onCancel: () -> Unit) {
    if (fraction == null) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = body.string(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.msp_player_stop))
        }
    }
}

/** `已下载 12.3 MB / 78.1 MB`；还没开始写第一个文件时只有一句「正在下载模型」。 */
private fun downloadText(progress: AsrModelProgress?): MspText =
    progress?.let {
        MspText.Res(
            R.string.msp_player_asr_download_bytes,
            TimeFormat.fileSize(it.downloadedBytes),
            TimeFormat.fileSize(it.totalBytes),
        )
    } ?: MspText.Res(R.string.msp_player_asr_downloading)

/** `已识别 12 句 · 00:31 / 04:12`；总时长还不知道时省掉时间那一半。 */
private fun transcribeText(progress: AsrProgress?): MspText = when {
    progress == null -> MspText.Res(R.string.msp_player_asr_recognizing)

    progress.isDeterminate -> MspText.Res(
        R.string.msp_player_asr_progress,
        progress.segmentCount,
        TimeFormat.clock(progress.processedMs),
        TimeFormat.clock(progress.totalMs),
    )

    else -> MspText.Res(R.string.msp_player_asr_progress_unknown, progress.segmentCount)
}
