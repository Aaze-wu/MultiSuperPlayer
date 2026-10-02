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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.translate.FailureText
import com.multisuperplayer.core.translate.Glossary
import com.multisuperplayer.core.translate.TranslationServices
import com.multisuperplayer.core.translate.TranslationTarget
import com.multisuperplayer.core.translate.formatGlossary
import com.multisuperplayer.core.translate.parseGlossary
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
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("字幕翻译") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            item { SectionHeader("服务商") }
            item { ProviderPicker(settings, onSelectProvider) }
            item {
                DraftTextField(
                    key = settings.providerId,
                    stored = settings.baseUrl,
                    onCommit = onSetBaseUrl,
                    label = "服务地址",
                    placeholder = "https://api.deepseek.com",
                    keyboardType = KeyboardType.Uri,
                    supportingText = "以 /v1 结尾的地址会被自动补上 /chat/completions；改这里可以指向你自己的中转。",
                )
            }
            item {
                DraftTextField(
                    key = settings.providerId,
                    stored = settings.model,
                    onCommit = onSetModel,
                    label = "模型名",
                    placeholder = "deepseek-flash",
                    // 模型名写错是最常见的一类：地址对、密钥对、HTTP 200，
                    // 但返回的是「模型不存在」，或者更糟——静默返回别的东西。
                    supportingText = "必须和厂商文档里的名字完全一致，别凭记忆写。",
                    trailing = {
                        TextButton(onClick = onFetchModels, enabled = !modelList.loading) {
                            Text(if (modelList.loading) "拉取中…" else "拉取列表")
                        }
                    },
                )
            }
            if (modelList.failure != null) {
                item { FailureBlock(modelList.failure, prefix = "拉不到模型列表") }
            } else if (modelList.models.isNotEmpty()) {
                item {
                    ModelListBlock(models = modelList.models, current = settings.model) { model ->
                        onSetModel(model)
                    }
                }
            }

            item { SectionHeader("密钥") }
            item { ApiKeyBlock(settings, onSaveApiKey, onClearApiKey) }

            item { SectionHeader("翻译") }
            item { TargetPicker(settings.target, onSetTarget) }
            item {
                SettingsSwitchRow(
                    title = "自动翻译后续字幕",
                    subtitle = "播放到哪就翻到哪，已经翻好的会留在字幕里。关掉也可以随时手动翻。",
                    checked = settings.autoTranslate,
                    onCheckedChange = onSetAutoTranslate,
                )
            }
            item { GlossaryBlock(settings.glossary, onSetGlossary) }

            item { SectionHeader("测试连接") }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "会真的翻一句样板文本。它同时验证地址、密钥、模型名，以及" +
                            "这家厂商收不收我们的请求形状。会消耗一次极少的 token。",
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
                            Text(if (connectionTest.running) "测试中…" else "测试连接")
                        }
                        if (!settings.ready) {
                            Text(
                                text = "还差：${missingConfigNotice(settings)}",
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
                    connectionTest.failure?.let { FailureBlock(it, prefix = "连接失败") }
                }
            }
        }
    }
}

/**
 * 还差哪一项，逐项列出来——「请检查设置」等于什么都没说。
 *
 * 清单本身在 [TranslationSettings.missingItems] 里，和 [TranslationSettings.ready] 同源；
 * 这里只负责拼成一句话，不在本地再写一遍判断条件（写两遍就会写漏一遍）。
 */
private fun missingConfigNotice(settings: TranslationSettings): String =
    settings.missingItems.joinToString("、")

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
            Text(current.displayName, modifier = Modifier.weight(1f))
            Text("切换", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationServices.all.forEach { service ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(service.displayName)
                            if (service.note.isNotBlank()) {
                                Text(
                                    text = service.note,
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
        if (current.note.isNotBlank()) {
            Text(
                text = current.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        }
    }
}

/**
 * 跟着存储走、但用户一开始输入就交给他的输入框。
 *
 * `draft == null` 表示「用户还没动过这一栏」，此时显示存储里的值：
 * 设置是异步读盘的，第一帧拿到的往往是空串，所以初值必须能**晚到**；
 * 而一旦绑死到存储上，每敲一个字符都会写盘、回流、把光标和刚敲的字符冲掉
 * （DataStore 的写是异步的，回流顺序没有保证）。
 *
 * 换了服务商（[key] 变）就丢掉草稿：那时存储里的地址/模型本来就是另一份。
 */
@Composable
private fun DraftTextField(
    key: Any?,
    stored: String,
    onCommit: (String) -> Unit,
    label: String,
    placeholder: String,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    var draft by remember(key) { mutableStateOf<String?>(null) }

    OutlinedTextField(
        value = draft ?: stored,
        onValueChange = {
            draft = it
            onCommit(it)
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        trailingIcon = trailing,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
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
            text = "这家厂商报了 ${models.size} 个模型，点一下就用它：",
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
    var message by remember(settings.providerId) { mutableStateOf<String?>(null) }
    var confirmClear by remember(settings.providerId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (settings.apiKeyStored) "已保存密钥（${settings.provider.displayName}）" else "还没有保存密钥",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!settings.apiKeyRequired) {
            Text(
                text = "这家服务不需要密钥，留空即可。",
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
            label = { Text("API 密钥") },
            placeholder = { Text("粘贴密钥后点「保存」") },
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
                        message = if (stored) "已保存。" else "没有保存（密钥是空的）。"
                        if (stored) input = ""
                    }
                },
                enabled = input.isNotBlank(),
            ) {
                Text("保存")
            }
            if (settings.apiKeyStored) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("删除密钥") }
            }
        }
        message?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            text = "密钥用系统密钥库加密后存在本机，不会写进日志，也不会随设置导出。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("删除密钥？") },
            text = { Text("删除后这家服务就不能翻译了，需要重新粘贴一次密钥。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                    message = "已删除。"
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
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
            Text("译成：${target.label}", modifier = Modifier.weight(1f))
            Text("切换", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationTarget.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.label) },
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
                Text("术语表", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "一行一条。`桐人` = 保持原样，`Guild = 公会` = 固定译法。# 开头是注释。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "编辑")
            }
        }
        if (expanded) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 6,
                label = { Text("术语（每行一条）") },
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
                ) { Text("保存术语表") }
                TextButton(onClick = {
                    text = formatGlossary(glossary)
                    parsed = glossary.size
                }) { Text("还原") }
                Text(
                    text = if (parsed == 0) "当前 0 条" else "当前 $parsed 条",
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
            text = "连通。模型翻出来的是：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "$sample → $translated",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "看到这句话就说明地址、密钥、模型名都是对的，播放页里应该也能翻出来。",
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
            text = "$prefix：${failure.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        failure.hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        failure.raw?.let { raw ->
            TextButton(onClick = { showRaw = !showRaw }) {
                Text(if (showRaw) "收起服务商返回的原文" else "看服务商返回的原文")
            }
            if (showRaw) {
                HorizontalDivider()
                Text(text = raw, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
