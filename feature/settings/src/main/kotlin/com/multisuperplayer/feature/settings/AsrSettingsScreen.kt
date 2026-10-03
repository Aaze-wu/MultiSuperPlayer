package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrModelProgress
import com.multisuperplayer.core.asr.AsrModelStatus
import com.multisuperplayer.core.asr.DEFAULT_MODEL_BASE_URL
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 下载源那一栏的记忆键。
 *
 * [DraftTextField] 用 `key` 判断「换了一份存储值，草稿该丢掉」；这一页只有一份地址，
 * 所以给一个常量。传 `null` 也能用（等于「永远不重置」），但传常量更明确：
 * 将来真出现「多套下载源」时，改这一行就能让草稿跟着换。
 */
private const val SOURCE_FIELD_KEY = "asr-model-base-url"

/**
 * 语音识别设置。从设置页进来。
 *
 * 页面本身很短（两条模型 + 一个地址），但它在整条链路上是**唯一的自救入口**：
 * 播放页那个「生成字幕」按钮不提供任何可选项，模型下不动、想换一条、下错了想删，
 * 都只能在这里做。
 */
@Composable
fun AsrSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AsrSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 模型也可能是在播放页的字幕面板里下完的。那个动作发生在这个 ViewModel 之外
    // （那边用的是自己的 `SubtitleViewModel`），除了一进页面重新问一次磁盘，
    // 没有别的办法知道——不刷新的话，用户会看到「未下载」而模型其实已经躺在那儿了。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AsrSettingsScreen(
        state = state,
        onBack = onBack,
        onSelectModel = viewModel::selectModel,
        onSetBaseUrl = viewModel::setBaseUrl,
        onInstall = viewModel::install,
        onCancelInstall = viewModel::cancelInstall,
        onRemove = viewModel::remove,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

/**
 * 无 ViewModel 版本，方便预览和单测里直接喂状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrSettingsScreen(
    state: AsrSettingsUiState,
    onBack: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemove: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 删除要先确认：这条路要走 190 MB 的流量，而且删掉的模型不走回收站
    // （见 `AsrModelLocator.remove`）。确认放在这一层而不是 ViewModel 里：
    // 「用户点过确认」是界面的事实，ViewModel 不该再猜一次。
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_asr)) },
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
            modifier = Modifier.fillMaxWidth().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item { SectionHeader(stringResource(R.string.msp_settings_section_asr_model)) }
            // 「音频不上传」是这条功能的卖点，也是用户第一个会怀疑的事，所以放在最前面。
            item { InfoNote(stringResource(R.string.msp_settings_asr_model_hint)) }
            items(AsrModelCatalog.models, key = { model -> model.id }) { model ->
                AsrModelRow(
                    model = model,
                    status = state.statusOf(model),
                    selected = model.id == state.model.id,
                    // 下载中不许换模型：换模型会把正在下的任务掐掉，而「点另一行」
                    // 这个动作看起来完全不像「停止下载」。
                    enabled = !state.installing,
                    onSelect = { onSelectModel(model.id) },
                )
            }
            item {
                AsrActions(
                    state = state,
                    onInstall = onInstall,
                    onCancelInstall = onCancelInstall,
                    onRemove = { confirmRemove = true },
                )
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_asr_source)) }
            item {
                DraftTextField(
                    key = SOURCE_FIELD_KEY,
                    stored = state.settings.baseUrl,
                    onCommit = onSetBaseUrl,
                    label = stringResource(R.string.msp_settings_asr_source),
                    placeholder = DEFAULT_MODEL_BASE_URL,
                    keyboardType = KeyboardType.Uri,
                    supportingText = stringResource(
                        if (state.sourceLooksValid) {
                            R.string.msp_settings_asr_source_support
                        } else {
                            R.string.msp_settings_asr_source_invalid
                        },
                    ),
                    isError = !state.sourceLooksValid,
                    // 下载中不改地址：正在跑的那个任务已经按旧地址在下了，
                    // 而这一栏改的是「下一次」——两件事同时发生会让用户以为改一下就换源续传了。
                    enabled = !state.installing,
                    help = stringResource(R.string.msp_settings_asr_source_help),
                )
            }

            state.message?.let { message ->
                item { AsrMessageBlock(message = message, onDismiss = onDismissMessage) }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.msp_settings_asr_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.msp_settings_asr_remove_confirm_body,
                        state.model.name.string(),
                        TimeFormat.fileSize(state.occupiedBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                ) {
                    Text(stringResource(R.string.msp_settings_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.msp_settings_cancel))
                }
            },
        )
    }
}

/**
 * 模型列表里的一行。
 *
 * 文案分三层：`名字 · 体积`（这一行是什么）、说明（它擅长什么）、状态（现在能不能用）。
 * 状态独立一行而不是并进标题里：它会在下载/删除之后变，用户扫的是同一行文字，
 * 位置固定才看得出「刚才那一格变了」。
 */
