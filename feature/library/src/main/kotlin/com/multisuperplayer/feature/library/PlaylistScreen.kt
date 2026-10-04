package com.multisuperplayer.feature.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.export.PlaybackExportFormat
import com.multisuperplayer.core.data.playlist.PlaylistImportMergeChoice
import com.multisuperplayer.core.data.playlist.PlaylistImportState
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.ui.list.ReorderDragAutoScroll
import com.multisuperplayer.core.ui.list.ReorderDragTrigger
import com.multisuperplayer.core.ui.list.reorderDragItem
import com.multisuperplayer.core.ui.list.reorderDragSource
import com.multisuperplayer.core.ui.list.reorderItemColor
import com.multisuperplayer.core.ui.list.rememberReorderDragState
import com.multisuperplayer.core.ui.list.rememberReorderPreview
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 播放列表入口（有状态）。
 */
@Composable
fun PlaylistsRoute(
    modifier: Modifier = Modifier,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    val viewModel: PlaylistsViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    // 导出要分两步：先记住用户选的是「导哪个 + 哪种格式」（弹菜单那一刻就知道），
    // 再等 SAF 回来拿到目标 uri（可能要过好几秒，用户还得翻目录）。两件事不能
    // 一起从 launcher 的回调里取——回调里只有 uri。与 `PlayerScreen` 字幕导出同一套。
    //
    // 「导哪个」跟着这一次导出走（而不是让 ViewModel 自己去猜当前是列表页还是详情页）：
    // 系统选择器开着的那几十秒里用户可以返回、详情页可以被关掉，而这次导出到底是
    // 哪一份必须是**按下菜单项那一瞬**就定下的。
    var pendingExport by remember { mutableStateOf<Pair<PlaylistExportTarget, PlaybackExportFormat>?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        // octet-stream 而不是 text/csv：DocumentsUI 不会给「已知的文本类型」
        // 补扩展名/改名字，文件名里自己带的 .csv / .json 才能原样保留。
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        // uri 为 null = 用户在选择器里按了返回。那不是失败，什么都不说。
        if (uri != null && pending != null) {
            val (target, format) = pending
            when (target) {
                PlaylistExportTarget.All -> viewModel.exportAll(uri, format)
                is PlaylistExportTarget.One -> viewModel.exportOpen(target.id, uri, format)
            }
        }
    }

    // 导入只需要一步：读到文件的那一刻就知道是哪个文件了，不需要像导出那样
    // 提前记住「选的是哪一份」。文件名也由读取器顺手查出来（见 importFrom）。
    //
    // 用 `OpenDocument` 而不是 `GetContent`：后者在部分设备上会直接进相册/音乐
    // 的筛选视图，看不到「文档」类文件。取回来的 Uri 不需要持久化权限——
    // 读一遍就完事，没有跨进程恢复的诉求。
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        // uri 为 null = 用户在选择器里按了返回。那不是失败，什么都不说。
        if (uri != null) viewModel.importFrom(uri)
    }

    PlaylistsScreen(
        state = state,
        modifier = modifier,
        export = export,
        importState = importState,
        onImport = { importLauncher.launch(IMPORT_MIME_TYPES) },
        onImportChoice = viewModel::chooseImportMerge,
        onDismissImport = viewModel::dismissImport,
        onOpen = viewModel::open,
        onCloseDetail = viewModel::close,
        onPlayRequest = onPlayRequest,
        onCreate = viewModel::create,
        onRename = viewModel::rename,
        onDelete = viewModel::remove,
        onRemoveItem = viewModel::removeItems,
        onMoveItem = viewModel::moveItem,
        onMove = viewModel::move,
        onExport = { target, format ->
            pendingExport = target to format
            exportLauncher.launch(viewModel.suggestedExportName(target, format))
        },
        onDismissExport = viewModel::dismissExport,
    )
}

