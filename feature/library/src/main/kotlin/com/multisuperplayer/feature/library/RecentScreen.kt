package com.multisuperplayer.feature.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.data.export.PlaybackExportFormat
import com.multisuperplayer.core.data.favorites.FavoriteRules
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.RecentPlay
import com.multisuperplayer.core.ui.text.string
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * 最近播放页的多选接线。
 *
 * 与目录页的 `BrowserSelection` 是同一套形状：多选状态在界面层（不进 ViewModel），
 * 数字、全选、退出、两个动作全都从这里往下传，界面层不再各自判断
 * 「现在是多选还是平常」。
 *
 * 这一页以前没有多选，所以**行的删除按钮只在非多选时出现**：多选时每一行左边的
 * 封面换成了勾选框，行内再放一个叉号会被读成「从选择里去掉这一项」。
 */
data class RecentSelection(
    val ids: List<String> = emptyList(),
    val count: Int = 0,
    val allSelected: Boolean = false,
    /** 选中项是否全部已收藏（决定操作条上的星是实心还是空心）。 */
    val favoriteAll: Boolean = false,
    val onToggle: (String) -> Unit = {},
    val onExit: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onAddToPlaylist: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onPlay: () -> Unit = {},
) {
    val mode: Boolean get() = ids.isNotEmpty()
}

/**
 * 最近播放入口（有状态）。
 */
@Composable
fun RecentRoute(
    modifier: Modifier = Modifier,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    val viewModel: RecentViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // 选择集是**界面瞬态**（不进 ViewModel、不落盘），而且**有顺序**：
    // 用户先点哪条、后点哪条，就是加入播放列表/开始播放的顺序。
    var selectedIds by rememberSaveable(stateSaver = MEDIA_SELECTION_SAVER) {
        mutableStateOf(emptyList<String>())
    }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    // 这一页的条目全部可播（每一行就是一条续播记录），所以「可播清单」就是全部行。
    val entries = remember(state.rows) { state.rows.orEmpty().map { it.entry } }

    // 剪枝的理由与浏览页相同：刷新后记录可能少了几条，留着旧 id 会让操作条
    // 显示一个屏幕上不存在的数字。key 用这份清单而不是它的长度。
    LaunchedEffect(entries) {
        val pruned = pruneSelection(selectedIds, entries)
        if (pruned !== selectedIds) selectedIds = pruned
    }

    val selectedEntries = LibrarySelectionRules.resolve(entries, selectedIds)

    // 多选时返回键的语义是「退出多选」。与浏览页一致，也要写在别的地方的
    // BackHandler 之后（这里没有，但保持顺序习惯一致）。
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptyList() }

    val selection = RecentSelection(
        ids = selectedIds,
        count = selectedEntries.size,
        allSelected = LibrarySelectionRules.allSelected(selectedIds, entries),
        favoriteAll = FavoriteRules.allFavorited(favorites, selectedEntries),
        onToggle = { id -> selectedIds = toggleSelection(selectedIds, entries, id) },
        onExit = { selectedIds = emptyList() },
        onSelectAll = { selectedIds = LibrarySelectionRules.addAll(selectedIds, entries) },
        onClear = { selectedIds = LibrarySelectionRules.removeAll(selectedIds, entries) },
        onAddToPlaylist = { pickerOpen = true },
        onFavorite = { viewModel.toggleFavorite(selectedEntries) },
        onPlay = { if (selectedEntries.isNotEmpty()) onPlayRequest(selectedEntries, 0) },
    )

    // 导出要分两步：先记住用户选的是哪种格式（弹菜单那一刻就知道），再等 SAF
    // 回来拿到目标 uri（可能要过好几秒，用户还得翻目录）。两件事不能一起从
    // launcher 的回调里取——回调里只有 uri。与 `PlayerScreen` 的字幕导出同一套。
    var pendingExport by remember { mutableStateOf<PlaybackExportFormat?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        // octet-stream 而不是 text/csv：DocumentsUI 不会给「已知的文本类型」
        // 补扩展名/改名字，文件名里自己带的 .csv / .json 才能原样保留。
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        // uri 为 null = 用户在选择器里按了返回。那不是失败，什么都不说。
        if (uri != null && pending != null) viewModel.exportTo(uri, pending)
    }

    // 这一页的内容会被外部改变：去播放器页播完一条再回来、在设置里开关「记录最近播放」。
    // 而这个 ViewModel 挂在导航栈上的「最近」入口上，切标签时导航栈**不会**销毁它
    // （见 `MspApp` 里关于 saveState/restoreState 的注释），所以 `init` 里那一次读取
    // 只代表应用刚启动时的样子——不在这里重读，用户看到的永远是旧快照：记录明明
    // 写进了磁盘，界面上却没有，长得完全像「没保存」。
    //
    // 用 `ON_RESUME` 而不是 `LaunchedEffect`：后者只在进入组合时跑一次，而这一页
    // 在切标签时**不会**离开组合（Scaffold 里的导航容器只换内容）。与 `BrowseScreen`
    // 同一套写法，两处要保持一致。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 收藏 / 加入播放列表的结果。与「最近」自己的删除/导出提示共用一个
    // snackbarHost：否则同一个页面会出现两条上下叠着的提示。
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = message?.text?.string()
    LaunchedEffect(message?.nonce) {
        val text = messageText ?: return@LaunchedEffect
        // nonce 而不是文本做 key：连续两次「已收藏 3 项」是完全相等的对象，
        // 只按文本判断的话第二条不会显示，用户会以为第二次点击没生效。
        snackbarHostState.showSnackbar(text)
    }

    RecentScreen(
        state = state,
        modifier = modifier,
        export = export,
        selection = selection,
        snackbarHostState = snackbarHostState,
        onRefresh = viewModel::refresh,
        onDelete = viewModel::delete,
        onUndoDelete = viewModel::undoDelete,
        onClearAll = viewModel::clearAll,
        onPlayRequest = onPlayRequest,
        // 先挑格式（菜单里那两项），再弹系统保存框。文件名在**弹之前**就要算好：
        // `CreateDocument` 的入参就是它，而 ViewModel 也是用同一个函数给结果里的
        // 文件名——两处必须是同一个来源。
        onExport = { format ->
            pendingExport = format
            exportLauncher.launch(viewModel.suggestedExportName(format))
        },
        onDismissExport = viewModel::dismissExport,
    )

    if (pickerOpen) {
        PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = { pickerOpen = false },
            // 选完就退出多选：动作已经落地，接着还勾着一批东西会让用户
            // 以为「刚才那一批还没加进去」。
            onPick = { playlistId ->
                viewModel.addToPlaylist(playlistId, selectedEntries)
                pickerOpen = false
                selectedIds = emptyList()
            },
            onCreate = { name ->
                viewModel.createPlaylistWith(name, selectedEntries)
                pickerOpen = false
                selectedIds = emptyList()
            },
        )
    }
}

