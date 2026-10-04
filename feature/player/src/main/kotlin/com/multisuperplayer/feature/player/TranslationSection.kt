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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.translate.SubtitleExportFormat
import com.multisuperplayer.core.translate.SubtitleExportMode
import com.multisuperplayer.core.translate.describeMissingItems
import com.multisuperplayer.core.ui.text.string

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
 *
 * ## 文案为什么是 [MspText] 拼出来的
 *
 * 这些句子里的数字、语言名、模型名都是运行期才知道的，而语序随语言变化。
 * 一旦在拿到资源之前用 `String` 拼好，切语言时这一块就永远是上次那种语言了，
 * 所以这里一律拼 [MspText]，只在最后交给 `Text` 的那一刻才落地成字符串。
 */
@Composable
internal fun TranslationSection(
    translation: TranslationUiState,
    subtitle: SubtitleUiState,
    exportMessage: MspText?,
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
        Text(stringResource(R.string.msp_player_translation), style = MaterialTheme.typography.titleMedium)

        Text(
            text = headerText(translation).string(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            !translation.configured -> NotConfiguredBlock(translation, onOpenSettings)

            subtitle.translatableCount == 0 -> Text(
                text = stringResource(R.string.msp_player_no_translatable),
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
                            text = stringResource(R.string.msp_player_failed_lines, translation.failedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onRetryFailed,
                            enabled = !translation.running,
                        ) { Text(stringResource(R.string.msp_player_retry_only_failed)) }
                    }
                }
                ExportRow(translation, subtitle, onExport)
            }
        }

        exportMessage?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.string(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissExportMessage) {
                    Text(stringResource(R.string.msp_player_got_it))
                }
            }
        }

        if (subtitle.translationUnavailable) {
            Text(
                text = stringResource(R.string.msp_player_translation_missing_shown_original),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 顶上一行：`目标语言：简体中文 · 模型：deepseek-flash（DeepSeek）`。
 *
 * 服务商名放在括号里，是因为前两项是用户自己填的、需要核对，而服务商名
 * 只是「这条设置现在指向哪家」的旁注。
 */
private fun headerText(translation: TranslationUiState): MspText {
    val head = MspText.join(
        SUBTITLE_DETAIL_SEPARATOR,
        listOfNotNull(
            MspText.Res(R.string.msp_player_target_prefix, translation.target.label),
            translation.model.takeIf { it.isNotBlank() }
                ?.let { MspText.Res(R.string.msp_player_with_model, it) },
        ),
    )
    // `（DeepSeek）`：括号的形状本身也跟着语言走（中文全角、英文半角）。
    //
    // `providerName` 是**非空**的（`TranslationUiState.providerName: MspText`），而且
    // 内置的 7 个服务商都给得出名字（认不出的 id 也会回退到默认服务商），所以这里
    // 不再判空——原来那个 `?.` 是它还是可空类型时的残留，编译器会警告它多余，
    // 而留着它反而会让人以为「服务商名可能缺」这件事真的会发生。
    return MspText.Res(R.string.msp_player_wrapped_in_parens, head, translation.providerName)
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
            text = MspText.Res(
                R.string.msp_player_not_configured,
                // 正常不会走到兜底那一支（`configured == false` 时 `missing` 非空）；
                // 真走到了就说明后台的判定和清单分叉了，给一句能读的话而不是空字符串。
                describeMissingItems(translation.missing)
                    .takeIf { translation.missing.isNotEmpty() }
                    ?: MspText.Res(R.string.msp_player_missing_url_fallback),
            ).string(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.msp_player_open_settings)) }
    }
}

@Composable
private fun TranslatedCountRow(
    translation: TranslationUiState,
    subtitle: SubtitleUiState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = MspText.join(
                MspText.Plain(""),
                listOfNotNull(
                    MspText.Res(
                        R.string.msp_player_translated_count,
                        subtitle.translatedCount,
                        subtitle.translatableCount,
                    ),
                    // `（其中人工修正 N 行）` 自带括号，所以这里不需要分隔符。
                    translation.editedCount.takeIf { it > 0 }
                        ?.let { MspText.Res(R.string.msp_player_edited_suffix, it) },
                ),
            ).string(),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (translation.total > 0) {
            Text(
                // 逐段套娃而不是一次写完：每一段都是一个完整的「A，B」句子，
                // 语序由各语言自己的资源决定，不必在代码里假设逗号在前还是在后。
                // 次缓存没命中时不拼那一段：句子里出现一个 0 只是噪音。
                text = translation.runSummary().string(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * `本次：3/10，缓存命中 2，实际请求 4 次`。
 *
 * 缓存命中为 0 时省掉那一段：`本次：3/10，缓存命中 0，实际请求 4 次` 里那个 0
 * 只占地方。两个结构不同的句子用**两个**资源键表达，而不是把括号逗号拼进代码里。
 */
private fun TranslationUiState.runSummary(): MspText {
    val progress = MspText.Res(R.string.msp_player_run_progress, done, total)
    val withCache = if (fromCache > 0) {
        MspText.Res(R.string.msp_player_with_cache, progress, fromCache)
    } else {
        progress
    }
    return MspText.Res(R.string.msp_player_with_requests, withCache, requests)
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
                text = stringResource(R.string.msp_player_translating),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.msp_player_stop)) }
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = onTranslateAll,
            enabled = translation.configured && !translation.running,
        ) { Text(stringResource(R.string.msp_player_translate_all)) }
        TextButton(
            onClick = onTranslateUpTo,
            enabled = translation.configured && !translation.running,
        ) { Text(stringResource(R.string.msp_player_translate_up_to)) }
        if (translation.failedCount > 0) {
            TextButton(onClick = onRetryFailed) {
                Text(stringResource(R.string.msp_player_retry_failed_n, translation.failedCount))
            }
        }
    }
}

/** 失败块：结论 + 下一步 + 可展开的厂商原文。 */
@Composable
private fun FailureBlock(message: MspText, hint: MspText?, raw: String?) {
    var showRaw by remember(raw) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = message.string(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        hint?.let {
            Text(
                text = it.string(),
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
                Text(
                    stringResource(
                        if (showRaw) R.string.msp_player_hide_raw else R.string.msp_player_show_raw,
                    ),
                )
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
        ) { Text(stringResource(R.string.msp_player_export)) }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SubtitleExportFormat.entries.forEach { format ->
                SubtitleExportMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        // 格式名（`ASS`/`SRT`）是扩展名大写，与语言无关，所以不走资源。
                        text = {
                            Text(
                                MspText.join(
                                    SUBTITLE_DETAIL_SEPARATOR,
                                    listOf(mode.label, MspText.Plain(format.extension.uppercase())),
                                ).string(),
                            )
                        },
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