/**
 * 选择导入文件时给系统选择器的类型。
 *
 * 这里是「全部类型」这一个通配符，而不是一串「我们认识」的类型。理由：**能不能读
 * 由内容决定，不由 provider 报的类型决定**（见
 * [com.multisuperplayer.core.data.export.PlaybackImport.decode]）。风险在两个方向——
 *
 * - 过滤得太窄：用户从网盘/聊天软件存下来的文件常常被报成
 *   `application/octet-stream` 或厂商自己的类型，于是它**在选择器里直接变成灰色选不了**。
 *   用户看到的是「这个功能根本不认识我的文件」，而我们的解析器其实读得懂它。
 * - 过滤得宽：用户点到一张图，得到一句「这个文件认不出来」。多花一次点击，
 *   但不会走进死胡同。
 *
 * 所以宁可后者。
 */
private val IMPORT_MIME_TYPES = arrayOf("*/*")

/**
 * 这一次导出的是哪一份。
 *
 * 详情页那个分支带 id：导出结果里的文件名要用列表**自己的名字**，而系统选择器
 * 回来的那一刻 `open` 可能已经换成另一份了。
 *
 * 不能是 `internal`：它出现在公开的 [PlaylistsScreen] 参数里（“public 函数暴露
 * internal 类型”在 Kotlin 里是错误而不是警告），而这个模块又是被 app / 导航
 * 直接调用的。
 */
sealed interface PlaylistExportTarget {
    data object All : PlaylistExportTarget
    data class One(val id: String) : PlaylistExportTarget
}

/**
 * SAF 对话框里预填的文件名。
 *
 * 和最近播放页一致，算文件的**那个函数**只有一个（`PlaybackExport.suggestedFileName`），
 * 导出完成后回显的名字也走这里，所以不会出现「已保存：A.csv」而磁盘上是 B.csv。
 */
private fun PlaylistsViewModel.suggestedExportName(
    target: PlaylistExportTarget,
    format: PlaybackExportFormat,
): String = when (target) {
    PlaylistExportTarget.All -> suggestedExportName(format)
    is PlaylistExportTarget.One -> suggestedExportName(target.id, format)
}

