package com.multisuperplayer.feature.settings

import android.app.ActivityManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.llm.DEFAULT_LLM_MODEL_BASE_URL
import com.multisuperplayer.core.llm.LlmInstallProgress
import com.multisuperplayer.core.llm.LlmMemoryAdvice
import com.multisuperplayer.core.llm.LlmModelCatalog
import com.multisuperplayer.core.llm.LlmModelInfo
import com.multisuperplayer.core.llm.LlmModelStatus
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 下载源那一栏的记忆键。同 `AsrSettingsScreen.SOURCE_FIELD_KEY`：
 * 这一页只有一份地址，所以给一个常量（传 `null` 也能用，但常量更明确）。
 */
private const val SOURCE_FIELD_KEY = "local-model-base-url"

/**
 * 本地翻译模型（跑在设备上的那个）的下载与删除。从「字幕与翻译」页进来。
 *
 * 为什么不是「字幕与翻译」页里的一块：这一页上的每个动作都要读磁盘、都要跑几十秒
 * （345 MB 的下载），而翻译设置页本来只是一次 DataStore 读取。分出来之后，
 * 「用户只是来改目标语言」这条路上不会碰到任何文件操作。
 *
 * 这里也是本地翻译唯一的自救入口：模型下不动、下了一半想删掉、想换镜像站，
 * 播放页里都做不了。
 */
@Composable
fun LocalModelSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalModelSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    // 整机内存是这台设备的固定事实（不随当前可用内存浮动），所以 `remember(context)`
    // 里读一次就够。用 `availMem` 的话，同一台机器在「刚开机」和「后台一堆应用」两种
    // 状态下会给出相反的结论，而那两次数值差异与模型能不能跑无关。
    val context = LocalContext.current
    val totalMemoryBytes = remember(context) { totalMemoryOf(context) }

    // 一进页面重新问一次磁盘：模型也可能是在别处被下完/被系统清掉的，而这一页显示
    // 的「已下载」是上一帧读到的事实。没有别的通知渠道，只能重问。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LocalModelSettingsScreen(
        state = state,
        totalMemoryBytes = totalMemoryBytes,
        onBack = onBack,
        onSelectModel = viewModel::selectModel,
        onSetSource = viewModel::setSource,
        onInstall = viewModel::install,
        onCancelInstall = viewModel::cancelInstall,
        onRemove = viewModel::remove,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

/**
 * 整机内存；读不到时返回 `0`。
 *
 * 返回 `0` 而不是抛异常或返回一个默认的「8 GB」：[LlmMemoryAdvice.isRisky] 把
 * `<= 0` 当成「不知道」并且**不报警**。而给一个假的大数字（比如「按 8 GB 算」）
 * 会让一台真的跑不动的机器安静地看起来没问题——那是这里最不能犯的错。
 */
private fun totalMemoryOf(context: Context): Long {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.totalMem
}

