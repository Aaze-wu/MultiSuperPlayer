package com.multisuperplayer.feature.settings

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.format.DurationDraft
import com.multisuperplayer.core.common.format.DurationInput
import com.multisuperplayer.core.data.update.UpdateAvailability
import com.multisuperplayer.core.data.update.UpdateChannel
import com.multisuperplayer.core.data.update.UpdateCheckTrigger
import com.multisuperplayer.core.data.update.UpdateCooldown
import com.multisuperplayer.core.data.update.UpdateRelease
import com.multisuperplayer.core.data.update.UpdateSettings
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

/**
 * 「检查更新」页。从设置入口页推上来。
 *
 * ## 为什么设置项（通道 / 令牌）和「检查」挤在同一页
 *
 * 因为它们**只在这一页上有意义**：通道决定「检查」拿回什么，令牌决定「检查」
 * 能不能问。放到另一个子页里，用户会遇到「为什么查不到 beta 版」而那个开关
 * 在两层之外。同页的代价只是这一页长了三行。
 *
 * ## 更新说明为什么直接显示 Markdown 原文
 *
 * 不解析。GitHub 的发行说明是 Markdown，而渲染 Markdown 要引一个库、
 * 一套排版规则和一堆回归面；纯文本在这里是**够用**的——用户要看的是
 * 「这一版改了什么」，`- 修了「上一首」` 这种原始写法一眼也能读。
 *
 * ## [viewModel] 的默认值是给「单独用这一页」留的
 *
 * 正常路径上应用根（`MspApp`）会把它**显式传进来**，于是启动检查、启动弹窗和
 * 这一页共享同一个实例。默认值（`koinViewModel()`）在这里会拿到挂在
 * `NavBackStackEntry` 上的另一个实例，而那一份不知道启动时查到了什么、
 * 也不知道包已经下好了——保留它只是为了让这个函数缺少外部接线时也能单独构造。
 */
