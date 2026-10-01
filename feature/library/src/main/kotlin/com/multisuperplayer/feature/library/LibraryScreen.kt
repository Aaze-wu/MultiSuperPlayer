package com.multisuperplayer.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import org.koin.androidx.compose.koinViewModel

/**
 * 媒体库入口（有状态）。
 *
 * 拆成 `Route` + 无状态 [LibraryScreen] 两个函数是刻意的：
 * 权限请求和 ViewModel 都必须绑定到 Activity，而列表本身是纯入参出参。
 * 这样列表的所有分支（加载中、要授权、空、报错、有内容）都能直接预览，
 * 不必真的去授权、也不必真的有一台装满歌的手机。
 */
@Composable
fun LibraryRoute(
    modifier: Modifier = Modifier,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    val viewModel: LibraryViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 不看返回值：仓库按实际权限重新判断，避免这里和系统版本判断逻辑重复。
        viewModel.onPermissionsResult()
    }

    LibraryScreen(
        state = state,
        modifier = modifier,
        onRequestPermission = { permissionLauncher.launch(viewModel.requiredPermissions()) },
        onRefresh = viewModel::refresh,
        onFilterChange = viewModel::setFilter,
        onQueryChange = viewModel::setQuery,
        onPlayRequest = onPlayRequest,
    )
}

/**
 * 媒体库列表（无状态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onFilterChange: (LibraryFilter) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    // 只需要 partial 这一个信息，分支判定整个交给 state.pane（纯函数，有单测）。
    val ready = state.library as? MediaLibraryState.Ready

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("媒体库") },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新扫描")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 工具栏是否显示，由**原始库**是否为空决定，绝不能由当前搜索结果决定。
            // 这里原本用的是「搜索命中数 > 0」，于是输入一个匹配不到的词就会
            // 把搜索框连同查询词一起藏起来 —— 用户既看不到自己输的字，也没入口清掉它。
            if (state.libraryCount > 0) {
                LibraryToolbar(
                    state = state,
                    onFilterChange = onFilterChange,
                    onQueryChange = onQueryChange,
                )
                if (ready?.partial == true) {
                    PartialAccessBanner()
                }
                HorizontalDivider()
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (val pane = state.pane) {
                    LibraryPane.Loading -> LoadingState()

                    LibraryPane.NeedsPermission -> NeedsPermissionState(onRequestPermission)

                    // 「被挡光了」和「库里是空的」是两个分支，各自只说自己那件事。
                    // 之前它们共用一个 `Ready` 分支，于是扫描到 0 条时提示的是
                    // 「没有匹配的内容」，而搜索匹配不到时提示的是「还没有扫描到媒体」——
                    // 两个提示都恰好说反了，用户要做的下一步也正好相反。
                    LibraryPane.FilteredOut -> EmptyState(
                        icon = Icons.Outlined.SearchOff,
                        title = "没有匹配的内容",
                        description = "库里的媒体还在，只是当前搜索或筛选没匹配到。",
                        actionLabel = "清空搜索与筛选",
                        onAction = {
                            onQueryChange("")
                            onFilterChange(LibraryFilter.ALL)
                        },
                    )

                    LibraryPane.NoMedia -> EmptyState(
                        icon = Icons.Outlined.FolderOff,
                        title = "还没有扫描到媒体",
                        description = "本机没有找到音频或视频文件。\n把文件放进手机存储后重新扫描即可。",
                        actionLabel = "重新扫描",
                        onAction = onRefresh,
                    )

                    is LibraryPane.Failure -> EmptyState(
                        icon = Icons.Outlined.WarningAmber,
                        title = "扫描失败",
                        description = pane.message,
                        actionLabel = "重试",
                        onAction = onRefresh,
                    )

                    // 列表只在这一个分支里画。以前它是同一层 Box 里独立的 `if`，
                    // 所以能和上面的空状态同时出现在屏幕上（真机上复现过）。
                    LibraryPane.Content -> MediaEntryList(
                        entries = state.entries,
                        onPlayRequest = onPlayRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryToolbar(
    state: LibraryUiState,
    onFilterChange: (LibraryFilter) -> Unit,
    onQueryChange: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜索标题、艺术家、专辑") },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text("${filter.label} ${state.counts[filter] ?: 0}") },
                )
            }
        }
    }
}

/**
 * 只拿到部分权限时的提示条。
 *
 * 必须显示。否则用户看到的是一个**看起来完整**的列表，会以为文件丢了，
 * 而实际上它们只是被权限挡在了查询之外。
 */
@Composable
private fun PartialAccessBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Text(
                text = "只授权了部分媒体访问，列表可能不完整。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MediaEntryList(
    entries: List<MediaEntry>,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(
            items = entries,
            // key 用 MediaEntry.id 而不是下标：下标做 key 时，列表内容一变
            // （筛选/搜索）Compose 会认为「第 3 项的内容变了」，于是复用错误的
            // 滚动位置与动画状态，看起来像滚动位置在乱跳。
            key = { it.id },
        ) { entry ->
            MediaEntryRow(
                entry = entry,
                onClick = { onPlayRequest(entries, entries.indexOf(entry)) },
            )
        }
    }
}

@Composable
private fun MediaEntryRow(entry: MediaEntry, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = entry.subtitle.ifBlank { entry.kind.label },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(imageVector = entry.kind.icon, contentDescription = entry.kind.label)
        },
        trailingContent = {
            // 时长未知时**不显示** `00:00`。那会被读成「这条是空文件」，
            // 而时长缺失其实是常态（有些容器不在 MediaStore 里存时长）。
            if (entry.durationMs > 0L) {
                Text(
                    text = TimeFormat.clock(entry.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NeedsPermissionState(onRequestPermission: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.Lock,
        title = "需要媒体访问权限",
        description = "扫描本机音频和视频需要读取媒体的权限。\n" +
            "应用只读取文件名、时长等元数据，不会修改或上传任何文件。",
        actionLabel = "授予权限",
        onAction = onRequestPermission,
    )
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) {
                Text(actionLabel)
            }
        }
    }
}

private val MediaKind.label: String
    get() = when (this) {
        MediaKind.AUDIO -> "音频"
        MediaKind.VIDEO -> "视频"
        MediaKind.UNKNOWN -> "媒体"
    }

private val MediaKind.icon: ImageVector
    get() = when (this) {
        MediaKind.VIDEO -> Icons.Outlined.Movie
        MediaKind.AUDIO, MediaKind.UNKNOWN -> Icons.Outlined.MusicNote
    }
