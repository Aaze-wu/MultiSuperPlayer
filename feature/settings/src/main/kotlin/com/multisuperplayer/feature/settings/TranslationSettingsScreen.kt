package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.SubtitleBottomMargin
import com.multisuperplayer.core.data.settings.SubtitleLineSpacing
import com.multisuperplayer.core.data.settings.SubtitleOutline
import com.multisuperplayer.core.data.settings.SubtitleStyle
import com.multisuperplayer.core.data.settings.SubtitleTextSize
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.translate.FailureText
import com.multisuperplayer.core.translate.Glossary
import com.multisuperplayer.core.translate.TranslationServices
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.describeMissingItems
import com.multisuperplayer.core.translate.formatGlossary
import com.multisuperplayer.core.translate.parseGlossary
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 翻译设置。从设置页（或播放页字幕面板的「去设置」）进来。
 *
 * ## 这一页为什么比「填四个框」长
 *
 * 翻译翻不出来时，用户手里没有任何线索：HTTP 200、字幕不动、日志他也看不到。
 * 所以这一页必须自己承担「诊断」的职责，把三类失败分清楚：
 *
 * 1. **没配好**（地址/模型/密钥）⇒ 用 [TranslationSettings.ready] 提前显示还差什么；
 * 2. **配好了但厂商不认**（密钥过期、跨地域、余额不足）⇒ 「测试连接」把服务商返回的
 *    原文带出来，用 [FailureText] 说人话；
 * 3. **能连但翻不出来**（模型名写错、推理模式吃光预算）⇒ 只有真的跑一次翻译才知道，
 *    所以「测试连接」跑的是真流程，并把模型翻出来的那一句显示出来当证据。
 */
