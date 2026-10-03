package com.multisuperplayer.feature.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.browser.BrowserContent
import com.multisuperplayer.core.data.browser.BrowserRoot
import com.multisuperplayer.core.data.browser.BrowserRootIssue
import com.multisuperplayer.core.data.browser.BrowserSort
import com.multisuperplayer.core.data.browser.BrowserSourceKind
import com.multisuperplayer.core.data.subtitle.SubtitleSource
import com.multisuperplayer.core.data.subtitle.subtitleSourceOf
import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

private const val BROWSE_TAG = "BrowseRoute"

/**
 * 「浏览」标签页。
 *
 * 这一页有**两个形态**，但只有一个页面：来源清单（从哪开始看）和目录内容。
 * 做成两个导航目的地的话，返回键会直接跳出浏览页，而用户想要的明显是
 * **先回到上一层目录**。所以「在哪儿」这件事由 [BrowseViewModel] 持有，
 * 旋转屏幕或切后台回来都还在原地。
 *
 * 这一层（路由层）的全部职责是「把手伸到 Android 上、拿回一个结果」，
 * [BrowseScreen] 本身不知道系统选择器存在。三件事只有这里能做：
 *
 * 1. `OpenDocumentTree` 的授权结果 + `takePersistableUriPermission`；
 * 2. `ON_RESUME` 重新问一次权限状态——「所有文件访问」是个**系统设置项，
 *    没有回调**，用户去设置里开完再回来，除了重新问一次没有别的办法知道；
 * 3. 把 `startActivity` 交出去。这里**不**捕获 `ActivityNotFoundException`：
 *    `StorageAccess.preferredSettingsIntent()` 已经挑了一个能解析的 intent，
 *    再包一层只会把「两个都没有」这种不该发生的情况变成一个静默的空操作。
 *
 * @param onPlayRequest 点一个可播文件时，把**当前目录里所有可播条目**交给它，
 *   并指明从哪一项开始。与媒体库/最近/播放列表是同一条「队列 = 当前列表」规则。
 * @param onOpenSubtitle 点一个字幕文件时，把这条候选交给它。与 [onPlayRequest]
 *   分开是必要的：字幕**不是媒体**，它要去的地方（当前正在播的那条）完全不同，
 *   而把 `.srt` 混进播放队列只会得到一句「无法播放」。
 */
