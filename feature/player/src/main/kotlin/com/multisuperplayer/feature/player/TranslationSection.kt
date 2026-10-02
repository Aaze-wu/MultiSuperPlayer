package com.multisuperplayer.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.translate.SubtitleExportFormat
import com.multisuperplayer.core.translate.SubtitleExportMode

/**
 * 字幕面板里的「翻译」一块。
 *
 * ## 为什么按钮的可用状态和文案都由状态算出来，而不是由调用方判断
 *
 * 「能不能点」和「为什么不能点」必须是同一个判断的两面。分开写的话，
 * 迟早会出现按钮是灰的、但没有任何一句话解释原因——用户只会以为功能坏了。
 *
 * ## 失败要给「补救方向」，不是「失败原因」
 *
 * [TranslationUiState.failureText] 已经按分支区分了「形状不对（换模型/缩小批次）」
 * 和「预算不够（调大 maxTokens）」这两条相反的路径，这里只负责把它和厂商原文
 * 一起摆出来。厂商原文默认收起：它很长很吓人，但用户要自己解决时只有它有用。
 */
@Composable
internal fun TranslationSection(
    translation: TranslationUiState,
    subtitle: SubtitleUiState,
    exportMessage: String?,
    onTranslateAll: () -> Unit,
    onTranslateUpTo: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenSettings: () -> Unit,
    onExport: (SubtitleExportFormat, SubtitleExportMode) -> Unit,
    onDismissExportMessage: () -> Unit,
) {
    HorizontalDivider()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("翻译", style = MaterialTheme.typography.titleMedium)

        Text(
            text = buildString {
                append("目标语言：").append(translation.target.label)
                if (translation.model.isNotBlank()) {
                    append(" · 模型：").append(translation.model)
                }
                if (translation.providerName.isNotBlank()) {
                    append("（").append(translation.providerName).append("）")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            !translation.configured -> NotConfiguredBlock(translation, onOpenSettings)

            subtitle.translatableCount == 0 -> Text(
                text = "这份字幕里没有可翻译的文本（可能整篇都是注释或空行）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                TranslatedCountRow(translation, subtitle)
                TranslationActions(translation, onTranslateAll, onTranslateUpTo, onCancel, onRetryFailed)
                translation.failureText?.let { FailureBlock(it.message, it.hint, it.raw) }
                if (translation.failures.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "有 ${translation.failedCount} 行没翻出来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onRetryFailed,
                            enabled = !translation.running,
                        ) { Text("只重试这些行") }
                    }
                }
                ExportRow(translation, subtitle, onExport)
            }
        }

        exportMessage?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissExportMessage) { Text("知道了") }
            }
        }

        if (subtitle.translationUnavailable) {
            Text(
                text = "这份字幕没有译文，已按原文显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotConfiguredBlock(
    translation: TranslationUiState,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            // 缺什么就说什么。这三个界面（这里、翻译设置页、设置页汇总行）
            // 的清单来自同一个 `TranslationSettings.missingItems`，不会各说一套。
            text = "还没有配置翻译服务：还差 " +
                translation.missing.joinToString("、").ifEmpty { "完好的服务地址" } +
                "。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSettings) { Text("去设置") }
    }
}

@Composable
private fun TranslatedCountRow(
    translation: TranslationUiState,
    subtitle: SubtitleUiState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = buildString {
                append("已译 ").append(subtitle.translatedCount).append("/").append(subtitle.translatableCount)
                if (translation.editedCount > 0) {
                    append("（其中人工修正 ").append(translation.editedCount).append(" 行）")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
        )

        if (translation.total > 0) {
            Text(
                text = buildString {
                    append("本次：").append(translation.done).append("/").append(translation.total)
                    if (translation.fromCache > 0) append("，缓存命中 ").append(translation.fromCache)
                    append("，实际请求 ").append(translation.requests).append(" 次")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranslationActions(
    translation: TranslationUiState,
    onTranslateAll: () -> Unit,
    onTranslateUpTo: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
) {
    if (translation.running) {
        LinearProgressIndicator(
            progress = { translation.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "正在翻译…可以随时停，已经翻好的会留下。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("停止") }
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = onTranslateAll,
            enabled = translation.configured && !translation.running,
        ) { Text("翻译全文") }
        TextButton(
            onClick = onTranslateUpTo,
            enabled = translation.configured && !translation.running,
        ) { Text("翻译到当前位置") }
        if (translation.failedCount > 0) {
            TextButton(onClick = onRetryFailed) { Text("重试失败的 ${translation.failedCount} 行") }
        }
    }
}

/** 失败块：结论 + 下一步 + 可展开的厂商原文。 */
@Composable
private fun FailureBlock(message: String, hint: String?, raw: String?) {
    var showRaw by remember(raw) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        raw?.takeIf { it.isNotBlank() }?.let {
            if (showRaw) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showRaw = !showRaw }) {
                Text(if (showRaw) "收起服务商返回的原文" else "看服务商返回的原文")
            }
        }
    }
}

/**
 * 导出。
 *
 * 只有真翻出东西来才显示——一份没有任何译文的字幕导出出来的就是原文副本，
 * 摆着这个按钮只会让人以为导出坏了。
 */
@Composable
private fun ExportRow(
    translation: TranslationUiState,
    subtitle: SubtitleUiState,
    onExport: (SubtitleExportFormat, SubtitleExportMode) -> Unit,
) {
    if (subtitle.translatedCount == 0) return
    var expanded by remember { mutableStateOf(false) }

    Column {
        TextButton(
            onClick = { expanded = true },
            enabled = !translation.running,
            modifier = Modifier.padding(start = 0.dp),
        ) { Text("导出译文…") }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SubtitleExportFormat.entries.forEach { format ->
                SubtitleExportMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text("${mode.label} · ${format.extension.uppercase()}") },
                        onClick = {
                            expanded = false
                            onExport(format, mode)
                        },
                    )
                }
            }
        }
    }
}