@Composable
fun UpdateRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 「安装未知应用」是在系统页面里开的，没有任何回调会通知我们——
    // 和电池优化白名单、所有文件访问是同一类状态。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 进页面顺手查一次。**「自动检查」开关和冷却窗口都在 `UpdateManager.check`
    // 里判**，不在这里判：这一层的 `settings` 首帧是默认值（很可能和用户设的不一样），
    // 拿它做判断等于用「还没读到的设置」替用户作决定。
    // 启动时那一次检查走在前面（见 `UpdateViewModel.checkAtLaunch`），所以这里
    // 绝大多数时候会被节流直接跳过——那是对的，不该为同一个问题问两遍。
    LaunchedEffect(Unit) { viewModel.check(UpdateCheckTrigger.AUTO) }

    LaunchedEffect(viewModel) {
        viewModel.launch.collect { intent ->
            // 目标页面不存在时必须兜住：`resolveActivity` 在部分 ROM 上会漏判，
            // 而 `startActivity` 抛的 `ActivityNotFoundException` 会让应用直接退出。
            // 崩在这里尤其难看——用户正是想修好这个应用才点进来的。
            if (runCatching { context.startActivity(intent) }.isFailure) viewModel.onLaunchFailed()
        }
    }

    UpdateScreen(
        state = state,
        settings = settings,
        buildInfo = viewModel.buildInfo,
        onBack = onBack,
        onCheck = { viewModel.check(UpdateCheckTrigger.MANUAL) },
        onDownload = viewModel::download,
        onIgnore = viewModel::ignore,
        onUndoIgnore = viewModel::undoIgnore,
        onOpenUnknownSource = viewModel::openUnknownSourceSettings,
        onSetChannel = viewModel::setChannel,
        onSetAutoCheck = viewModel::setAutoCheck,
        onSetCheckInterval = viewModel::setCheckInterval,
        onPutToken = viewModel::putToken,
        onClearToken = viewModel::clearToken,
        onDismissFailure = viewModel::dismissFailure,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    state: UpdateUiState,
    settings: UpdateSettings = UpdateSettings(
        channel = UpdateChannel.BETA,
        autoCheck = true,
        ignoredTag = null,
        lastCheckAtEpochMs = null,
        hasToken = false,
        checkIntervalMs = UpdateCooldown.DEFAULT_MS,
    ),
    buildInfo: AppBuildInfo = AppBuildInfo.Unknown,
    onBack: () -> Unit,
    onCheck: () -> Unit = {},
    onDownload: () -> Unit = {},
    onIgnore: () -> Unit = {},
    onUndoIgnore: () -> Unit = {},
    onOpenUnknownSource: () -> Unit = {},
    onSetChannel: (UpdateChannel) -> Unit = {},
    onSetAutoCheck: (Boolean) -> Unit = {},
    onSetCheckInterval: (Long) -> Unit = {},
    onPutToken: (String) -> Unit = {},
    onClearToken: () -> Unit = {},
    onDismissFailure: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showChannelDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_update)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.msp_settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader(stringResource(R.string.msp_update_section_current)) }

            item {
                SettingActionButtonRow(
                    icon = Icons.Outlined.Refresh,
                    title = stringResource(R.string.msp_update_check_now),
                    // 「当前版本」跟状态写在同一行：这一页最基础的两个问题就是
                    // 「我装的是几」和「有没有新的」，它们分占两行只会让
                    // 用户把其中一行当成装饰。
                    subtitle = UpdateSummaries.statusLine(state.availability, buildInfo.versionName).string(),
                    // 检查中把按钮文字换掉而不是禁用：禁用之后按钮变灰、
                    // 而这一页上方没有任何别的东西在动，用户会以为它坏了。
                    action = if (state.checking) {
                        stringResource(R.string.msp_update_checking)
                    } else {
                        stringResource(R.string.msp_update_check_action)
                    },
                    onAction = onCheck,
                )
            }

            state.failure?.let { failure ->
                item { InfoNote(UpdateSummaries.failure(failure).string()) }
                item {
                    TextButton(onClick = onDismissFailure) {
                        Text(stringResource(R.string.msp_update_failure_dismiss))
                    }
                }
            }

            // 「安装未知应用」没打开时，安装按钮按下去只会弹一个系统页面，
            // 所以先把这一步摆在明面上：用户能预期到接下来会发生什么。
            if (!state.canInstallPackages) {
                item { InfoNote(stringResource(R.string.msp_update_need_unknown_source)) }
                item {
                    TextButton(onClick = onOpenUnknownSource) {
                        Text(stringResource(R.string.msp_update_open_unknown_source))
                    }
                }
            }

            val progress = state.progress
            if (progress != null) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val fraction = progress.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            // 服务端没给 `Content-Length` 时只能画一条不确定的进度条，
                            // 而不是画一条定在 0% 的确定进度条——后者看起来像卡住了。
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = downloadStatusText(context, progress.bytesRead, progress.totalBytes, fraction),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            when (val availability = state.availability) {
                is UpdateAvailability.Available -> {
                    item { SectionHeader(stringResource(R.string.msp_update_section_available)) }
                    item {
                        ReleaseNotes(
                            release = availability.release,
                            // 下载中 / 检查中都不接受新动作：两个都占着网络，
                            // 而且「忽略」在下载做到一半时按下会让状态自相矛盾。
                            busy = state.progress != null || state.checking,
                            onDownload = onDownload,
                            onIgnore = onIgnore,
                        )
                    }
                }

                is UpdateAvailability.Ignored -> {
                    item {
                        // 只给「撤销忽略」入口，而不问一次「你确定要重新考虑吗」：
                        // 忽略本来就不是个重动作，撤销也不应该变成一个需要确认的事。
                        TextButton(onClick = onUndoIgnore) {
                            Text(stringResource(R.string.msp_update_undo_ignore))
                        }
                    }
                }

                UpdateAvailability.NotChecked,
                UpdateAvailability.UpToDate,
                -> Unit
            }

            item { SectionHeader(stringResource(R.string.msp_update_section_settings)) }

            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.msp_update_auto_check),
                    subtitle = stringResource(R.string.msp_update_auto_check_desc),
                    checked = settings.autoCheck,
                    onCheckedChange = onSetAutoCheck,
                )
            }

            item {
                // 开关和通道之间：它只对开关打开时的自动检查有意义，
                // 所以紧跟在开关下面；而它的取值又会决定「多久问一次」，
                // 又必须排在「问什么」（通道）前面。
                //
                // 开关关掉时这一行**照常可改**，而不是变灰：变灰的行读起来像
                // 「这里出错了」，而这里只是一个当下不生效的设置。
                SettingActionButtonRow(
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(R.string.msp_update_check_interval),
                    subtitle = stringResource(R.string.msp_update_check_interval_desc),
                    action = UpdateSummaries.interval(settings.checkIntervalMs).string(),
                    onAction = { showIntervalDialog = true },
                )
            }

            item {
                // 通道用「按钮在右侧」那一行而不是整行可点的选择行：
                // 右侧写的是**当前值**，而这一行里用户会先读那个值再决定要不要换，
                // 整行可点的话读一下就跳走了。
                SettingActionButtonRow(
                    icon = Icons.Outlined.SwapVert,
                    title = stringResource(R.string.msp_update_channel),
                    subtitle = UpdateSummaries.channelDescription(settings.channel).string(),
                    action = UpdateSummaries.channel(settings.channel).string(),
                    onAction = { showChannelDialog = true },
                )
            }

            item {
                SettingActionButtonRow(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.msp_update_token),
                    subtitle = UpdateSummaries.token(settings.hasToken).string(),
                    action = if (settings.hasToken) {
                        stringResource(R.string.msp_update_token_change)
                    } else {
                        stringResource(R.string.msp_update_token_add)
                    },
                    onAction = { showTokenDialog = true },
                )
            }

            item { InfoNote(stringResource(R.string.msp_update_token_note)) }
        }
    }

    if (showChannelDialog) {
        ChoiceDialog(
            title = stringResource(R.string.msp_update_channel),
            options = UpdateChannel.entries.toList(),
            selected = settings.channel,
            label = { UpdateSummaries.channel(it).string() },
            description = { UpdateSummaries.channelDescription(it).string() },
            onSelect = {
                showChannelDialog = false
                onSetChannel(it)
            },
            onDismiss = { showChannelDialog = false },
        )
    }

    if (showIntervalDialog) {
        CheckIntervalDialog(
            currentMs = settings.checkIntervalMs,
            onSelect = {
                showIntervalDialog = false
                onSetCheckInterval(it)
            },
            onDismiss = { showIntervalDialog = false },
        )
    }

    if (showTokenDialog) {
        UpdateTokenDialog(
            hasToken = settings.hasToken,
            onSave = {
                showTokenDialog = false
                onPutToken(it)
            },
            onClear = {
                showTokenDialog = false
                onClearToken()
            },
            onDismiss = { showTokenDialog = false },
        )
    }
}