/**
 * 播放列表（无状态）。
 *
 * 「列表 ↔ 详情」做成这一页内部的两种形态，而不是两条导航目的地：详情需要
 * 一个 `playlistId` 参数，而参数一旦进了导航路由，就得处理「id 已经不存在了」
 * 的路由（用户从别处删掉它、进程被回收后再恢复）。这里用一份本地状态，
 * 查不到就自然退回列表，没有第二种失败形态。
 *
 * 代价是系统返回键要自己接：见下面的 [BackHandler]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    state: PlaylistsUiState,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    export: PlaybackExportState = PlaybackExportState.Idle,
    importState: PlaylistImportState = PlaylistImportState.Idle,
    onOpen: (String) -> Unit = {},
    onCloseDetail: () -> Unit = {},
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
    onCreate: (String) -> Unit = {},
    onRename: (id: String, name: String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onRemoveItem: (id: String, mediaIds: Collection<String>) -> Unit = { _, _ -> },
    onMoveItem: (id: String, from: Int, to: Int) -> Unit = { _, _, _ -> },
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    onExport: (PlaylistExportTarget, PlaybackExportFormat) -> Unit = { _, _ -> },
    onDismissExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onImportChoice: (PlaylistImportMergeChoice, Boolean) -> Unit = { _, _ -> },
    onDismissImport: () -> Unit = {},
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }

    // 「选格式」在弹系统选择器之前：`CreateDocument` 只能带一个文件名，带不了
    // 「CSV 还是 JSON」。null 表示现在没在选。两个入口共用这一个状态（只会有一个
    // 图标被点到），菜单项映射回选的是哪个图标靠目标本身判断。
    var exportTarget by remember { mutableStateOf<PlaylistExportTarget?>(null) }

    val detail = state.open
    BackHandler(enabled = detail != null) { onCloseDetail() }

    // 导出结果。文案里带文件名（可能很长），失败那一句还是要用户**拿去做事**的
    // （换目录、清空间），所以用 Long 而不是那 4 秒。
    //
    // 用那句话本身当 key（而不是整个状态对象）：`Idle`/`Running` 都没话可说，
    // 一律退回 null；看完就清掉，这样「连导两份内容一模一样的」不会哑掉。
    val exportText = export.message()?.string()
    LaunchedEffect(exportText) {
        if (exportText == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(message = exportText, duration = SnackbarDuration.Long)
        onDismissExport()
    }

    // 导入结果。重名提问（[PlaylistImportState.Ask]）不是一句话，它走对话框；
    // 其余状态要么没什么可说，要么就是一句话。
    val importText = importState.message()?.string()
    LaunchedEffect(importText) {
        if (importText == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(message = importText, duration = SnackbarDuration.Long)
        onDismissImport()
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (detail == null) {
                TopAppBar(
                    title = { Text(stringResource(R.string.msp_playlists_title)) },
                    actions = {
                        // 导入不分「导哪个」：一个文件里有多少个列表就建多少个，
                        // 所以它没有格式菜单那一步，点一下直接开选择器。
                        IconButton(
                            onClick = onImport,
                            enabled = importState !is PlaylistImportState.Reading,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.FileUpload,
                                contentDescription = stringResource(R.string.msp_playlists_import),
                            )
                        }
                        // 一个列表都没有时不给导出：和「清空」那个图标同理，
                        // 点了只会弹一句「没有可导出的内容」，不如根本不给。
                        Box {
                            IconButton(
                                onClick = { exportTarget = PlaylistExportTarget.All },
                                enabled = !state.playlists.isNullOrEmpty(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FileDownload,
                                    contentDescription = stringResource(R.string.msp_playlists_export_all),
                                )
                            }
                            ExportFormatMenu(
                                expanded = exportTarget == PlaylistExportTarget.All,
                                onDismiss = { exportTarget = null },
                                onPick = { format ->
                                    exportTarget = null
                                    onExport(PlaylistExportTarget.All, format)
                                },
                            )
                        }
                        IconButton(onClick = { creating = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = stringResource(R.string.msp_playlists_new),
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(detail.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onCloseDetail) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.msp_library_back),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { onPlayRequest(detail.queue, 0) },
                            enabled = detail.queue.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = stringResource(R.string.msp_playlists_play_all),
                            )
                        }
                        // 导的是**屏幕上这一份**（`detail.rows`，含标了「文件已不在」的那些）。
                        // 空列表不给点：导出产物会是一张只有表头的空表。
                        Box {
                            IconButton(
                                onClick = { exportTarget = PlaylistExportTarget.One(detail.id) },
                                enabled = detail.rows.isNotEmpty(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FileDownload,
                                    contentDescription = stringResource(R.string.msp_playlists_export_open),
                                )
                            }
                            ExportFormatMenu(
                                expanded = exportTarget == PlaylistExportTarget.One(detail.id),
                                onDismiss = { exportTarget = null },
                                onPick = { format ->
                                    exportTarget = null
                                    onExport(PlaylistExportTarget.One(detail.id), format)
                                },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                detail != null -> PlaylistDetailPane(
                    detail = detail,
                    onPlayRequest = onPlayRequest,
                    onRemoveItem = { mediaIds -> onRemoveItem(detail.id, mediaIds) },
                    onMoveItem = { from, to -> onMoveItem(detail.id, from, to) },
                )

                state.playlists.isNullOrEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.QueueMusic,
                    title = stringResource(R.string.msp_playlists_empty_title),
                    description = stringResource(R.string.msp_playlists_empty_desc),
                    actionLabel = stringResource(R.string.msp_playlists_new),
                    onAction = { creating = true },
                )

                else -> {
                    // 这里的长按拖动和详情页用的是同一套（见 `ReorderDragSource`）：
                    // 两处各写一份的话，以后调整手感只会改到其中一处，
                    // 而「列表页拖着跟手、详情页发飘」这种差异没人会去复现。
                    val rows = state.playlists.orEmpty()
                    val listState = rememberLazyListState()
                    val drag = rememberReorderDragState()
                    // 拖动期间渲染的是这一份，松手时才把「谁移到哪儿」交出去。
                    val shown = rememberReorderPreview(items = rows, state = drag, onCommit = onMove)
                    // 列表上面插着一条拖动提示：`LazyColumn` 的下标要减掉它才是数据下标。
                    val headerCount = if (rows.size > 1) 1 else 0
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            // 手势挂在列表上，不在行上：行会被列表回收，挂在行上的
                            // 手势会跟着行一起消失（表现是长按拖到一半突然断了）。
                            .reorderDragSource(
                                state = drag,
                                listState = listState,
                                itemCount = shown.size,
                                trigger = ReorderDragTrigger.LongPress,
                                dataIndexOf = { lazy ->
                                    (lazy - headerCount).takeIf { it in shown.indices }
                                },
                            ),
                    ) {
                        // 只有一条时拖动没有任何意义，提示也就不必占一行。
                        if (rows.size > 1) {
                            item(key = "drag-hint") { DragHint() }
                        }
                        itemsIndexed(shown, key = { _, it -> it.id }) { index, playlist ->
                            PlaylistListRow(
                                playlist = playlist,
                                menuOpen = menuFor == playlist.id,
                                containerColor = reorderItemColor(drag, index),
                                onOpenMenu = { menuFor = playlist.id },
                                onCloseMenu = { menuFor = null },
                                onOpen = { onOpen(playlist.id) },
                                onRename = { renaming = playlist.id; menuFor = null },
                                onDelete = { deleting = playlist.id; menuFor = null },
                                modifier = Modifier.reorderDragItem(state = drag, index = index),
                            )
                        }
                    }
                    // 拖到上下边缘时自动滚动。它不画任何东西，只要和列表待在同一个
                    // 容器里就行（这里外面就是那个 `Box`）：靠 `listState` 和 `drag` 干活。
                    ReorderDragAutoScroll(state = drag, listState = listState)
                }
            }
        }
    }

    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.msp_playlists_new),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                onCreate(name)
            },
        )
    }

    // 重命名/删除的目标都用 id 记着，而不是把整个 Playlist 存进状态：列表一变
    // （比如另一处改了名字）这份副本就过期了，对话框里显示的会是旧名字。
    val renameTarget = state.playlists?.firstOrNull { it.id == renaming }
    if (renameTarget != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.msp_playlists_rename),
            initial = renameTarget.name,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRename(renameTarget.id, name)
            },
        )
    }

    val deleteTarget = state.playlists?.firstOrNull { it.id == deleting }
    if (deleteTarget != null) {        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.msp_playlists_delete_title)) },
            text = { Text(stringResource(R.string.msp_playlists_delete_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        onDelete(deleteTarget.id)
                    },
                ) {
                    Text(stringResource(R.string.msp_playlists_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.msp_library_cancel))
                }
            },
        )
    }

    // 重名问「合并还是新建」。它和上面几个对话框有个本质区别：关掉它不是
    // 「这一次操作取消」，而是**整次导入取消**（还没写盘，库里一个字节都没动，
    // 见 PlaylistImporter）。所以 dismiss 走的是 onDismissImport 而不是什么
    // 都不做——否则那个协程会永远停在 await 上，用户再点导入时被 Reading 挡住。
    val askImport = importState as? PlaylistImportState.Ask
    if (askImport != null) {
        ImportChoiceDialog(
            state = askImport,
            onChoice = onImportChoice,
            onDismiss = onDismissImport,
        )
    }
}

/**
 * 「这个列表已经存在，合并还是新建？」。
 *
 * 两个动作用两个按钮平铺在最下面（而不是默认确认+取消）：它们**没有主次**——
 * 「合并」会改掉用户已有的列表，「新建」不会，哪种更安全取决于用户想要什么。
 * 默认确认的那个会被当成推荐动作，而这里没有推荐。
 *
 * 「剩下的都这样处理」只在真的还有别的重名时才给：只剩这一个的时候勾上
 * 什么也不会变，而用户会以为剩下那几个被静默处理掉了。
 */
