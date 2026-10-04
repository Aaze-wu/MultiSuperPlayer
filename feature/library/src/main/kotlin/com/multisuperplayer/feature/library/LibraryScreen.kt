package com.multisuperplayer.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.ArtworkSourceRules
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.ui.artwork.ArtworkImage
import com.multisuperplayer.core.ui.text.displayTitle
import com.multisuperplayer.core.ui.text.string
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
    val message by viewModel.message.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 不看返回值：仓库按实际权限重新判断，避免这里和系统版本判断逻辑重复。
        viewModel.onPermissionsResult()
    }

    // 权限也可能是在别处给的（启动时的统一弹框、设置里的权限页、系统设置页），
    // 那几条路都拿不到上面这个 launcher 的回调，所以切回前台时补一次重扫。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LibraryScreen(
        state = state,
        modifier = modifier,
        message = message,
        playlists = playlists,
        onRequestPermission = { permissionLauncher.launch(viewModel.requiredPermissions()) },
        onRefresh = viewModel::refresh,
        onFilterChange = viewModel::setFilter,
        onQueryChange = viewModel::setQuery,
        onSortChange = viewModel::setSort,
        onGroupChange = viewModel::setGroupMode,
        onViewModeChange = viewModel::setViewMode,
        onAddToPlaylist = viewModel::addToPlaylist,
        onCreatePlaylistWith = viewModel::createPlaylistWith,
        onPlayRequest = onPlayRequest,
    )
}