@Composable
fun TranslationSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.translation.collectAsStateWithLifecycle()
    val connectionTest by viewModel.connectionTest.collectAsStateWithLifecycle()
    val modelList by viewModel.modelList.collectAsStateWithLifecycle()
    val subtitle by viewModel.subtitle.collectAsStateWithLifecycle()

    TranslationSettingsScreen(
        settings = settings,
        connectionTest = connectionTest,
        modelList = modelList,
        onBack = onBack,
        onSelectProvider = viewModel::selectProvider,
        onSetBaseUrl = viewModel::setBaseUrl,
        onSetModel = viewModel::setModel,
        onSetTarget = viewModel::setTarget,
        onSetAutoTranslate = viewModel::setAutoTranslate,
        onSetGlossary = viewModel::setGlossary,
        onSaveApiKey = viewModel::saveApiKey,
        onClearApiKey = viewModel::clearApiKey,
        onTestConnection = viewModel::testConnection,
        onFetchModels = viewModel::fetchModels,
        // 外观和翻译在同一个仓库的两个键上，所以两段共用这一个 ViewModel，
        // 不需要为「字幕长什么样」再造一个。
        subtitleStyle = subtitle.style,
        onSetSubtitleTextSize = viewModel::setSubtitleTextSize,
        onSetSubtitleLineSpacing = viewModel::setSubtitleLineSpacing,
        onSetSubtitleOutline = viewModel::setSubtitleOutline,
        onSetSubtitleBottomMargin = viewModel::setSubtitleBottomMargin,
        onResetSubtitleStyle = viewModel::resetSubtitleStyle,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationSettingsScreen(
    settings: TranslationSettings,
    connectionTest: ConnectionTestState,
    modelList: ModelListState,
    onBack: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onSetTarget: (TranslationTarget) -> Unit,
    onSetAutoTranslate: (Boolean) -> Unit,
    onSetGlossary: (Glossary) -> Unit,
    onSaveApiKey: (String, (Boolean) -> Unit) -> Unit,
    onClearApiKey: () -> Unit,
    onTestConnection: () -> Unit,
    onFetchModels: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleStyle: SubtitleStyle = SubtitleStyle.DEFAULT,
    onSetSubtitleTextSize: (SubtitleTextSize) -> Unit = {},
    onSetSubtitleLineSpacing: (SubtitleLineSpacing) -> Unit = {},
    onSetSubtitleOutline: (SubtitleOutline) -> Unit = {},
    onSetSubtitleBottomMargin: (SubtitleBottomMargin) -> Unit = {},
    onResetSubtitleStyle: () -> Unit = {},
) {
    // 当前打开的字幕样式对话框（null = 没开）。
    //
    // 用「当前值 + 点开选」而不是像播放页的字幕面板那样把档位排成芯片：那边是
    // 「一边看画面一边连点着试」，所以每多一次点击都是代价；这里是「先把偏好配好」，
    // 一屏能看完四项比少点一下更重要——四排芯片摆开之后，反而要事先弄清楚
    // 「这一排是字号还是行距」。
    var openStyleDialog: SubtitleStyleDialog? by remember { mutableStateOf(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_translation)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.msp_settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { SectionHeader(stringResource(R.string.msp_settings_section_provider)) }
            item { ProviderPicker(settings, onSelectProvider) }
            item {
                DraftTextField(
                    key = settings.providerId,
                    stored = settings.baseUrl,
                    onCommit = onSetBaseUrl,
                    label = stringResource(R.string.msp_settings_base_url),
                    placeholder = "https://api.deepseek.com",
                    keyboardType = KeyboardType.Uri,
                    supportingText = stringResource(R.string.msp_settings_base_url_support),
                )
            }
            item {
                DraftTextField(
                    key = settings.providerId,
                    stored = settings.model,
                    onCommit = onSetModel,
                    label = stringResource(R.string.msp_settings_model_name),
                    placeholder = "deepseek-flash",
                    // 模型名写错是最常见的一类：地址对、密钥对、HTTP 200，
                    // 但返回的是「模型不存在」，或者更糟——静默返回别的东西。
                    supportingText = stringResource(R.string.msp_settings_model_support),
                    trailing = {
                        TextButton(onClick = onFetchModels, enabled = !modelList.loading) {
                            Text(
                                if (modelList.loading) stringResource(R.string.msp_settings_fetching)
                                else stringResource(R.string.msp_settings_fetch_models),
                            )
                        }
                    },
                )
            }
            if (modelList.failure != null) {
                item {
                    FailureBlock(
                        failure = modelList.failure,
                        prefix = stringResource(R.string.msp_settings_fetch_failed_prefix),
                    )
                }
            } else if (modelList.models.isNotEmpty()) {
                item {
                    ModelListBlock(models = modelList.models, current = settings.model) { model ->
                        onSetModel(model)
                    }
                }
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_api_key)) }
            item { ApiKeyBlock(settings, onSaveApiKey, onClearApiKey) }

            item { SectionHeader(stringResource(R.string.msp_settings_section_translate)) }
            item { TargetPicker(settings.target, onSetTarget) }
            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.msp_settings_auto_translate),
                    subtitle = stringResource(R.string.msp_settings_auto_translate_desc),
                    checked = settings.autoTranslate,
                    onCheckedChange = onSetAutoTranslate,
                )
            }
            item { GlossaryBlock(settings.glossary, onSetGlossary) }

            item { SectionHeader(stringResource(R.string.msp_settings_section_test)) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.msp_settings_test_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onTestConnection,
                            // 没配齐就不给点：点了必然拿到「还差 XX」，不如提前说。
                            enabled = settings.ready && !connectionTest.running,
                        ) {
                            Text(
                                if (connectionTest.running) stringResource(R.string.msp_settings_test_running)
                                else stringResource(R.string.msp_settings_test_button),
                            )
                        }
                        if (!settings.ready) {
                            Text(
                                text = stringResource(
                                    R.string.msp_settings_missing_prefix,
                                    missingConfigNotice(settings).string(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (connectionTest.running) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    connectionTest.translated?.let { translated ->
                        ConnectionOkBlock(sample = connectionTest.sample.orEmpty(), translated = translated)
                    }
                    connectionTest.failure?.let {
                        FailureBlock(
                            failure = it,
                            prefix = stringResource(R.string.msp_settings_connection_failed_prefix),
                        )
                    }
                }
            }

            // 字幕外观放在**最后**：这一页的两个入口里，一个是设置页的「字幕与翻译」，
            // 另一个是播放页字幕面板里翻译没配好时的「去设置」——后者带着
            // 「我要填密钥」进来，把外观摆到他第一眼看到的地方等于让他多绕一步。
            // 而上面服务商 → 密钥 → 翻译 → 测试连接是一条完整的诊断链，也不该被切断。
            item { SectionHeader(stringResource(R.string.msp_settings_section_subtitle_style)) }
            item {
                SettingChoiceRow(
                    icon = Icons.Outlined.FormatSize,
                    title = stringResource(R.string.msp_settings_subtitle_size),
                    value = subtitleStyle.textSize.label.string(),
                    subtitle = stringResource(R.string.msp_settings_subtitle_size_desc),
                    onClick = { openStyleDialog = SubtitleStyleDialog.TEXT_SIZE },
                )
            }
            item {
                SettingChoiceRow(
                    icon = Icons.Outlined.FormatLineSpacing,
                    title = stringResource(R.string.msp_settings_subtitle_line_spacing),
                    value = subtitleStyle.lineSpacing.label.string(),
                    subtitle = stringResource(R.string.msp_settings_subtitle_line_spacing_desc),
                    onClick = { openStyleDialog = SubtitleStyleDialog.LINE_SPACING },
                )
            }
            item {
                SettingChoiceRow(
                    icon = Icons.Outlined.FormatColorText,
                    title = stringResource(R.string.msp_settings_subtitle_outline),
                    value = subtitleStyle.outline.label.string(),
                    subtitle = stringResource(R.string.msp_settings_subtitle_outline_desc),
                    onClick = { openStyleDialog = SubtitleStyleDialog.OUTLINE },
                )
            }
            item {
                SettingChoiceRow(
                    icon = Icons.Outlined.VerticalAlignBottom,
                    title = stringResource(R.string.msp_settings_subtitle_margin),
                    value = subtitleStyle.bottomMargin.label.string(),
                    subtitle = stringResource(R.string.msp_settings_subtitle_margin_desc),
                    onClick = { openStyleDialog = SubtitleStyleDialog.BOTTOM_MARGIN },
                )
            }
            item {
                // 四个档位的默认值**不是**同一类值（字号默认「标准」、描边默认「无」），
                // 所以「恢复默认」不能让用户自己去猜是哪几项。已经全是默认值时置灰：
                // 按下去不会有任何变化的按钮，按下去只会让人怀疑「是不是没生效」。
                val isDefault = subtitleStyle == SubtitleStyle.DEFAULT
                SettingActionRow(
                    icon = Icons.Outlined.Restore,
                    title = stringResource(R.string.msp_settings_subtitle_reset),
                    subtitle = stringResource(
                        if (isDefault) R.string.msp_settings_subtitle_reset_already
                        else R.string.msp_settings_subtitle_reset_desc,
                    ),
                    onClick = onResetSubtitleStyle,
                    enabled = !isDefault,
                )
            }
        }
    }

    // 对话框画在 `Scaffold` 外面，而不是塞进 `LazyColumn` 的 item 里：
    // 放进 item 的话它会随列表滚走，而对话框是浮层，本就不该有自己的滚动位置。
    //
    // 四组都没有 `description`：档位名（小/标准/大/特大、无/细/标准/粗）就是全部
    // 需要知道的信息，再写一句「比标准小一点」只是把同一件事说两遍。
    when (openStyleDialog) {
        SubtitleStyleDialog.TEXT_SIZE -> ChoiceDialog(
            title = stringResource(R.string.msp_settings_subtitle_size),
            options = SubtitleTextSize.entries,
            selected = subtitleStyle.textSize,
            label = { it.label.string() },
            description = { null },
            onSelect = { size ->
                onSetSubtitleTextSize(size)
                openStyleDialog = null
            },
            onDismiss = { openStyleDialog = null },
        )

        SubtitleStyleDialog.LINE_SPACING -> ChoiceDialog(
            title = stringResource(R.string.msp_settings_subtitle_line_spacing),
            options = SubtitleLineSpacing.entries,
            selected = subtitleStyle.lineSpacing,
            label = { it.label.string() },
            description = { null },
            onSelect = { spacing ->
                onSetSubtitleLineSpacing(spacing)
                openStyleDialog = null
            },
            onDismiss = { openStyleDialog = null },
        )

        SubtitleStyleDialog.OUTLINE -> ChoiceDialog(
            title = stringResource(R.string.msp_settings_subtitle_outline),
            options = SubtitleOutline.entries,
            selected = subtitleStyle.outline,
            label = { it.label.string() },
            description = { null },
            onSelect = { outline ->
                onSetSubtitleOutline(outline)
                openStyleDialog = null
            },
            onDismiss = { openStyleDialog = null },
        )

        SubtitleStyleDialog.BOTTOM_MARGIN -> ChoiceDialog(
            title = stringResource(R.string.msp_settings_subtitle_margin),
            options = SubtitleBottomMargin.entries,
            selected = subtitleStyle.bottomMargin,
            label = { it.label.string() },
            description = { null },
            onSelect = { margin ->
                onSetSubtitleBottomMargin(margin)
                openStyleDialog = null
            },
            onDismiss = { openStyleDialog = null },
        )

        null -> Unit
    }
}