/**
 * 更新说明 + 两个动作（下载 / 忽略）。
 *
 * 说明区**独立可折叠**：一份完整的发行说明有几十行，而用户多数时候只想按「下载」。
 * 不让它撑满一屏、也不把它藏进另一个页面——前者把按钮推到屏幕外，后者让用户
 * 在不看说明的情况下决定要不要装。
 */
@Composable
private fun ReleaseNotes(
    release: UpdateRelease,
    busy: Boolean,
    onDownload: () -> Unit,
    onIgnore: () -> Unit,
) {
    var expanded by remember(release.tagName) { mutableStateOf(false) }
    val notes = release.notes?.trim().orEmpty()

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.msp_update_available_version, release.tagName),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (notes.isEmpty()) {
                stringResource(R.string.msp_update_notes_empty)
            } else {
                notes
            },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_NOTE_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        // 够短就不给按钮：一个点了没有任何视觉变化的「展开」比没有更糟。
        if (notes.length > COLLAPSED_NOTE_CHARS) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(
                    stringResource(
                        if (expanded) R.string.msp_update_notes_collapse else R.string.msp_update_notes_expand,
                    ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onDownload,
            enabled = !busy && release.isInstallable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.msp_update_download_and_install))
        }
        TextButton(onClick = onIgnore, enabled = !busy) {
            Text(stringResource(R.string.msp_update_ignore))
        }
    }
}

/** [hasToken] 为 true 时对话框里会多一个「清除」——它是**破坏性**动作，所以只放在那里。 */
@Composable
private fun UpdateTokenDialog(
    hasToken: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msp_update_token)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.msp_update_token_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    // 令牌就是密码。用密码遮罩而不是明文：设置页会被截屏、
                    // 会被旁人看到，而 GitHub 令牌能读写账号下的仓库。
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.msp_update_token_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(input) },
                // 空输入时按钮不可用，而不是「保存空值」：`putToken` 对空输入
                // 本来就返回 false 不修改，但界面上要让它看起来就是这样，
                // 否则用户会以为自己清空了令牌。
                enabled = input.isNotBlank(),
            ) {
                Text(stringResource(R.string.msp_settings_save))
            }
        },
        dismissButton = {
            if (hasToken) {
                // 清除走确认按钮之外的位置，并且**不**和保存并排成一个
                // 「保存 / 清除」的二选一：两个都是主要动作的按钮，
                // 用户会在想保存的时候点到清除。
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.msp_update_token_clear))
                }
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.msp_settings_cancel))
            }
        },
    )
}