/**
 * 最近播放（无状态）。
 *
 * 顺序完全交给数据层（`RecentPlayRules` 按播放时间倒序），这里不再排一次：
 * 两处排序规则迟早会分叉，而「最近播放的顺序不对」这种事用户立刻就会注意到。
 *
 * ## 删除和清空的语义
 *
 * 一行就是一条续播记录，所以「删掉这一条」也就是「忘掉它播到哪」（见
 * `RecentPlayRepository`）。清空对话框必须把这件事说出来：用户点「清空」时想的
 * 大概是「列表太长了」，而他实际失去的是「下次接着播」。反过来说，这一页的删除
 * **不碰任何文件**，这一点也要说——否则一句「删除」会让人以为文件没了，
 * 而这正好是这个播放器里最危险的一种误解。
 *
 * ## 为什么删除没有确认框，而清空有
 *
 * 删除是**可撤销**的（下面那句带「撤销」的提示），而确认框是用来救「不可撤销」
 * 的操作的；给可撤销操作再加一道确认，只会让「删掉几条」变成一件麻烦事。
 * 清空一次干掉全部记录、且没法列出「刚才删了什么」，所以它必须有确认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    state: RecentUiState,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    export: PlaybackExportState = PlaybackExportState.Idle,
    selection: RecentSelection = RecentSelection(),
    onRefresh: () -> Unit = {},
    onDelete: (RecentPlay) -> Unit = {},
    onUndoDelete: (RecentPlay) -> Unit = {},
    onClearAll: () -> Unit = {},
    onExport: (PlaybackExportFormat) -> Unit = {},
    onDismissExport: () -> Unit = {},
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val removeLabel = stringResource(R.string.msp_recent_remove)
    val removedText = stringResource(R.string.msp_recent_removed)
    val undoLabel = stringResource(R.string.msp_recent_undo)
    val clearedText = stringResource(R.string.msp_recent_cleared)
    // 用 rememberSaveable 而不是 remember：这个对话框是一个「等一下、我确认」的
    // 停顿，旋屏/切后台回来时它应该还在，而不是静悄悄消失（用户会以为自己点了取消）。
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    var choosingExport by remember { mutableStateOf(false) }

    // 导出结果。提示里可能带着文件名（很长、带时间戳），而且失败那一句是**要用户
    // 拿去做事**的（换目录、清空间），所以用 Long 而不是 Short 那 4 秒。
    //
    // 用 `export` 里那句话本身当 key（而不是整个状态对象）：`Idle`/`Running` 都没有
    // 话可说，一律退回 null；只有真的出现新的一句话时才会弹。看完就把它清掉，
    // 这样「导出一份、再导一次内容完全一样的」不会因为状态对象相等而哑掉。
    val exportText = export.message()?.string()
    LaunchedEffect(exportText) {
        if (exportText == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(message = exportText, duration = SnackbarDuration.Long)
        onDismissExport()
    }

    // 删一条，并给一次撤销的机会。撤销的作用域是**这一条记录本身**（跟着提示走），
    // 而不是 ViewModel 里某个「最后删掉的」字段：连着删两条时界面上会先后有
    // 两句提示，而读共享字段的「撤销」会还错人。见 `RecentViewModel.undoDelete`。
    // `showSnackbar` 是挂起的，所以两句提示会**排队**出现（内部串行），
    // 各自带各自的撤销对象，不会互相覆盖。
    fun deleteWithUndo(row: RecentPlay) {
        onDelete(row)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = removedText,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoDelete(row)
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selection.mode) {
                SelectionTopBar(
                    count = selection.count,
                    allSelected = selection.allSelected,
                    onExit = selection.onExit,
                    onSelectAll = selection.onSelectAll,
                    onClearSelection = selection.onClear,
                    onAddToPlaylist = selection.onAddToPlaylist,
                    onFavorite = selection.onFavorite,
                    favoriteAll = selection.favoriteAll,
                    onPlay = selection.onPlay,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.msp_recent_title)) },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.msp_recent_refresh),
                            )
                        }
                        // 一条记录都没有时不给「清空」：那时它没有任何作用，
                        // 而一个点了没反应的图标比一个不存在的图标更让人困惑。
                        //
                        // 导出同理（哪怕导出空表会被拦成「没有可导出的内容」，
                        // 那个提示本身也没意义：那个图标不应该在那里）。
                        if (!state.rows.isNullOrEmpty()) {
                            // 「导出成哪种格式」必须先问：SAF 的 `CreateDocument` 只能带
                            // 一个文件名，带不了「CSV 还是 JSON」这个选择。
                            Box {
                                IconButton(onClick = { choosingExport = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.FileDownload,
                                        contentDescription = stringResource(R.string.msp_recent_export),
                                    )
                                }
                                ExportFormatMenu(
                                    expanded = choosingExport,
                                    onDismiss = { choosingExport = false },
                                    onPick = { format ->
                                        choosingExport = false
                                        onExport(format)
                                    },
                                )
                            }
                            IconButton(onClick = { confirmingClear = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = stringResource(R.string.msp_recent_clear),
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val rows = state.rows
            when {
                state.disabled -> EmptyState(
                    icon = Icons.Outlined.VisibilityOff,
                    title = stringResource(R.string.msp_recent_disabled_title),
                    description = stringResource(R.string.msp_recent_disabled_desc),
                )

                state.blocked -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.msp_recent_blocked_title),
                    description = stringResource(R.string.msp_recent_blocked_desc),
                )

                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                rows.isNullOrEmpty() -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.msp_recent_empty_title),
                    description = stringResource(R.string.msp_recent_empty_desc),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> row.entry.id }) { index, row ->
                        val longPress = { selection.onToggle(row.entry.id) }
                        MediaEntryRow(
                            entry = row.entry,
                            selected = row.entry.id in selection.ids,
                            selectionMode = selection.mode,
                            // 位置为 0 是**一条正常记录**（刚播过，但下次从头播：短片、
                            // 或播到只剩尾巴的那种）。显示「播放到 00:00」会被读成
                            // 「没记下位置」，所以这一支单独给一句话。
                            supporting = if (row.positionMs <= 0L) {
                                stringResource(
                                    R.string.msp_recent_supporting_from_start,
                                    TimeFormat.dateTime(row.playedAtMs),
                                )
                            } else {
                                stringResource(
                                    R.string.msp_recent_supporting,
                                    TimeFormat.clock(row.positionMs),
                                    TimeFormat.dateTime(row.playedAtMs),
                                )
                            },
                            // 多选时点整行 = 勾/取消勾；平常点整行 = 从这一条开始播。
                            onClick = {
                                if (selection.mode) {
                                    selection.onToggle(row.entry.id)
                                } else {
                                    onPlayRequest(rows.map { it.entry }, index)
                                }
                            },
                            // 长按进多选（并立即把这一行勾上），与媒体库/浏览页同一个手势。
                            onLongClick = if (selection.mode) null else longPress,
                            trailing = {
                                // 时长 + 删除。时长保留（它是这一行唯一的「这文件多大」
                                // 信息），删除按钮放在它右边——和播放列表详情页
                                // 「时长 + 移除」完全一样的排法，两处按钮长在同一个
                                // 位置上，用户不用分别学。
                                //
                                // 多选时不给行内删除：那时左边的封面已经换成了勾选框，
                                // 行内再放一个叉号会被读成「从选择里去掉这一项」。
                                EntryTrailing {
                                    EntryDuration(row.entry)
                                    if (!selection.mode) {
                                        IconButton(onClick = { deleteWithUndo(row) }) {
                                            Icon(
                                                imageVector = Icons.Outlined.Close,
                                                contentDescription = removeLabel,
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.msp_recent_clear_title)) },
            text = { Text(stringResource(R.string.msp_recent_clear_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClearAll()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                message = clearedText,
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.msp_recent_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.msp_library_cancel))
                }
            },
        )
    }
}