@Composable
private fun ImportChoiceDialog(
    state: PlaylistImportState.Ask,
    onChoice: (PlaylistImportMergeChoice, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // 按名字键住：换到下一个重名列表时这个勾要回到未勾。用 `remember(state.name)`
    // 而不是外层的一个变量，是因为「哪个列表正在问」本身就是这个状态的标识。
    var applyToAll by remember(state.name) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msp_import_ask_title)) },
        text = {
            Column {
                Text(stringResource(R.string.msp_import_ask_desc, state.name))
                if (state.remaining > 1) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.msp_import_ask_remaining, state.remaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { applyToAll = !applyToAll },
                    ) {
                        Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                        Text(stringResource(R.string.msp_import_apply_all))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoice(PlaylistImportMergeChoice.MERGE, applyToAll) }) {
                Text(stringResource(R.string.msp_import_merge))
            }
        },
        dismissButton = {
            TextButton(onClick = { onChoice(PlaylistImportMergeChoice.NEW, applyToAll) }) {
                Text(stringResource(R.string.msp_import_new))
            }
        },
    )
}

/**
 * 播放列表清单里的一行。
 *
 * [containerColor] 与 [modifier] 都是为拖动排序开的两个口子：前者把「松手会
 * 落在这里」画在**这一行自己的底色**上（外面再包一层带背景的 Box 会被它盖住，
 * 表现就是「拖到哪儿都看不出会落在哪儿」），后者挂手势与位移。两者都给默认值，
 * 别的地方照旧。
 */
