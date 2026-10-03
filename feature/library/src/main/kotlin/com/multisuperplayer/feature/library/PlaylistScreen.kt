package com.multisuperplayer.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.ui.list.rememberReorderDragState
import com.multisuperplayer.core.ui.list.reorderDrag
import com.multisuperplayer.core.ui.list.reorderItemColor
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

    PlaylistsScreen(
        state = state,
        modifier = modifier,
        onOpen = viewModel::open,
        onCloseDetail = viewModel::close,
        onPlayRequest = onPlayRequest,
        onCreate = viewModel::create,
        onRename = viewModel::rename,
        onDelete = viewModel::remove,
        onRemoveItem = viewModel::removeItems,
        onMoveItem = viewModel::moveItem,
        onMove = viewModel::move,
    )
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
    onOpen: (String) -> Unit = {},
    onCloseDetail: () -> Unit = {},
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
    onCreate: (String) -> Unit = {},
    onRename: (id: String, name: String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onRemoveItem: (id: String, mediaIds: Collection<String>) -> Unit = { _, _ -> },
    onMoveItem: (id: String, from: Int, to: Int) -> Unit = { _, _, _ -> },
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by rememberSaveable { mutableStateOf<String?>(null) }

    val detail = state.open
    BackHandler(enabled = detail != null) { onCloseDetail() }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (detail == null) {
                TopAppBar(
                    title = { Text(stringResource(R.string.msp_playlists_title)) },
                    actions = {
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
                    // 这里的长按拖动和详情页用的是同一套（见 `Modifier.reorderDrag`）：
                    // 两处各写一份的话，以后调整手感只会改到其中一处，
                    // 而「列表页拖着跟手、详情页发飘」这种差异没人会去复现。
                    val rows = state.playlists.orEmpty()
                    val listState = rememberLazyListState()
                    val drag = rememberReorderDragState()
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        // 只有一条时拖动没有任何意义，提示也就不必占一行。
                        if (rows.size > 1) {
                            item(key = "drag-hint") { DragHint() }
                        }
                        itemsIndexed(rows, key = { _, it -> it.id }) { index, playlist ->
                            PlaylistListRow(
                                playlist = playlist,
                                menuOpen = menuFor == playlist.id,
                                containerColor = reorderItemColor(drag, index),
                                onOpenMenu = { menuFor = playlist.id },
                                onCloseMenu = { menuFor = null },
                                onOpen = { onOpen(playlist.id) },
                                onRename = { renaming = playlist.id; menuFor = null },
                                onDelete = { deleting = playlist.id; menuFor = null },
                                modifier = Modifier.reorderDrag(
                                    state = drag,
                                    index = index,
                                    itemKey = playlist.id,
                                    listState = listState,
                                    itemCount = rows.size,
                                    onMove = onMove,
                                ),
                            )
                        }
                    }
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
    if (deleteTarget != null) {
        AlertDialog(
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
 * ## 手势本身在 `Modifier.reorderDrag` 里
 *
 * 量行高、算落点、把位移画到行上这一套，和列表页的「播放列表清单」完全一样，
 * 所以都搬到了 `core:ui`（那里解释了「行高为什么是量出来的」）。这里只剩下
 * 「哪一行被拎起来 → 给什么底色」，而那是每一行自己的外貌。
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

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
        if (detail.rows.size > 1) {
            item(key = "drag-hint") { DragHint() }
        }
        itemsIndexed(detail.rows, key = { _, row -> row.item.mediaId }) { index, row ->
            Box(
                modifier = Modifier.reorderDrag(
                    state = drag,
                    index = index,
                    itemKey = row.item.mediaId,
                    listState = listState,
                    itemCount = detail.rows.size,
                    onMove = onMoveItem,
                ),
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
