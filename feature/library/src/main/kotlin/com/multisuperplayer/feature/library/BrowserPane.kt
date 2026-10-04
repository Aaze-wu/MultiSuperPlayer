package com.multisuperplayer.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.data.browser.BrowserContent
import com.multisuperplayer.core.data.browser.BrowserSort
import com.multisuperplayer.core.data.browser.BrowserTrail
import com.multisuperplayer.core.data.subtitle.subtitleFileExtensions
import com.multisuperplayer.core.model.ArtworkSourceRules
import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.ui.artwork.ArtworkImage
import com.multisuperplayer.core.ui.text.string

/**
 * 某个来源里的目录浏览。
 *
 * 面包屑用的是「来路」而不是算出来的路径（见 `BrowserTrail` 的注释），
 * 所以每一段都是**真正走过的**一层：点回去一定回得去，不会出现
 * 「路径拼出来是对的、但那个位置碰不了」的情况。
 *
 * 读不到（[BrowserContent.Unreadable]）和「这里不是目录」
 * （[BrowserContent.NotADirectory]）是**两个不同的出口**，必须分别说：
 * 前者要用户重新授权或换位置，后者说明这个位置在别处被改名/删除了。
 * 合成一句的话，用户会去授权一个根本没坏的地方。
 *
 * [selection] 是这一页的多选接线：`mode` 为真时顶栏换成操作条、行前面出现勾选框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DirectoryBrowser(
    trail: BrowserTrail,
    content: BrowserContent,
    sort: BrowserSort,
    showHidden: Boolean,
    modifier: Modifier = Modifier,
    selection: BrowserSelection = BrowserSelection(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onUp: () -> Unit = {},
    onEnterDirectory: (BrowserEntry) -> Unit = {},
    onOpenCrumb: (Int) -> Unit = {},
    onSortChange: (BrowserSort) -> Unit = {},
    onShowHiddenChange: (Boolean) -> Unit = {},
    onOpenFile: (BrowserEntry) -> Unit = {},
    onOpenSubtitle: (BrowserEntry) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        // 提示条（「已加入 3 项」）挂在**目录页自己的** Scaffold 上：
        // 来源清单是另一个 Scaffold，挂在那边会在进目录后被遮住。
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
                    onPlay = selection.onPlay,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = trail.current.label.string(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                // 同一个按钮两种语义：还在深层时是「上一层」，
                                // 已经在来源根上时才是「回来源列表」。读屏用户
                                // 听到的必须是当下真正会发生的那件事。
                                contentDescription = stringResource(
                                    if (trail.canGoUp) {
                                        R.string.msp_browser_up
                                    } else {
                                        R.string.msp_browser_back_sources
                                    },
                                ),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Sort,
                                contentDescription = stringResource(R.string.msp_library_sort_label),
                            )
                        }
                        BrowserMenu(
                            expanded = menuOpen,
                            sort = sort,
                            showHidden = showHidden,
                            onDismiss = { menuOpen = false },
                            onSortChange = onSortChange,
                            onShowHiddenChange = onShowHiddenChange,
                        )
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            CrumbRow(trail = trail, onOpenCrumb = onOpenCrumb)
            HorizontalDivider()
            BrowserBody(
                content = content,
                selection = selection,
                onEnterDirectory = onEnterDirectory,
                onOpenFile = onOpenFile,
                onOpenSubtitle = onOpenSubtitle,
            )
        }
    }
}

/** 面包屑。横向可滚：深层的路径一定比屏幕宽。 */
@Composable
private fun CrumbRow(trail: BrowserTrail, onOpenCrumb: (Int) -> Unit) {
    val lastIndex = trail.crumbs.lastIndex
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items = trail.crumbs, key = { index, _ -> index }) { index, crumb ->
            if (index > 0) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 最后一段就是当前目录，点了也是原地不动：用纯文本，不给点按反馈。
            // 可点但没反应比不可点更让人困惑。
            if (index == lastIndex) {
                Text(
                    text = crumb.label.string(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            } else {
                TextButton(onClick = { onOpenCrumb(index) }) {
                    Text(text = crumb.label.string(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun BrowserBody(
    content: BrowserContent,
    selection: BrowserSelection,
    onEnterDirectory: (BrowserEntry) -> Unit,
    onOpenFile: (BrowserEntry) -> Unit,
    onOpenSubtitle: (BrowserEntry) -> Unit,
) {
    when (content) {
        // 有 trail 就不可能出现 Idle，但穷举到这里不能留一个空的 else：
        // 「什么都不画」在屏幕上和「正在读」长得一模一样，用户不知道要不要等。
        BrowserContent.Idle,
        BrowserContent.Loading,
        -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        BrowserContent.Unreadable -> EmptyState(
            icon = Icons.Outlined.FolderOff,
            title = stringResource(R.string.msp_browser_unreadable),
            description = stringResource(R.string.msp_browser_unreadable_desc),
        )

        BrowserContent.NotADirectory -> EmptyState(
            icon = Icons.Outlined.FolderOff,
            title = stringResource(R.string.msp_browser_not_directory),
            description = stringResource(R.string.msp_browser_not_directory_desc),
        )

        is BrowserContent.Ready -> Column(modifier = Modifier.fillMaxSize()) {
            if (content.truncated) {
                // 说清「还有更多」而不是「就这些」。不提示的话，用户会以为
                // 缺的那些文件被丢了，而实际上它们只是超出了单目录上限。
                Banner(
                    icon = Icons.Outlined.Info,
                    text = stringResource(R.string.msp_browser_truncated, content.entries.size),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                // 「全是被隐藏的」必须和「真的空」分开：前者关掉隐藏开关
                // 立刻就有东西，后者关不关都一样。
                content.onlyHidden -> EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.msp_browser_only_hidden),
                    description = stringResource(R.string.msp_browser_only_hidden_desc),
                )

                content.empty -> EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.msp_browser_empty_dir),
                    description = stringResource(R.string.msp_browser_empty_dir_desc),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = content.entries, key = { it.ref }) { entry ->
                        BrowserEntryRow(
                            entry = entry,
                            selected = entry.mediaId in selection.ids,
                            selectionMode = selection.mode,
                            onToggleSelection = { selection.onToggle(entry.mediaId) },
                            onEnterDirectory = onEnterDirectory,
                            onOpenFile = onOpenFile,
                            onOpenSubtitle = onOpenSubtitle,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一行目录项。
 *
 * 标题用**原名**而不是 `displayTitle`（去掉后缀的那个）：在文件管理器里
 * 后缀本身就是信息——`.srt` 和 `.lrc` 的区别、`.mp4` 和 `.mkv` 的区别，
 * 恰恰是用户在这一页要找的东西。去后缀是媒体库那一边的美化规则。
 *
 * ## 点一下会发生什么
 *
 * 三种条目对应三个动作，分流**必须在界面层做完**：目录是进下一层，字幕文件是
 * 「给正在看的那个片子当外挂字幕」，其余文件是「交给播放器」。
 * 字幕这一支尤其不能混：`.srt` 自己没有画面，当成媒体交给播放器只会弹一句失败，
 * 而用户在这一页点它的**唯一**意图就是选字幕。
 *
 * ## 多选
 *
 * 多选态下单击是切换选中，**不再是**进入目录或播放：这时候整页的动作是
 * 「对选中的一批做什么」，忽然跳到另一个目录会把用户正在选的东西丢在背后。
 *
 * 长按**可选的**那一行才会进多选（与媒体库页同一个手势）。目录和字幕行长按没反应：
 * 它们本来就不进队列，让它们进去只会得到一个「已选 0 项、某一行却画着对勾」的界面
 * （实测过的坑）——一个按不动的勾选框比没有勾选框更难理解。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserEntryRow(
    entry: BrowserEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onEnterDirectory: (BrowserEntry) -> Unit,
    onOpenFile: (BrowserEntry) -> Unit,
    onOpenSubtitle: (BrowserEntry) -> Unit,
) {
    val directory = entry.isDirectory
    // 字幕的后缀清单只有一份（`subtitleFileExtensions`）：自动查找和手动指定必须
    // 对「什么算字幕」给出同一个答案，不然会出现「同目录里扫得到、在这里却点不着」。
    val subtitle = entry.isFile && entry.extension in subtitleFileExtensions
    // 可选的才是「会被交给播放器/播放列表」的那一批。目录和字幕进不了队列，
    // 所以它们既不能被勾，也不给选中底色——看得见却做不了任何事的勾选框比
    // 没有勾选框更难理解。
    val selectable = entry.playable
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.combinedClickable(
            onClick = {
                if (selectionMode) {
                    // 禁用状态的勾选框不该能吃点击：目录行在多选态里是「看得见、
                    // 但没事可做」，所以点它什么也不发生。
                    if (selectable) onToggleSelection()
                } else {
                    when {
                        directory -> onEnterDirectory(entry)
                        subtitle -> onOpenSubtitle(entry)
                        else -> onOpenFile(entry)
                    }
                }
            },
            onLongClick = { if (!selectionMode && selectable) onToggleSelection() },
        ),
    ) {
        ListItem(
            leadingContent = {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = null, enabled = selectable)
                } else {
                    // 目录用文件夹图标；文件按类型画（视频/音频/字幕/其他）。
                    // 三种一眼可分，比统一的文件图标多一层信息，代价只有一个 `when`。
                    val icon = when {
                        directory -> Icons.Outlined.FolderOpen
                        // 字幕图标只有 Filled 一种变体（Material Icons 没给 Outlined）。
                        // 与播放页控制栏上的字幕按钮用同一个，用户一眼能对上。
                        subtitle -> Icons.Filled.Subtitles
                        else -> entry.kind?.icon ?: Icons.AutoMirrored.Outlined.Article
                    }
                    // 目录/可播放/字幕用 primary，其余用 onSurfaceVariant。
                    // 有真封面的时候不再套这层颜色（那只会让图看着发暗），
                    // 所以它只用在回退图标上。
                    val tint = if (directory || entry.playable || subtitle) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    // 只有可播放的音视频能拿到请求（`requestFor` 内部要求 `playable`），
                    // 所以目录行和字幕行走的一定还是上面那个图标，
                    // 它们在文件浏览器里的样子和以前完全一样。
                    val request = ArtworkSourceRules.requestFor(entry)
                    if (request == null) {
                        Icon(imageVector = icon, contentDescription = null, tint = tint)
                    } else {
                        ArtworkImage(
                            request = request,
                            fallbackIcon = icon,
                            contentDescription = null,
                            modifier = Modifier.size(ArtworkSizes.LIST),
                            shape = ArtworkSizes.LIST_SHAPE,
                            fallbackIconSize = ArtworkSizes.LIST_ICON,
                            // 这一行的底色跟着选中态变（surface / secondaryContainer），
                            // 这里画不出正确的那个，所以底色留给行自己。
                            fallbackContainer = Color.Transparent,
                            // `tint` 必须一起传下来。可播放的文件**一定**走这个分支
                            // （`request` 不为空 = 它可播放），所以不传就等于把
                            // 「紫色 = 能播」这条提示从恰好没有封面的文件上抹掉：
                            // 它们会退回 `ArtworkImage` 的默认色（onSurfaceVariant），
                            // 和旁边不能播的文件长得一模一样。取不到真图时垫在底层
                            // 的正是这个图标，所以它才是这条颜色唯一的落点。
                            fallbackTint = tint,
                        )
                    }
                }
            },
            headlineContent = {
                Text(text = entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            // 后缀用大写显示：它在视觉上是「标签」而不是句子的一部分，
            // 而且在小字号下大写比小写更容易和文件名分开。
            trailingContent = when {
                directory -> {
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                entry.extension.isNotEmpty() -> {
                    {
                        Text(
                            text = entry.extension.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> null
            },
        )
    }
}

/**
 * 排序 + 显示隐藏文件。
 *
 * 合成一个菜单：两个都是「这一页list怎么排列」，分成两个按钮会让顶栏更挤，
 * 而它们的使用频率一样低（看过一次就不会再动）。
 */
@Composable
private fun BrowserMenu(
    expanded: Boolean,
    sort: BrowserSort,
    showHidden: Boolean,
    onDismiss: () -> Unit,
    onSortChange: (BrowserSort) -> Unit,
    onShowHiddenChange: (Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        BrowserSort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(text = option.label.string()) },
                // 用「打个勾」而不是「高亮当前项」表示选中：高亮在浅色主题下
                // 几乎看不出来，用户没法确认自己现在选的是哪一个。
                leadingIcon = if (option == sort) {
                    { Icon(imageVector = Icons.Outlined.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    onSortChange(option)
                    onDismiss()
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = stringResource(R.string.msp_browser_show_hidden)) },
            leadingIcon = if (showHidden) {
                { Icon(imageVector = Icons.Outlined.Check, contentDescription = null) }
            } else {
                null
            },
            onClick = {
                onShowHiddenChange(!showHidden)
                onDismiss()
            },
        )
    }
}