@Composable
private fun PlaylistListRow(
    playlist: Playlist,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    containerColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = containerColor ?: MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = {
                Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(stringResource(R.string.msp_library_item_count, playlist.size))
            },
            leadingContent = {
                Icon(imageVector = Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = null)
            },
            trailingContent = {
                Box {
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.msp_playlists_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onCloseMenu) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.msp_playlists_rename)) },
                            onClick = onRename,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.msp_playlists_delete)) },
                            onClick = onDelete,
                        )
                    }
                }
            },
            // 普通 clickable（不是 combinedClickable）：长按要留给外层的拖动。
            modifier = Modifier.clickable(onClick = onOpen),
        )
    }
}

/**
 * 播放列表详情。列表顺序就是播放顺序，长按整行可以拖动重排。
 *
 * ## 为什么是长按整行，而不是像播放队列那样给一个把手
 *
 * 队列面板里那根把手是为了**保护单击切歌**：那一行点一下就等于换歌，整行都能拖的话
 * 手指落下的几像素抖动会让点击几乎点不准。这一页的行本来就是「点一下从这儿开始播」，
 * 但行更高（有副标题和时长），长按 500ms 之后才开始拖，误触的代价只是一次什么都没
 * 发生的长按——而用户对这一页的预期就是「长按拖一下」。
 *
 * ## 手势本身在 `ReorderDragSource` 里
 *
 * 量行高、算落点、把位移画到行上这一套，和列表页的「播放列表清单」完全一样，
 * 所以都搬到了 `core:ui`（那里解释了「手势为什么必须挂在列表上」）。
 * 这里只剩下「哪一行被拎起来 → 给什么底色」，而那是每一行自己的外貌。
 *
 * ## 拖动中的三个状态用 `remember` 而不是 `rememberSaveable`
 *
 * 转屏 / 进程重建时拖拽手势必然已经中断（手指要么抬起来了，要么这次交互已经结束），
 * 把「正在拖第几行」存下来只会在重建后画出一个没有手指的幽灵行。
 */