/**
 * 冷却窗口的选择框：固定档位 + 「自定义…」。
 *
 * 档位按一下**立即生效并关框**（和通道那个框一样）：档位只有六个、一屏能看完，
 * 再要一次「保存」只是多一步。
 *
 * 自定义那一档反过来：两格填完才出现「保存」。这时用户是在**填一次**而不是
 * **选一个**，中途每敲一个数字都生效会把 12 改成 1、再改成 12 这样来回写盘。
 *
 * 进来时若当前值正好是某个档位，就停在档位上；否则（用户上次填的是自定义值）
 * 直接把两格预填成当前值——「上次设了 2 小时 30 分，再点进来还能看见它」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckIntervalDialog(
    currentMs: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft: DurationDraft = remember(currentMs) { UpdateCooldown.draftOf(currentMs) }
    var custom by remember(currentMs) { mutableStateOf(UpdateCooldown.isCustom(currentMs)) }
    var hours by remember(currentMs) { mutableStateOf(draft.hoursText()) }
    var minutes by remember(currentMs) { mutableStateOf(draft.minutesText()) }

    val parsed = UpdateCooldown.parseInput(hours, minutes)
    val problem: MspText? = when (parsed) {
        is DurationInput.Valid, DurationInput.Blank -> null
        DurationInput.NotANumber -> MspText.Res(R.string.msp_update_check_interval_number)
        DurationInput.TooShort -> MspText.Res(R.string.msp_update_check_interval_min)
        DurationInput.TooLong -> MspText.Res(R.string.msp_update_check_interval_max)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msp_update_check_interval)) },
        text = {
            Column {
                // `FlowRow` 而不是等分 `Row`：六个档位加「自定义…」在中文下
                // 正好一行，换成英文（`12 h` / `Custom…`）长度就变了，
                // 等分会把字裁掉。换行对芯片文案是安全的——它们都很短。
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UpdateCooldown.PRESETS_MS.forEach { preset ->
                        FilterChip(
                            selected = !custom && UpdateCooldown.presetFor(currentMs) == preset,
                            onClick = { onSelect(preset) },
                            label = { Text(UpdateSummaries.interval(preset).string()) },
                        )
                    }
                    FilterChip(
                        selected = custom,
                        onClick = { custom = true },
                        label = { Text(stringResource(R.string.msp_update_check_interval_custom)) },
                    )
                }

                if (custom) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IntervalField(
                            value = hours,
                            onValueChange = { hours = it },
                            label = stringResource(R.string.msp_update_check_interval_hours),
                            modifier = Modifier.weight(1f),
                        )
                        IntervalField(
                            value = minutes,
                            onValueChange = { minutes = it },
                            label = stringResource(R.string.msp_update_check_interval_minutes),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // 出错时这一行**换成**错误文案，而不是在提示下面再补一行：
                    // 这里只放得下一行，而「为什么保存按不动」比「该怎么填」更该被看见。
                    Text(
                        text = problem?.string()
                            ?: stringResource(R.string.msp_update_check_interval_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (problem == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        },
        confirmButton = {
            if (custom) {
                TextButton(
                    onClick = {
                        (parsed as? DurationInput.Valid)?.let {
                            onSelect(it.minutes * UpdateCooldown.MINUTE_MS)
                        }
                    },
                    enabled = parsed is DurationInput.Valid,
                ) {
                    Text(stringResource(R.string.msp_settings_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.msp_settings_cancel))
            }
        },
    )
}

/**
 * 时/分两格之一。
 *
 * 两格用 `weight(1f)` 等分是安全的：这里是数字输入，宽窄只由位数决定，
 * 不像芯片文案那样会「换个语言就装不下」。
 */
@Composable
private fun IntervalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        // 截断挡的是「粘一整段进来」：这里先拒掉，否则超长数字会先落进状态、
        // 再被解析层判成越界，报出来的原因和用户实际做的事对不上。
        onValueChange = { onValueChange(it.take(MAX_INTERVAL_INPUT_CHARS)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        label = { Text(label) },
        modifier = modifier,
    )
}

/** 上限 7 天 = `10080` 分 ⇒ 5 位；再留一位给「还在敲」。 */
private const val MAX_INTERVAL_INPUT_CHARS = 6

private const val COLLAPSED_NOTE_LINES = 12
private const val COLLAPSED_NOTE_CHARS = 420

private fun downloadStatusText(
    context: Context,
    bytesRead: Long,
    totalBytes: Long?,
    fraction: Float?,
): String {
    val read = Formatter.formatFileSize(context, bytesRead)
    return if (totalBytes == null || fraction == null) {
        context.getString(R.string.msp_update_download_unknown_total, read)
    } else {
        val total = Formatter.formatFileSize(context, totalBytes)
        // `fraction` 是 Float 的有理数，`roundToInt` 之前先挡住 NaN：
        // `Number.isFinite` 在 Kotlin 里是 `isFinite()`，而 `roundToInt()` 对 NaN
        // 会给出 0——那样进度会从 43% 掉回 0%，看起来像重新开始了。
        val percent = if (fraction.isFinite()) (fraction * 100).roundToInt().coerceIn(0, 100) else 0
        context.getString(R.string.msp_update_download_progress, percent, read, total)
    }
}
