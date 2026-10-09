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
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteSweep
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
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.AsrService
import com.multisuperplayer.core.asr.AsrServices
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
 * 云端那两栏（地址 / 模型）的记忆键前缀。
 *
 * 与 [SOURCE_FIELD_KEY] 不同，这一份草稿**属于某一家服务商**：换家就是换了一份存储值，
 * 草稿必须跟着作废。键里带上服务商 id，就是上面那句「这个键是这一栏属于哪份记录的
 * 标识」的具体做法——如果只用一个常量，用户在 A 家的框里打了一半、又去切到 B 家，
 * 框里还会留下 A 家的半截地址，而请求已经发去 B 家。
 */
private const val CLOUD_FIELD_KEY_PREFIX = "asr-cloud-"

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
        onSelectRoute = viewModel::selectRoute,
        onSelectCloudService = viewModel::selectCloudService,
        onSetCloudBaseUrl = viewModel::setCloudBaseUrl,
        onSetCloudModel = viewModel::setCloudModel,
        onSaveCloudApiKey = viewModel::saveCloudApiKey,
        onClearCloudApiKey = viewModel::clearCloudApiKey,
        onSelectModel = viewModel::selectModel,
        onSetBaseUrl = viewModel::setBaseUrl,
        onInstall = viewModel::install,
        onCancelInstall = viewModel::cancelInstall,
        onRemove = viewModel::remove,
        onClearCache = viewModel::clearGeneratedSubtitleCache,
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
    onSelectRoute: (AsrRoute) -> Unit,
    onSelectCloudService: (String) -> Unit,
    onSetCloudBaseUrl: (String) -> Unit,
    onSetCloudModel: (String) -> Unit,
    onSaveCloudApiKey: (String, (Boolean) -> Unit) -> Unit,
    onClearCloudApiKey: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onInstall: () -> Unit,
    onCancelInstall: () -> Unit,
    onRemove: () -> Unit,
    onClearCache: () -> Unit = {},
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 删除要先确认：这条路要走 190 MB 的流量，而且删掉的模型不走回收站
    // （见 `AsrModelLocator.remove`）。确认放在这一层而不是 ViewModel 里：
    // 「用户点过确认」是界面的事实，ViewModel 不该再猜一次。
    var confirmRemove by remember { mutableStateOf(false) }

    // 清字幕也要确认，但理由与上面**不同**：不是因为代价大（那些字幕本来就随时可以
    // 再跑一遍），而是因为用户要花掉的是**几分钟的算力**，而按下去之后界面上什么都不会
    // 变（字幕列表在另一个页面）。不确认的话，误触一次就是几分钟白等。
    var confirmClearCache by remember { mutableStateOf(false) }

    // 当前是不是开着「选服务商」的对话框。与 `confirmRemove` 同一个理由留在这里。
    var pickingService by remember { mutableStateOf(false) }

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
            // 识别方式排在最前面：它决定的不是「哪个更好」，而是下面半页要填什么。
            // 反过来把模型列表放前面的话，一个打算用云端的用户要先把两行本机模型读完
            // （连同 190 MB 的体积）才看到那个真正属于他的选项。
            item { SectionHeader(stringResource(R.string.msp_settings_section_asr_route)) }
            items(AsrRoute.entries, key = { route -> route.id }) { route ->
                AsrRouteRow(
                    route = route,
                    selected = route == state.settings.route,
                    // 下载中不许换路：换到云端会把正在下的那个任务的进度条从屏幕上拿掉，
                    // 而后台还在下——「看不见的 190 MB」比一个点不动的选项更难查。
                    enabled = !state.installing,
                    onSelect = { onSelectRoute(route) },
                )
            }
            item {
                // 两条路各说各的代价，**没有**一句共用的免责声明：一句「音频不会上传」
                // 在云端那条路上是假话（见 `AsrSection` 里同一条取舍）。
                InfoNote(
                    stringResource(
                        if (state.settings.usesCloud) {
                            R.string.msp_settings_asr_route_cloud_note
                        } else {
                            R.string.msp_settings_asr_model_hint
                        },
                    ),
                )
            }

            if (state.settings.usesCloud) {
                item { SectionHeader(stringResource(R.string.msp_settings_section_asr_cloud)) }
                item {
                    CloudServiceRow(
                        service = state.settings.cloudService,
                        onClick = { pickingService = true },
                    )
                }
                item { CloudEndpointField(state = state, onSetCloudBaseUrl = onSetCloudBaseUrl) }
                item { CloudModelField(state = state, onSetCloudModel = onSetCloudModel) }

                item { SectionHeader(stringResource(R.string.msp_settings_section_asr_cloud_key)) }
                item {
                    SecretKeyBlock(
                        // 换家就换一份凭证：草稿和刚才那条提示必须跟着作废（见 `SecretKeyBlock`）。
                        resetKey = state.settings.cloudApiKeyOwner,
                        ownerLabel = state.settings.cloudService.displayName.string(),
                        stored = state.settings.cloudApiKeyStored,
                        required = state.settings.cloudNeedsApiKey,
                        clearConfirm = stringResource(R.string.msp_settings_asr_delete_key_confirm),
                        onSave = onSaveCloudApiKey,
                        onClear = onClearCloudApiKey,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                item { SectionHeader(stringResource(R.string.msp_settings_section_asr_model)) }
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
            }

            // 生成字幕的缓存排在这一页的最后，而且**不在**上面那个分叉里：两路写的是同一批
            // 文件（文件名是媒体哈希，与路线无关——见 `AsrSubtitleGenerator`），
            // 所以选云端时这一行照样该在，否则一个用云端的用户永远看不到自己付过的钱
            // 积在磁盘上的那块地方。
            //
            // 「模型文件不在这里」也必须在标题上就看出来：离它最近的那个删除按钮删的是模型。
            item { SectionHeader(stringResource(R.string.msp_settings_section_asr_cache)) }
            item {
                val stats = state.cache.stats
                // 没缓存时**不显示按钮**，而不是把按钮置灰：按下去不会有任何变化的按钮，
                // 唯一的作用是让人怀疑「是不是没生效」。副标题照样给一句「还没有缓存」，
                // 让这一行看上去是空的、不是没加载出来。
                SettingActionButtonRow(
                    icon = Icons.Outlined.DeleteSweep,
                    title = stringResource(R.string.msp_settings_asr_cache_clear),
                    subtitle = when {
                        state.cache.busy -> stringResource(R.string.msp_settings_asr_cache_clearing)
                        // 失败时说的是「还剩多少」，而不是「清空失败」四个字：
                        // 后者会让人以为已经清掉了（而字幕还在，磁盘也没被释放）。
                        state.cache.failed -> stats.describeClearFailure().string()
                        else -> stats.describe().string()
                    },
                    action = if (stats.isEmpty || state.cache.busy) {
                        null
                    } else {
                        stringResource(R.string.msp_settings_asr_cache_clear_action)
                    },
                    onAction = { confirmClearCache = true },
                    help = stringResource(R.string.msp_settings_asr_cache_help),
                )
            }

            state.message?.let { message ->
                item { AsrMessageBlock(message = message, onDismiss = onDismissMessage) }
            }
        }
    }

    if (pickingService) {
        ChoiceDialog(
            title = stringResource(R.string.msp_settings_asr_cloud_service),
            options = AsrServices.all,
            selected = state.settings.cloudService,
            label = { service -> service.displayName.string() },
            description = { service -> service.note.string() },
            onSelect = { service ->
                pickingService = false
                onSelectCloudService(service.id)
            },
            onDismiss = { pickingService = false },
        )
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

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text(stringResource(R.string.msp_settings_asr_cache_clear_title)) },
            // 确认句里三件事都要有：删多少、要重付什么代价、什么不会被删
            // （见 `GeneratedSubtitleCacheStats.describeClearConfirmation`）。
            text = { Text(state.cache.stats.describeClearConfirmation().string()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCache = false
                        onClearCache()
                    },
                ) {
                    Text(stringResource(R.string.msp_settings_asr_cache_clear_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) {
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

// --------------------------------------------------------------- 识别方式

/**
 * 「本机 / 云端」两行。
 *
 * 每行都带自己那句代价说明：本机是「音频不会上传 + 大概要等多久」，云端是「5 分钟一块
 * 上传、传完才开始」。两条路的代价**不能合并成一句**——这正是播放页那条 hint 被下移
 * 到各自块里的同一个理由，也是这个功能里最容易写错的一句话。
 *
 * 名字用 [AsrRoute.displayName]（core:asr 里那份，三语齐全），不在这一层再抄一份：
 * 抄一份之后「本机识别」在设置页和播放页就可能叫两个名字。
 */
@Composable
private fun AsrRouteRow(
    route: AsrRoute,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
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
        headlineContent = {
            Text(
                text = route.displayName.string(),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Unspecified
                },
            )
        },
    )
}

// --------------------------------------------------------------- 云端

/**
 * 服务商那一行：右边是当前这家（不出时间轴时带一个短徽标），下面是这家的说明。
 *
 * 说明直接铺在行上，而不是只藏在选择对话框里：默认服务商就是不出时间轴的那家，
 * 也就是说「用户什么都没点」的状态下，这条信息必须已经在屏幕上。
 */
@Composable
private fun CloudServiceRow(
    service: AsrService,
    onClick: () -> Unit,
) {
    SettingChoiceRow(
        icon = Icons.Outlined.Cloud,
        title = stringResource(R.string.msp_settings_asr_cloud_service),
        value = if (service.supportsSegments) {
            service.displayName.string()
        } else {
            // 徽标由 `supportsSegments` 算出来，不是把各家的说明抄一遍：
            // 抄的那一份会在我们改预设数据之后继续撒谎。
            stringResource(
                R.string.msp_settings_asr_cloud_no_timeline,
                service.displayName.string(),
            )
        },
        subtitle = service.note.string(),
        onClick = onClick,
        help = null,
    )
}

/**
 * 云端服务地址。
 *
 * 两处与下载源那一栏**刻意相反**：
 * - 空串 = 错（那边空 = 用默认镜像）。两个极性各由一个 `isError` 来源给出，
 *   所以这里用 [AsrSettingsUiState.cloudAddressLooksValid] 而不是 `looksLikeHttpUrl`；
 * - 下面那行写的是**真正会请求的完整地址**，因为用户填的是 base，而 404 与
 *   「模型名写错了」在错误提示里长得一模一样，只有把拼好的结果摆出来才分得清。
 */
@Composable
private fun CloudEndpointField(
    state: AsrSettingsUiState,
    onSetCloudBaseUrl: (String) -> Unit,
) {
    DraftTextField(
        // 换家就丢掉草稿：地址是跟着服务商走的一份值。
        key = CLOUD_FIELD_KEY_PREFIX + "base-url-" + state.settings.cloudService.id,
        stored = state.settings.cloudBaseUrl,
        onCommit = onSetCloudBaseUrl,
        label = stringResource(R.string.msp_settings_asr_cloud_address),
        placeholder = stringResource(R.string.msp_settings_asr_cloud_address_placeholder),
        keyboardType = KeyboardType.Uri,
        supportingText = if (state.cloudAddressLooksValid) {
            stringResource(R.string.msp_settings_asr_cloud_endpoint, state.cloudEndpoint)
        } else {
            stringResource(R.string.msp_settings_asr_cloud_address_invalid)
        },
        isError = !state.cloudAddressLooksValid,
    )
}

/**
 * 云端模型名。留空 = 回到预设自带的那个（与下载源同一套语义）。
 *
 * 框里显示的是**解析后**的值（留空时就是预设名），所以「留空」这件事不能用空框
 * 表达；下面那行把它写出来，用户才知道自己看到的是预设还是自己填的。
 */
@Composable
private fun CloudModelField(
    state: AsrSettingsUiState,
    onSetCloudModel: (String) -> Unit,
) {
    val preset = state.settings.cloudService.model
    DraftTextField(
        key = CLOUD_FIELD_KEY_PREFIX + "model-" + state.settings.cloudService.id,
        stored = state.settings.cloudModel,
        onCommit = onSetCloudModel,
        label = stringResource(R.string.msp_settings_asr_cloud_model),
        placeholder = preset,
        keyboardType = KeyboardType.Text,
        supportingText = stringResource(R.string.msp_settings_asr_cloud_model_support, preset),
    )
}