/**
 * 媒体库列表（无状态）。
 *
 * 多选状态留在这里（`rememberSaveable`）而不是 ViewModel：它是纯粹的界面瞬态，
 * 切到别的页面就该丢掉，塞进 ViewModel 反而会让「离开再回来」恢复上一次的选择，
 * 而用户已经忘了自己选过什么。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    playlists: List<Playlist> = emptyList(),
    onRequestPermission: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onFilterChange: (LibraryFilter) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onSortChange: (LibrarySort) -> Unit = {},
    onGroupChange: (LibraryGroupMode) -> Unit = {},
    onViewModeChange: (LibraryViewMode) -> Unit = {},
    onAddToPlaylist: (playlistId: String, entries: List<MediaEntry>) -> Unit = { _, _ -> },
    onCreatePlaylistWith: (name: String, entries: List<MediaEntry>) -> Unit = { _, _ -> },
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    // 只需要 partial / truncated 这两个信息，分支判定整个交给 state.pane（纯函数，有单测）。
    val ready = state.library as? MediaLibraryState.Ready

    // 选择集**是有顺序的**：条目按点选顺序进选择集（见 LibrarySelectionRules），
    // 加入播放列表、从选中项开始播放都用这个顺序，所以这里必须是 List 而不是 Set。
    var selectedIds by rememberSaveable(stateSaver = MEDIA_SELECTION_SAVER) {
        mutableStateOf(emptyList<String>())
    }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    // 选择集必须先剪掉「已经不在列表里的 id」。
    // 不剪的话，筛选/搜索换一批内容之后，操作条会显示一个含不可见条目的数字
    // （「已选 5 项」但屏幕上只有 2 行是勾上的），而且「播放」会播到看不见的东西。
    // 用 entries 做 key：内容没变时（结构相等）effect 不会重启，选择照旧保留。
    LaunchedEffect(state.entries) {
        // 用 pruneSelection 而不是自己写一遍：它已经保证了「没变就返回同一个实例」，
        // 于是这里可以靠 !== 判断要不要写回状态，也不必再比一次 size。
        val pruned = pruneSelection(selectedIds, state.entries)
        if (pruned !== selectedIds) selectedIds = pruned
    }

    val rows = remember(state.entries, state.sort, state.groupMode) {
        LibraryArrangement.rows(state.entries, state.sort, state.groupMode)
    }
    // 播放队列 = 屏幕上从上到下的全部条目（不含分组头）。
    // 用 LibraryRow.Item.index 当起始下标，而不是 entries.indexOf(entry)：
    // 分组之后行的顺序和 entries 的顺序已经不是一回事了。
    val queue = remember(rows) {
        rows.filterIsInstance<LibraryRow.Item>().map { it.entry }
    }
    val selectedEntries = LibrarySelectionRules.resolve(state.entries, selectedIds)
    val unknownGroupLabel = state.groupMode.unknownLabel.string()

    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = message?.text?.string()
    LaunchedEffect(message?.nonce) {
        val text = messageText ?: return@LaunchedEffect
        // nonce 而不是 text 做 key：连续两次「已加入 3 项」是完全相等的对象，
        // 只按文本判断的话第二条不会显示，用户会以为第二次点击没生效。
        snackbarHostState.showSnackbar(text)
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectedIds.isNotEmpty()) {
                SelectionTopBar(
                    count = selectedEntries.size,
                    allSelected = LibrarySelectionRules.allSelected(selectedIds, state.entries),
                    onExit = { selectedIds = emptyList() },
                    onSelectAll = {
                        selectedIds = LibrarySelectionRules.addAll(selectedIds, state.entries)
                    },
                    onClearSelection = {
                        selectedIds = LibrarySelectionRules.removeAll(selectedIds, state.entries)
                    },
                    onAddToPlaylist = { pickerOpen = true },
                    onPlay = {
                        if (selectedEntries.isNotEmpty()) onPlayRequest(selectedEntries, 0)
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.msp_library_title)) },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.msp_library_rescan),
                            )
                        }
                    },
                )
            }
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
                LibraryViewBar(
                    state = state,
                    onSortChange = onSortChange,
                    onGroupChange = onGroupChange,
                    onViewModeChange = onViewModeChange,
                )
                if (ready?.partial == true) {
                    Banner(
                        icon = Icons.Outlined.Lock,
                        text = stringResource(R.string.msp_library_partial_access),
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        content = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                if (state.truncated) {
                    Banner(
                        icon = Icons.Outlined.WarningAmber,
                        text = stringResource(R.string.msp_library_truncated),
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        title = stringResource(R.string.msp_library_filtered_out_title),
                        description = stringResource(R.string.msp_library_filtered_out_desc),
                        actionLabel = stringResource(R.string.msp_library_clear_filters),
                        onAction = {
                            onQueryChange("")
                            onFilterChange(LibraryFilter.ALL)
                        },
                    )

                    LibraryPane.NoMedia -> EmptyState(
                        icon = Icons.Outlined.FolderOff,
                        title = stringResource(R.string.msp_library_empty_title),
                        description = stringResource(R.string.msp_library_empty_desc),
                        actionLabel = stringResource(R.string.msp_library_rescan),
                        onAction = onRefresh,
                    )

                    is LibraryPane.Failure -> EmptyState(
                        icon = Icons.Outlined.WarningAmber,
                        title = stringResource(R.string.msp_library_failure_title),
                        description = pane.message.string(),
                        actionLabel = stringResource(R.string.msp_library_retry),
                        onAction = onRefresh,
                    )

                    // 列表只在这一个分支里画。以前它是同一层 Box 里独立的 `if`，
                    // 所以能和上面的空状态同时出现在屏幕上（真机上复现过）。
                    LibraryPane.Content -> when (state.viewMode) {
                        LibraryViewMode.LIST -> MediaEntryList(
                            rows = rows,
                            queue = queue,
                            selectedIds = selectedIds,
                            unknownGroupLabel = unknownGroupLabel,
                            onToggleSelection = { id -> selectedIds = LibrarySelectionRules.toggle(selectedIds, id) },
                            onPlayRequest = onPlayRequest,
                        )

                        LibraryViewMode.GRID -> MediaEntryGrid(
                            rows = rows,
                            queue = queue,
                            selectedIds = selectedIds,
                            unknownGroupLabel = unknownGroupLabel,
                            onToggleSelection = { id -> selectedIds = LibrarySelectionRules.toggle(selectedIds, id) },
                            onPlayRequest = onPlayRequest,
                        )
                    }
                }
            }
        }
    }

    if (pickerOpen) {
        PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = { pickerOpen = false },
            onPick = { playlistId ->
                onAddToPlaylist(playlistId, selectedEntries)
                pickerOpen = false
                selectedIds = emptyList()
            },
            onCreate = { name ->
                onCreatePlaylistWith(name, selectedEntries)
                pickerOpen = false
                selectedIds = emptyList()
            },
        )
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
            placeholder = { Text(stringResource(R.string.msp_library_search_hint)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text("${filter.label.string()} ${state.counts[filter] ?: 0}") },
                )
            }
        }
    }
}

/**
 * 排序 / 分组 / 视图模式。
 *
 * 图标一律画**点击之后会变成的那个模式**（当前是列表就画网格），
 * 并且把动作写进 `contentDescription` —— 画当前状态的话，用户没法从图标本身
 * 判断「按一下会发生什么」。
 */
@Composable
private fun LibraryViewBar(
    state: LibraryUiState,
    onSortChange: (LibrarySort) -> Unit,
    onGroupChange: (LibraryGroupMode) -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibrarySortMenu(current = state.sort, onSelect = onSortChange)
        Spacer(modifier = Modifier.weight(1f))
        LibraryGroupMenu(current = state.groupMode, onSelect = onGroupChange)
        IconButton(
            onClick = {
                onViewModeChange(
                    when (state.viewMode) {
                        LibraryViewMode.LIST -> LibraryViewMode.GRID
                        LibraryViewMode.GRID -> LibraryViewMode.LIST
                    },
                )
            },
        ) {
            val toGrid = state.viewMode == LibraryViewMode.LIST
            Icon(
                imageVector = if (toGrid) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                contentDescription = stringResource(
                    if (toGrid) R.string.msp_library_view_grid else R.string.msp_library_view_list,
                ),
            )
        }
    }
}