@Composable
private fun AsrModelRow(
    model: AsrModelInfo,
    status: AsrModelStatus,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        // 整行可选，而不是只有那个小圆点：小屏上点 20dp 的圆点很容易失手。
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        // onClick = null：整行的 selectable 已经负责选择，再挂一次会点一下触发两次。
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        headlineContent = { Text(model.labelWithSize().string()) },
        supportingContent = {
            Column {
                Text(
                    text = model.description.string(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = SettingsSummaries.asrStatus(model, status).string(),
                    style = MaterialTheme.typography.bodySmall,
                    // 「已下载」是要紧的状态（它可以立刻拿去用），给主色；其余是灰字。
                    color = if (status == AsrModelStatus.Ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
    )
}

/**
 * 当前模型能做的动作：下载 / 继续下载、停止、删除。
 *
 * 三种状态各只有一个「下一步」，不并排摆按钮：
 * 「已下载完」时不画下载按钮（`AsrModelInstaller` 发现没有缺的文件会直接返回，
 * 那个按钮点了什么都不发生，是最坏的一种按钮）；要重下先删。
 */
@Composable
private fun AsrActions(
    state: AsrSettingsUiState,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.installing -> {
                val progress = state.progress
                if (progress == null) {
                    // 还没读到第一个字节：画不确定态的进度条，而不是 0%。
                    // 假的 0% 和「刚点下去」一样，和「卡住了」也一模一样。
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = downloadText(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancelInstall) {
                    Text(stringResource(R.string.msp_settings_asr_stop))
                }
            }

            state.status == AsrModelStatus.Ready ->
                Text(
                    text = stringResource(R.string.msp_settings_asr_ready_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            else -> Button(onClick = onInstall) {
                Text(
                    stringResource(
                        if (state.status is AsrModelStatus.Partial) {
                            R.string.msp_settings_asr_resume
                        } else {
                            R.string.msp_settings_asr_download
                        },
                    ),
                )
            }
        }

        // 只有下过东西才可能出现「删除」：一条一个字节都没有的模型，删它没有意义，
        // 画出来只会让用户怀疑「是不是有什么我没下的东西」。
        if (state.status != AsrModelStatus.Absent) {
            TextButton(onClick = onRemove) {
                Text(
                    stringResource(
                        R.string.msp_settings_asr_remove,
                        TimeFormat.fileSize(state.occupiedBytes),
                    ),
                )
            }
        }
    }
}

/** 页面底部那条提示。失败的红字、并带一个关闭按钮（见 [AsrSettingsMessage]）。 */
@Composable
private fun AsrMessageBlock(message: AsrSettingsMessage, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = message.text.string(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.msp_settings_asr_dismiss))
        }
    }
}

@Composable
private fun downloadText(progress: AsrModelProgress?): String = if (progress == null) {
    stringResource(R.string.msp_settings_asr_downloading)
} else {
    stringResource(
        R.string.msp_settings_asr_download_bytes,
        TimeFormat.fileSize(progress.downloadedBytes),
        TimeFormat.fileSize(progress.totalBytes),
    )
}