@Composable
fun BrowseRoute(
    onPlayRequest: (List<MediaEntry>, Int) -> Unit = { _, _ -> },
    onOpenSubtitle: (SubtitleSource) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: BrowseViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 选择集是**界面瞬态**（和媒体库页一样，不进 ViewModel、不落盘），
    // 而且**有顺序**：用户先点哪条、后点哪条，就是加入播放列表/开始播放的顺序。
    var selectedIds by rememberSaveable(stateSaver = MEDIA_SELECTION_SAVER) {
        mutableStateOf(emptyList<String>())
    }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    val browsed = (state.content as? BrowserContent.Ready)?.entries.orEmpty()
    val selectable = remember(browsed) { selectableMedia(browsed) }

    // 选择集必须先剪掉「已经不在这个目录里的 id」。
    // 不剪的话：进了另一个目录、换了排序、文件被删之后，操作条会显示一个
    // 含不可见条目的数字（「已选 5 项」但屏上只有 2 行勾上），而且「播放」
    // 会播到看不见的东西。key 用这份**可播清单**而不是它的长度：
    // 两个目录行数一样是常事，而长度相同时 effect 根本不会重启。
    LaunchedEffect(selectable) {
        val pruned = pruneSelection(selectedIds, selectable)
        // 没变就**不要**写回：`pruneSelection` 没变时返回同一个实例，
        // 不判断的话每次进目录都会白写一次状态、白重组一次。
        if (pruned !== selectedIds) selectedIds = pruned
    }

    val selectedEntries = LibrarySelectionRules.resolve(selectable, selectedIds)

    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = message?.text?.string()
    LaunchedEffect(message?.nonce) {
        val text = messageText ?: return@LaunchedEffect
        // nonce 而不是 text 做 key：连续两次「已加入 3 项」是完全相等的对象，
        // 只按文本判断的话第二条不会显示，用户会以为第二次点击没生效。
        snackbarHostState.showSnackbar(text)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.reloadSources()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // 用户点了取消不是错误，什么都不做。不能把 null 当成「授权失败」提示一遍，
        // 那样每次误触都会弹一个吓人的提示。
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val taken = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            if (taken.isFailure) {
                // 拿不到持久授权的话，这条目录**这一次能用、下次启动就变成「授权被收回」**。
                // 不记日志的现场是一个「用一会儿就坏」的谜，没有任何线索指向这里。
                MspLog.w(BROWSE_TAG) { "takePersistableUriPermission 失败：$uri（${taken.exceptionOrNull()?.message}）" }
            }
            viewModel.addTree(uri.toString())
        }
    }

    // 只在「已经进到某个来源里」时接管返回键。停在来源清单时让系统处理
    // （那才是「离开这个标签页 / 退出应用」的正常语义）。
    BackHandler(enabled = state.browsing) { viewModel.goBack() }

    // 多选时返回键的语义变成「退出多选」，而不是「回上一层目录」——
    // 用户刚勾了几项，按返回的直觉是取消这个状态。
    //
    // 必须写在上一句**后面**：`OnBackPressedDispatcher` 是后注册者优先
    // （它用倒序遍历回调查表），两个都启用时最后注册的那个才会被调用。
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptyList() }

    // 动作条、行的勾选框、两个动作（播放 / 加入播放列表）都从这里往下传，
    // 界面层不再各自判断「现在是多选还是平常」。
    val selection = BrowserSelection(
        ids = selectedIds,
        count = selectedEntries.size,
        allSelected = LibrarySelectionRules.allSelected(selectedIds, selectable),
        onToggle = { id -> selectedIds = toggleSelection(selectedIds, selectable, id) },
        onExit = { selectedIds = emptyList() },
        onSelectAll = { selectedIds = LibrarySelectionRules.addAll(selectedIds, selectable) },
        onClear = { selectedIds = LibrarySelectionRules.removeAll(selectedIds, selectable) },
        onAddToPlaylist = { pickerOpen = true },
        onPlay = { if (selectedEntries.isNotEmpty()) onPlayRequest(selectedEntries, 0) },
    )

    BrowseScreen(
        state = state,
        modifier = modifier,
        selection = selection,
        snackbarHostState = snackbarHostState,
        onEnterSource = viewModel::enterSource,
        onEnterDirectory = viewModel::enterDirectory,
        onOpenCrumb = viewModel::openCrumb,
        onBack = viewModel::goBack,
        onSortChange = viewModel::selectSort,
        onShowHiddenChange = viewModel::setShowHidden,
        onAddTree = { picker.launch(null) },
        onRemoveTree = { uri ->
            // 主动移除时把系统那边的持久授权也还回去。不还的话它会一直挂在
            // 本应用的授权列表里——占系统配额，而且用户在设置里看不出是谁占的。
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.removeTree(uri)
        },
        onOpenAllFilesAccess = { context.startActivity(viewModel.allFilesAccessIntent()) },
        onOpenFile = { entry ->
            // 队列 = 当前这份目录清单里**可播的**条目（保持用户当前看到的排序），
            // 被点的那一项为起点。子目录和不可播的文件由 `toMediaEntry()` 自然剔掉。
            // 与多选的「播放」用**同一份**清单（`selectable`），
            // 两条路径各算一次迟早会有一天算出不同的东西。
            //
            // 这里**不**把条目写进媒体库：用户的意思是「翻到哪儿放一下」，
            // 不是「把整个 Download 目录收进库」。写库会让媒体库标签页突然
            // 多出几百个条目，而用户从没同意过这件事。
            //
            // 按 id 找回索引，而不是先筛一遍再数位置：两者一旦因为
            // 「某个文件构造不出 MediaEntry」而错位，就会放到别的文件上。
            // 目标 id 为 null（不可播）时永远匹配不上，就是「点了没反应」。
            val index = selectable.indexOfFirst { it.id == entry.mediaId }
            if (index >= 0) onPlayRequest(selectable, index)
        },
        onOpenSubtitle = { entry ->
            // 转换放在路由层，`BrowseScreen` 只报「用户点了这一行」。
            // 「文件名怎么解析出语言 / forced / 双语」是数据层的知识，
            // 页面本体不该为了这件事也去依赖 `SubtitleFileNaming`。
            onOpenSubtitle(subtitleSourceOf(entry.ref, entry.name, entry.sizeBytes))
        },
    )

    if (pickerOpen) {
        PlaylistPickerDialog(
            playlists = playlists,
            onDismiss = { pickerOpen = false },
            // 选完就退出多选：动作已经落地，接着还勾着一批东西会让用户
            // 以为「刚才那一批还没加进去」。消息由 ViewModel 发，
            // 「加了几个 / 跳过几个」只有它（存储层）知道。
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
 * 浏览页本体（无状态）。
 *
 * 停在来源清单还是已经进了目录，只看 `state.trail` 是不是 null——
 * 不另设一个「当前是哪个形态」的字段，两个字段迟早会互相矛盾。
 *
 * `internal`：模块对外的入口是 [BrowseRoute]；这一层的参数里有模块内的多选接线
 * （[BrowserSelection]），把它公开等于公开一个界面的内部结构。
 */
@Composable
internal fun BrowseScreen(
    state: BrowseUiState,
    modifier: Modifier = Modifier,
    selection: BrowserSelection = BrowserSelection(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onEnterSource: (BrowserRoot) -> Unit = {},
    onEnterDirectory: (BrowserEntry) -> Unit = {},
    onOpenCrumb: (Int) -> Unit = {},
    onBack: () -> Unit = {},
    onSortChange: (BrowserSort) -> Unit = {},
    onShowHiddenChange: (Boolean) -> Unit = {},
    onAddTree: () -> Unit = {},
    onRemoveTree: (String) -> Unit = {},
    onOpenAllFilesAccess: () -> Unit = {},
    onOpenFile: (BrowserEntry) -> Unit = {},
    onOpenSubtitle: (BrowserEntry) -> Unit = {},
) {
    val trail = state.trail
    if (trail == null) {
        SourceList(
            state = state,
            modifier = modifier,
            onEnterSource = onEnterSource,
            onAddTree = onAddTree,
            onRemoveTree = onRemoveTree,
            onOpenAllFilesAccess = onOpenAllFilesAccess,
        )
    } else {
        DirectoryBrowser(
            trail = trail,
            content = state.content,
            sort = state.sort,
            showHidden = state.showHidden,
            modifier = modifier,
            selection = selection,
            snackbarHostState = snackbarHostState,
            onUp = onBack,
            onEnterDirectory = onEnterDirectory,
            onOpenCrumb = onOpenCrumb,
            onSortChange = onSortChange,
            onShowHiddenChange = onShowHiddenChange,
            onOpenFile = onOpenFile,
            onOpenSubtitle = onOpenSubtitle,
        )
    }
}

/**
 * 来源清单。
 *
 * 不可用的条目**也显示出来**：藏起来的话，用户既不知道有这个来源，
 * 也不知道去哪儿开权限——这正是旧版「系统选择器不给授权」的那个抱怨。
 * 显示出来 + 说清原因 + 给一个按钮，才是能自己走出去的状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceList(
    state: BrowseUiState,
    modifier: Modifier,
    onEnterSource: (BrowserRoot) -> Unit,
    onAddTree: () -> Unit,
    onRemoveTree: (String) -> Unit,
    onOpenAllFilesAccess: () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_browse_title)) },
                actions = {
                    IconButton(onClick = onAddTree) {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.msp_browse_add),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Banner(
                icon = Icons.Outlined.Info,
                text = stringResource(R.string.msp_browse_hint),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val roots = state.roots
            when {
                roots == null -> Centered { CircularProgressIndicator() }
                roots.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.msp_browse_empty_title),
                    description = stringResource(R.string.msp_browse_empty_desc),
                    actionLabel = stringResource(R.string.msp_browse_add),
                    onAction = onAddTree,
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // key 用「类型 + ref」：同一棵树不可能同时以两种类型出现，
                    // 但换来源类型时 ref 可能撞上（同一个路径被两种方式授权）。
                    items(items = roots, key = { "${it.kind.name}:${it.ref}" }) { root ->
                        SourceRow(
                            root = root,
                            onEnter = { onEnterSource(root) },
                            onRemove = if (root.kind == BrowserSourceKind.SAF) {
                                { onRemoveTree(root.ref) }
                            } else {
                                null
                            },
                            onOpenAccess = onOpenAllFilesAccess,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    root: BrowserRoot,
    onEnter: () -> Unit,
    onRemove: (() -> Unit)?,
    onOpenAccess: () -> Unit,
) {
    val issue = root.issue
    val detail = root.detail

    val supporting: (@Composable () -> Unit)? = when {
        issue != null -> { { Text(unavailableText(issue), color = MaterialTheme.colorScheme.error) } }
        detail != null -> { { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        else -> null
    }

    // 顺序有讲究：SAF 来源永远要能删掉（哪怕它已经失效，那条记录也得能清理），
    // 所以「删除」优先于「去开启」。
    val trailing: (@Composable () -> Unit)? = when {
        onRemove != null -> {
            {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.msp_browse_remove),
                    )
                }
            }
        }
        issue == BrowserRootIssue.ACCESS_OFF -> {
            {
                TextButton(onClick = onOpenAccess) {
                    Text(stringResource(R.string.msp_browser_access_open))
                }
            }
        }
        else -> null
    }

    ListItem(
        modifier = Modifier.clickable(enabled = root.available, onClick = onEnter),
        leadingContent = {
            Icon(
                // 文件系统来源画「存储」、SAF 来源画「文件夹」：两者都能是内部存储，
                // 靠图标区分「这是怎么进来的」比靠文字短。
                imageVector = if (root.kind == BrowserSourceKind.FILE_SYSTEM) {
                    Icons.Outlined.Memory
                } else {
                    Icons.Outlined.FolderOpen
                },
                contentDescription = null,
                tint = if (root.available) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        headlineContent = {
            Text(
                text = root.label.string(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (root.available) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        supportingContent = supporting,
        trailingContent = trailing,
    )
}

/**
 * 来源不可用时的说明。
 *
 * 三种原因必须是三句话：`NOT_SUPPORTED` 的用户去设置里**找不到**这一项，
 * 给他一句「去开启」等于让他白找一趟。
 */
@Composable
private fun unavailableText(issue: BrowserRootIssue): String = stringResource(
    when (issue) {
        BrowserRootIssue.NOT_SUPPORTED -> R.string.msp_browser_access_unsupported
        BrowserRootIssue.ACCESS_OFF -> R.string.msp_browser_access_off
        BrowserRootIssue.GRANT_REVOKED -> R.string.msp_browse_tree_inaccessible
    },
)

/** 居中的单元素容器。加载指示器在五处地方用，版式只有这一种。 */
@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