/** 字幕样式页上会弹出的选择对话框。 */
private enum class SubtitleStyleDialog { TEXT_SIZE, LINE_SPACING, OUTLINE, BOTTOM_MARGIN }

/**
 * 还差哪一项，逐项列出来——「请检查设置」等于什么都没说。
 *
 * 清单本身在 [TranslationSettings.missingItems] 里，和 [TranslationSettings.ready] 同源；
 * 这里只负责拼成一句话，不在本地再写一遍判断条件（写两遍就会写漏一遍）。
 */
private fun missingConfigNotice(settings: TranslationSettings): MspText =
    describeMissingItems(settings.missingItems)

@Composable
private fun ProviderPicker(settings: TranslationSettings, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = settings.provider

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(current.displayName.string(), modifier = Modifier.weight(1f))
            Text(stringResource(R.string.msp_settings_switch), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationServices.all.forEach { service ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(service.displayName.string())
                            val note = service.note.string()
                            if (note.isNotBlank()) {
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(service.id)
                    },
                )
            }
        }
        val currentNote = current.note.string()
        if (currentNote.isNotBlank()) {
            Text(
                text = currentNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun ModelListBlock(models: List<String>, current: String, onPick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.msp_settings_model_count, models.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        models.forEach { model ->
            TextButton(onClick = { onPick(model) }) {
                Text(
                    text = if (model == current) "✓ $model" else model,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * 密钥的三态。
 *
 * 「留空」和「删除」必须分开：一个输入框的空值如果等于「把密钥删掉」，
 * 那么用户只是点进去、什么都没输、退回上一页（或其他任何触发保存的路径），
 * 密钥就没了。所以这里的规则是：
 *
 * - 输入框永远是空的，**不会**把已存的密钥读回来显示（读回来就等于把它明文摊在屏幕上）；
 * - 点「保存」且输入非空 ⇒ 覆盖；
 * - 点「删除」且**只有**这个按钮 ⇒ 删除（带一次确认）。
 */
@Composable
private fun ApiKeyBlock(
    settings: TranslationSettings,
    onSave: (String, (Boolean) -> Unit) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember(settings.providerId) { mutableStateOf("") }
    var message by remember(settings.providerId) { mutableStateOf<MspText?>(null) }
    var confirmClear by remember(settings.providerId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (settings.apiKeyStored) {
                stringResource(R.string.msp_settings_key_stored, settings.provider.displayName.string())
            } else {
                stringResource(R.string.msp_settings_key_missing)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!settings.apiKeyRequired) {
            Text(
                text = stringResource(R.string.msp_settings_key_not_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                message = null
            },
            label = { Text(stringResource(R.string.msp_settings_api_key_label)) },
            placeholder = { Text(stringResource(R.string.msp_settings_api_key_placeholder)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                // 空输入不给点：仓库对空值本来就是「不动」，给一个按下去没反应的按钮
                // 只会让人以为保存成功了。
                onClick = {
                    onSave(input) { stored ->
                        message = MspText.Res(
                            if (stored) R.string.msp_settings_key_saved
                            else R.string.msp_settings_key_not_saved,
                        )
                        if (stored) input = ""
                    }
                },
                enabled = input.isNotBlank(),
            ) {
                Text(stringResource(R.string.msp_settings_save))
            }
            if (settings.apiKeyStored) {
                OutlinedButton(onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.msp_settings_delete_key))
                }
            }
        }
        message?.let {
            Text(
                text = it.string(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.msp_settings_key_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.msp_settings_delete_key_title)) },
            text = { Text(stringResource(R.string.msp_settings_delete_key_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                    message = MspText.Res(R.string.msp_settings_key_deleted)
                }) { Text(stringResource(R.string.msp_settings_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.msp_settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun TargetPicker(target: TranslationTarget, onSelect: (TranslationTarget) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.msp_settings_target_prefix, target.label.string()),
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.msp_settings_switch), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationTarget.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.label.string()) },
                    onClick = {
                        expanded = false
                        onSelect(candidate)
                    },
                )
            }
        }
    }
}

/**
 * 术语表。
 *
 * 编辑框里是**文本草稿**，失焦/离开页面时才解析写盘：直接按字写盘会让「Guild =」这种
 * 半成品状态也被存下来（右边空 ⇒ 变成「不翻译」，于是用户打字打到一半，术语表就变了语义）。
 */
@Composable
private fun GlossaryBlock(
    glossary: Glossary,
    onSetGlossary: (Glossary) -> Unit,
) {
    // rememberSaveable：切到别的页再回来、或者转屏，草稿不该丢。
    var text by rememberSaveable { mutableStateOf(formatGlossary(glossary)) }
    var parsed by remember { mutableStateOf(glossary.size) }
    var expanded by rememberSaveable { mutableStateOf(glossary.isNotEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.msp_settings_glossary), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.msp_settings_glossary_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.msp_settings_glossary_collapse
                        else R.string.msp_settings_glossary_edit,
                    ),
                )
            }
        }
        if (expanded) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 6,
                label = { Text(stringResource(R.string.msp_settings_glossary_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val parsedGlossary = parseGlossary(text)
                        parsed = parsedGlossary.size
                        onSetGlossary(parsedGlossary)
                    },
                ) { Text(stringResource(R.string.msp_settings_glossary_save)) }
                TextButton(onClick = {
                    text = formatGlossary(glossary)
                    parsed = glossary.size
                }) { Text(stringResource(R.string.msp_settings_glossary_restore)) }
                Text(
                    text = stringResource(R.string.msp_settings_glossary_count, parsed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionOkBlock(sample: String, translated: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.msp_settings_test_ok_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "$sample → $translated",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.msp_settings_test_ok_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 失败块：一句人话 + 一个补救方向 +（可展开的）服务商原文。
 *
 * 原文必须留着。它是唯一能分辨「密钥其实没问题、是跨地域用错了 key」
 * 这类事故的东西，而一句话的总结永远会丢掉这个信息。
 */
@Composable
private fun FailureBlock(failure: FailureText, prefix: String) {
    var showRaw by remember(failure.raw) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.msp_settings_failure_line, prefix, failure.message.string()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.hint?.let {
            Text(
                text = it.string(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        failure.raw?.let { raw ->
            TextButton(onClick = { showRaw = !showRaw }) {
                Text(
                    stringResource(
                        if (showRaw) R.string.msp_settings_hide_raw
                        else R.string.msp_settings_show_raw,
                    ),
                )
            }
            if (showRaw) {
                HorizontalDivider()
                Text(text = raw, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