@Composable
private fun PlaylistDetailPane(
    detail: PlaylistDetail,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit,
    onRemoveItem: (Collection<String>) -> Unit,
    onMoveItem: (from: Int, to: Int) -> Unit,
) {
    if (detail.rows.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            title = stringResource(R.string.msp_playlists_empty_title),
            description = stringResource(R.string.msp_playlists_empty_list_desc),
        )
        return
    }

    val listState = rememberLazyListState()
    val drag = rememberReorderDragState()
    // 拖动期间渲染的是这一份：`onMoveItem` 会写库，那一份真实数据在拖动中
    // 一下都不能动（写一次之后整页会重读，手里拖着的那一行会跟着跑）。
    val rows = detail.rows
    val shown = rememberReorderPreview(items = rows, state = drag, onCommit = onMoveItem)
    // 上面可能插着失效横幅和拖动提示：`LazyColumn` 的下标要减掉它们才是数据下标。
    val headerCount = (if (detail.missingCount > 0) 1 else 0) + (if (rows.size > 1) 1 else 0)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .reorderDragSource(
                state = drag,
                listState = listState,
                itemCount = shown.size,
                trigger = ReorderDragTrigger.LongPress,
                dataIndexOf = { lazy -> (lazy - headerCount).takeIf { it in shown.indices } },
            ),
    ) {
        if (detail.missingCount > 0) {
            item(key = "missing") {
                Banner(
                    icon = Icons.Outlined.WarningAmber,
                    text = stringResource(R.string.msp_playlists_missing_banner, detail.missingCount),
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        // 只有一条时拖动没有任何意义，提示也就没必要占一行。
        if (rows.size > 1) {
            item(key = "drag-hint") { DragHint() }
        }
        itemsIndexed(shown, key = { _, row -> row.item.mediaId }) { index, row ->
            Box(
                modifier = Modifier.reorderDragItem(state = drag, index = index),
            ) {
                MediaEntryRow(
                    entry = row.display,
                    // 失效的条目照常留在队列里（见 PlaylistRows.queue 的说明），
                    // 但必须在副标题上说清楚，用户点下去之前就知道可能放不出来。
                    supporting = if (row.missing) {
                        stringResource(R.string.msp_playlists_item_missing)
                    } else {
                        null
                    },
                    // 底色必须画在这一行自己的 `Surface` 上（见 `MediaEntryRow`）。
                    containerColor = reorderItemColor(drag, index),
                    onClick = { onPlayRequest(detail.queue, index) },
                    trailing = {
                        EntryTrailing {
                            EntryDuration(row.display)
                            IconButton(onClick = { onRemoveItem(listOf(row.item.mediaId)) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.msp_playlists_remove_item),
                                )
                            }
                        }
                    },
                )
            }
        }
    }

    // 拖到上下边缘时自动滚动。和上面那个清单页一样，它不画东西，只是待在同一个
    // 容器里（调用方用 `Box` 装着这一页）。
    ReorderDragAutoScroll(state = drag, listState = listState)
}

/**
 * 两个列表共用的拖动提示。
 *
 * 抽出来的理由不是省几行，而是「文案只有一个来处」：以后改措辞时不会有
 * 一个页面改了、另一个页面还写着旧话。
 */
@Composable
private fun DragHint() {
    Text(
        text = stringResource(R.string.msp_playlists_drag_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
    )
}

/**
 * 新建/重命名共用的小对话框。
 *
 * 确认按钮在名字为空白时禁用：空白名字会被 [com.multisuperplayer.core.data.playlist.PlaylistRules]
 * 里的名字校验挡掉，但那时对话框已经关了——用户看到的是「点了确认什么都没发生」。
 */
@Composable
private fun PlaylistNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.msp_library_playlist_name_hint)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.msp_library_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.msp_library_cancel))
            }
        },
    )
}