@Composable
private fun LibrarySortMenu(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Sort,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(current.label.string(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.label.string()) },
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                    trailingIcon = {
                        if (sort == current) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryGroupMenu(current: LibraryGroupMode, onSelect: (LibraryGroupMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.Category,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(current.label.string(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibraryGroupMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label.string()) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                    trailingIcon = {
                        if (mode == current) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

// Banner 也已搬到 EntryRows.kt：播放列表页要用同一条提示条。

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaEntryList(
    rows: List<LibraryRow>,
    queue: List<MediaEntry>,
    selectedIds: List<String>,
    unknownGroupLabel: String,
    onToggleSelection: (String) -> Unit,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit,
) {
    val selectionMode = selectedIds.isNotEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        rows.forEach { row ->
            when (row) {
                // key 用 MediaEntry.id（分组头是 "h:…"）而不是下标：下标做 key 时，
                // 列表内容一变（筛选/搜索/换排序）Compose 会认为「第 3 项的内容变了」，
                // 于是复用错误的滚动位置与动画状态，看起来像滚动位置在乱跳。
                is LibraryRow.Header -> item(key = row.key) {
                    GroupHeader(title = row.title, count = row.count, unknownLabel = unknownGroupLabel)
                }

                is LibraryRow.Item -> item(key = row.key) {
                    MediaEntryRow(
                        entry = row.entry,
                        selected = row.entry.id in selectedIds,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                onToggleSelection(row.entry.id)
                            } else {
                                onPlayRequest(queue, row.index)
                            }
                        },
                        onLongClick = { onToggleSelection(row.entry.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaEntryGrid(
    rows: List<LibraryRow>,
    queue: List<MediaEntry>,
    selectedIds: List<String>,
    unknownGroupLabel: String,
    onToggleSelection: (String) -> Unit,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit,
) {
    val selectionMode = selectedIds.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 132.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            when (row) {
                is LibraryRow.Header -> item(key = row.key, span = { GridItemSpan(maxLineSpan) }) {
                    GroupHeader(title = row.title, count = row.count, unknownLabel = unknownGroupLabel)
                }

                is LibraryRow.Item -> item(key = row.key) {
                    MediaEntryTile(
                        entry = row.entry,
                        selected = row.entry.id in selectedIds,
                        selectionMode = selectionMode,
                        onClick = {
                            if (selectionMode) {
                                onToggleSelection(row.entry.id)
                            } else {
                                onPlayRequest(queue, row.index)
                            }
                        },
                        onLongClick = { onToggleSelection(row.entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: LibraryGroupTitle, count: Int, unknownLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            // 「未知艺术家」这类标签必须走资源：它是分组模式的一部分，
            // 不能在数据层固化成一句中文。
            text = when (title) {
                is LibraryGroupTitle.Text -> title.value
                LibraryGroupTitle.Unknown -> unknownLabel
            },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = stringResource(R.string.msp_library_item_count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// MediaEntryRow / MediaKind.label / MediaKind.icon / EmptyState 已搬到 EntryRows.kt：
// 「最近播放」与「播放列表」要画同一行，两个副本迟早会分叉。

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaEntryTile(
    entry: MediaEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                // 网格视图里封面就是这一格的全部内容，所以它铺满整格；
                // 圆角由外面那个 Surface 裁（连选中底色一起裁），
                // 所以这里不重复画背景，也不重复裁一遍。
                ArtworkImage(
                    request = ArtworkSourceRules.requestFor(entry),
                    fallbackIcon = entry.kind.icon,
                    contentDescription = entry.kind.label(),
                    modifier = Modifier.matchParentSize(),
                    fallbackIconSize = ArtworkSizes.TILE_ICON,
                    // 底色留给 Surface：选中态的 secondaryContainer 画在这里会漏掉，
                    // 而它正是「已选中」在网格视图里的唯一提示。
                    fallbackContainer = Color.Transparent,
                )
                if (selectionMode) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = null,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
        Text(
            text = entry.displayTitle().string(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (entry.durationMs > 0L) {
            Text(
                text = TimeFormat.clock(entry.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
        title = stringResource(R.string.msp_library_permission_title),
        description = stringResource(R.string.msp_library_permission_desc),
        actionLabel = stringResource(R.string.msp_library_grant_permission),
        onAction = onRequestPermission,
    )
}