/**
 * 无 ViewModel 版本，方便预览与单测里直接喂状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelSettingsScreen(
    state: LocalModelUiState,
    /**
     * 整机内存（字节），`0` = 读不到。只用来决定要不要写一句「可能跑不起来」——
     * 不做任何拦截，理由见 [LlmMemoryAdvice]。
     */
    totalMemoryBytes: Long,
    onBack: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSetSource: (String) -> Unit,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemove: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 删除要先确认：模型不走回收站（见 `LlmModelLocator.remove`），删掉就是 345 MB
    // 流量白花。确认放在这一层而不是 ViewModel 里：「用户点过确认」是界面的事实。
    var confirmRemove by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_local_model)) },
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
            item { SectionHeader(stringResource(R.string.msp_settings_section_local_model)) }
            // 「不联网」放在最前面：用户第一个怀疑的是「我的字幕被传到哪去了」。
            item { InfoNote(stringResource(R.string.msp_settings_local_model_hint)) }
            items(LlmModelCatalog.models, key = { model -> model.id }) { model ->
                LocalModelRow(
                    model = model,
                    status = state.statusOf(model),
                    selected = model.id == state.model.id,                    totalMemoryBytes = totalMemoryBytes,                    // 下载中不许换模型：换模型会把正在下的任务掐掉，而「点另一行」这个
                    // 动作看起来完全不像「停止下载」。
                    enabled = !state.installing,
                    onSelect = { onSelectModel(model.id) },
                )
            }
            item {
                LocalModelActions(
                    state = state,
                    onInstall = onInstall,
                    onCancelInstall = onCancelInstall,
                    onRemove = { confirmRemove = true },
                )
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_local_model_source)) }
            item {
                DraftTextField(
                    key = SOURCE_FIELD_KEY,
                    stored = state.source,
                    onCommit = onSetSource,
                    label = stringResource(R.string.msp_settings_local_model_source),
                    placeholder = DEFAULT_LLM_MODEL_BASE_URL,
                    keyboardType = KeyboardType.Uri,
                    supportingText = stringResource(
                        if (state.sourceLooksValid) {
                            R.string.msp_settings_local_model_source_support
                        } else {
                            R.string.msp_settings_local_model_source_invalid
                        },
                    ),
                    isError = !state.sourceLooksValid,
                    // 下载中不改地址：正在跑的那一次已经按旧地址在下，这一栏改的是
                    // 「下一次」——两件事同时发生会让人以为改一下就能换源续传。
                    enabled = !state.installing,
                    help = stringResource(R.string.msp_settings_local_model_source_help),
                )
            }

            state.message?.let { message ->
                item { LocalModelMessageBlock(message = message, onDismiss = onDismissMessage) }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.msp_settings_local_model_remove_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.msp_settings_local_model_remove_confirm_body,
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
 * 模型列表里的一行。文案分三层：`名字 · 体积`（这一行是什么）、说明（它擅长什么）、
 * 状态（现在能不能用）。状态独立一行而不是并进标题里：它会在下载/删除之后变，
 * 位置固定才看得出「刚才那一格变了」。
 */
@Composable
private fun LocalModelRow(
    model: LlmModelInfo,
    status: LlmModelStatus,
    selected: Boolean,
    totalMemoryBytes: Long,
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
                    text = SettingsSummaries.localModelStatus(model, status).string(),
                    style = MaterialTheme.typography.bodySmall,
                    // 「已下载」是要紧的状态（马上就能拿去翻译），给主色；其余是灰字。
                    color = if (status == LlmModelStatus.Ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // 内存需求写在**下载前**：下完之后再说「你机器不够」等于把 1.82 GB
                // 流量花掉之后才告知。`memoryText()` 为 null 时一行都不显示——
                // 上游没给实测值就不猜。
                model.memoryText()?.let { memory ->
                    Text(
                        text = memory.string(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 红字而不是灰字：「可能跑不起来」与「运行需约 2.6 GB 内存」是两类
                // 信息（上面那句是事实，这句是**关于你这台机器的结论**），同一种颜色
                // 会让它在扫读时被略过。这里也不置灰任何按钮。
                if (LlmMemoryAdvice.isRisky(model, totalMemoryBytes)) {
                    Text(
                        text = LlmMemoryAdvice.warningText(totalMemoryBytes).string(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    )
}

/**
 * 当前模型能做的动作：下载 / 重新下载、停止、删除。
 *
 * 三种状态各只有一个「下一步」，不并排摆按钮：「已下载」时不画下载按钮
 * （`LlmModelInstaller.install` 发现自己已经装好会直接返回，那个按钮点了什么都不会
 * 发生，是最坏的一种按钮）；要重下得先删。
 *
 * 注意**没有**「继续下载」：本下载器整文件重下（见 `LlmModelLocator` 的类注释），
 * 所以下了一半时的说法是「重新下载」+ 如实报出已收到的字节数。
 */
@Composable
private fun LocalModelActions(
    state: LocalModelUiState,
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
                // 只有拿到字节数时才画确定的进度条。「还没读到第一个字节」与「正在校验」
                // 都画不确定态：校验那段停在 100% 一动不动，会被读成卡死，
                // 而用户接下来最可能的动作是取消——白下 345 MB（见 `LlmInstallProgress`）。
                if (progress is LlmInstallProgress.Downloading) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = progressText(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCancelInstall) {
                    Text(stringResource(R.string.msp_settings_local_model_stop))
                }
            }

            state.status == LlmModelStatus.Ready ->
                Text(
                    text = stringResource(R.string.msp_settings_local_model_ready_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

            else -> {
                val partial = state.status as? LlmModelStatus.Incomplete
                if (partial != null) {
                    Text(
                        text = stringResource(
                            R.string.msp_settings_local_model_partial_hint,
                            TimeFormat.fileSize(partial.presentBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onInstall) {
                    Text(
                        stringResource(
                            if (partial == null) R.string.msp_settings_local_model_download
                            else R.string.msp_settings_local_model_redownload,
                            TimeFormat.fileSize(state.model.sizeBytes),
                        ),
                    )
                }
            }
        }

        // 只有下过东西才可能出现「删除」：一条一个字节都没有的模型，删它没有意义，
        // 画出来只会让用户怀疑「是不是有什么我没下的东西」。
        if (state.status != LlmModelStatus.Absent) {
            TextButton(onClick = onRemove) {
                Text(
                    stringResource(
                        R.string.msp_settings_local_model_remove,
                        TimeFormat.fileSize(state.occupiedBytes),
                    ),
                )
            }
        }
    }
}

/** 页面底部那条提示。失败的红字、并带一个关闭按钮（见 [LocalModelMessage]）。 */
@Composable
private fun LocalModelMessageBlock(message: LocalModelMessage, onDismiss: () -> Unit) {
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
            Text(stringResource(R.string.msp_settings_local_model_dismiss))
        }
    }
}

/** 进度条下面那一行字：三种进度各有各的说法，见 `LlmInstallProgress`。 */
@Composable
private fun progressText(progress: LlmInstallProgress?): String = when (progress) {
    null -> stringResource(R.string.msp_settings_local_model_downloading)
    is LlmInstallProgress.Downloading -> stringResource(
        R.string.msp_settings_local_model_download_bytes,
        TimeFormat.fileSize(progress.bytes),
        TimeFormat.fileSize(progress.total),
    )
    LlmInstallProgress.Verifying -> stringResource(R.string.msp_settings_local_model_verifying)
}
