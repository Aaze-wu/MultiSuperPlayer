package com.multisuperplayer.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
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
                )

                state.playlists.isNullOrEmpty() -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.QueueMusic,
                    title = stringResource(R.string.msp_playlists_empty_title),
                    description = stringResource(R.string.msp_playlists_empty_desc),
                    actionLabel = stringResource(R.string.msp_playlists_new),
                    onAction = { creating = true },
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(state.playlists.orEmpty(), key = { _, it -> it.id }) { _, playlist ->
                        PlaylistListRow(
                            playlist = playlist,
                            menuOpen = menuFor == playlist.id,
                            onOpenMenu = { menuFor = playlist.id },
                            onCloseMenu = { menuFor = null },
                            onOpen = { onOpen(playlist.id) },
                            onRename = { renaming = playlist.id; menuFor = null },
                            onDelete = { deleting = playlist.id; menuFor = null },
                        )
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

@Composable
private fun PlaylistListRow(
    playlist: Playlist,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
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
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun PlaylistDetailPane(
    detail: PlaylistDetail,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit,
    onRemoveItem: (Collection<String>) -> Unit,
) {
    if (detail.rows.isEmpty()) {
        EmptyState(
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            title = stringResource(R.string.msp_playlists_empty_title),
            description = stringResource(R.string.msp_playlists_empty_list_desc),
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
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
        itemsIndexed(detail.rows, key = { _, row -> row.item.mediaId }) { index, row ->
            MediaEntryRow(
                entry = row.display,
                // 失效的条目照常留在队列里（见 PlaylistRows.queue 的说明），
                // 但必须在副标题上说清楚，用户点下去之前就知道可能放不出来。
                supporting = if (row.missing) {
                    stringResource(R.string.msp_playlists_item_missing)
                } else {
                    null
                },
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
