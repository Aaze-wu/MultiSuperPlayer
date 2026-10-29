package com.multisuperplayer.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import com.multisuperplayer.core.ui.text.ScrollingTitleText
import com.multisuperplayer.core.ui.text.TitleScrollPage
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
    /** 子文件夹的直接子项数量，键是 `BrowserEntry.ref`。见 `BrowseUiState.folderCounts`。 */
    folderCounts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier,
    selection: BrowserSelection = BrowserSelection(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** 当前目录是否已收藏（顶栏那颗星是实心还是空心）。 */
    folderFavorited: Boolean = false,
    onToggleFolderFavorite: () -> Unit = {},
    onUp: () -> Unit = {},
    onEnterDirectory: (BrowserEntry) -> Unit = {},
    onOpenCrumb: (Int) -> Unit = {},
    onSortChange: (BrowserSort) -> Unit = {},
    onShowHiddenChange: (Boolean) -> Unit = {},
    onOpenFile: (BrowserEntry) -> Unit = {},
    onOpenSubtitle: (BrowserEntry) -> Unit = {},
    /** 这个目录上次滚到哪儿（键是目录的 `ref`）；null = 没去过，按顶格。 */
    scrollPositionOf: (String) -> BrowseScrollPosition? = { null },
    /** 目录列表滚了，把「停在哪儿」交出去。 */
    onScrollPositionChanged: (String, BrowseScrollPosition) -> Unit = { _, _ -> },
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
                    onFavorite = selection.onFavorite,
                    favoriteAll = selection.favoriteAll,
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
                        // 收藏「当前这个目录」。放在排序按钮**左边**：
                        // 排序是每一次浏览都要用的、收藏是一个偶尔做一次的动作，
                        // 常驻的那个该在内侧。
                        IconButton(onClick = onToggleFolderFavorite) {
                            Icon(
                                imageVector = if (folderFavorited) {
                                    Icons.Outlined.Star
                                } else {
                                    Icons.Outlined.StarBorder
                                },
                                contentDescription = stringResource(
                                    if (folderFavorited) {
                                        R.string.msp_favorites_remove
                                    } else {
                                        R.string.msp_favorites_add
                                    },
                                ),
                                tint = if (folderFavorited) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        }
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
            // 面包屑和「共 N 项」放在同一行：数字贴右边，滚面包屑时它不跟着跑。
            // 塞进面包屑那一行里当最后一项会变成「深层目录看不到总数」。
            val totalCount = (content as? BrowserContent.Ready)?.totalCount
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CrumbRow(
                    trail = trail,
                    onOpenCrumb = onOpenCrumb,
                    modifier = Modifier.weight(1f),
                )
                // 0 项的时候不写「共 0 项」：下面已经有一句「这个文件夹是空的」，
                // 两个数字叠在一起只是噪音。
                if (totalCount != null && totalCount > 0) {
                    Text(
                        text = stringResource(R.string.msp_browser_count_total, totalCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        // start 侧也留白：面包屑滚到头时文字是**贴着**自己那块区域
                        // 右边缘画的，没有这段间距就和「共 N 项」糊成一句
                        // （实测「测试目录一二三四五六七共 2 项」）。
                        modifier = Modifier.padding(start = 8.dp, end = 12.dp),
                    )
                }
            }
            HorizontalDivider()
            BrowserBody(
                content = content,
                // 这一层是谁：滚动位置就按它记。用 `trail.current.ref` 而不是路径，
                // 理由见 `BrowserScrollMemory`。
                directoryRef = trail.current.ref,
                scrollPositionOf = scrollPositionOf,
                onScrollPositionChanged = onScrollPositionChanged,
                folderCounts = folderCounts,
                selection = selection,
                onEnterDirectory = onEnterDirectory,
                onOpenFile = onOpenFile,
                onOpenSubtitle = onOpenSubtitle,
            )
        }
    }
}

/**
 * 面包屑。横向可滚：深层的路径一定比屏幕宽。
 *
 * ## 停到「当前目录」那一端
 *
 * 不滚的话，深层路径从最左端开始画，屏幕上只剩「内部存储 › Download › …」，
 * 而用户最需要看见的那一段（我现在在哪）被挤到右边——路径越长越明显。
 *
 * ## 为什么不是 `LazyRow` + `scrollToItem(lastIndex)`
 *
 * 那个组合只能把最后一段的**开头**对齐到视口左边。总宽一超过视口，滚动量就被
 * 最大值夹住，最后一段的后半截照样被切——实测停在「子目」上，后面直接接上
 * 右边的「共 1 项」，两个东西糊成一句。它也没有「滚到末尾」这个动作可用。
 *
 * 换成普通 [Row] + [rememberScrollState]：`maxValue` 是**测量之后**的确定值，
 * `scrollTo(maxValue)` 就是「右边缘对齐」——最后一段一定完整，不管它多宽、
 * 前面有几段。内容本来就不超宽时 `maxValue` 恒为 0，滚了也是原地不动。
 *
 * 位置是一次落定、不是动作，所以用 `scrollTo` 而不是 `animateScrollTo`：每次
 * 进目录都演一段横向滚动只是噪音。这个 effect 只在 [BrowserTrail.crumbs]
 * 变化时重跑，用户自己划走之后不会被拽回去。
 *
 * ## 为什么必须等一帧
 *
 * `maxValue` 在**测量之前不是真值**：Compose 里它的初始值是 `Int.MAX_VALUE`
 * （「无限，还没量过」），而组合 / 测量 / 绘制是同一帧里接着做的——effect 跑在
 * 测量**之前**。所以「读到 `maxValue` 就滚」读到的是那个占位值，实测结果就是
 * 压根没滚（最后一段只靠省略号收尾，祖先段还完整留在左边）。先
 * `withFrameNanos` 等一帧，那时这一帧的测量已经落定，`maxValue` 才是真的。
 *
 * ## 留白为什么用 Spacer
 *
 * 滚动容器没有 `contentPadding`；`LazyRow` 虽然有一个，但它一旦滚起来，内容会
 * 滑到 padding 区**下面**、被容器 clip 成「贴着屏幕边缘的半个字」（实测最左那段
 * 只剩「三四五六七八九」）。两端各放一个定宽 [Spacer] 才是真的留白。
 */
@Composable
private fun CrumbRow(
    trail: BrowserTrail,
    onOpenCrumb: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastIndex = trail.crumbs.lastIndex
    val scrollState = rememberScrollState()
    LaunchedEffect(trail.crumbs) {
        // 等一帧，让这一帧的测量把 `maxValue` 变成真值（见上面的说明）。
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }
    BoxWithConstraints(modifier = modifier) {
        // 最后一段最多占满整行：超长的目录名要出省略号。不设上限的话它在滚动
        // 容器里是「无界宽」，`TextOverflow.Ellipsis` 永远不会触发，画到视口外
        // 被硬切——切掉之后连省略号都没有，看不出「这里还有字」。
        val lastCrumbMaxWidth = maxWidth - CrumbEdgePadding * 2
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.width(CrumbEdgePadding))
            trail.crumbs.forEachIndexed { index, crumb ->
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
                        modifier = Modifier
                            .widthIn(max = lastCrumbMaxWidth)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    )
                } else {
                    TextButton(onClick = { onOpenCrumb(index) }) {
                        Text(text = crumb.label.string(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(modifier = Modifier.width(CrumbEdgePadding))
        }
        // 滚起来之后最前那段是被容器边缘**硬切**的：实测「内部存储」只剩半截「储」，
        // 半个字的偏旁看上去还像一个**别的字**（「诸」），比缺字更容易读错。
        // 所以不是淡出，而是**盖住**：一个不透明的「…」把残字整个遮掉，右边再补
        // 一小段渐隐当过渡。只在真的滚过之后出现，没滚动时保持干净留白。
        if (scrollState.value > 0) {
            Row(
                // 必须是 matchParentSize（不影响父尺寸），不能用 fillMaxHeight：
                // BoxWithConstraints 的高度由内容决定，fillMaxHeight 会把这一行撑到
                // 屏幕高，面包屑整块变高、把列表顶出可视区。
                modifier = Modifier.matchParentSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(CrumbTailFade)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surface,
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                ),
                            ),
                        ),
                )
            }
        }
    }
}

/** 面包屑两端的留白。滚动容器里只能靠两端的 [Spacer] 撑出来。 */
private val CrumbEdgePadding = 12.dp

/** 「…」右侧那一段渐隐的宽度：够了就行，再宽会吃掉可读内容。 */
private val CrumbTailFade = 12.dp

@Composable
private fun BrowserBody(
    content: BrowserContent,
    /** 当前这个目录是谁（`BrowserTrail.current.ref`），滚动位置按它记。 */
    directoryRef: String,
    scrollPositionOf: (String) -> BrowseScrollPosition?,
    onScrollPositionChanged: (String, BrowseScrollPosition) -> Unit,
    folderCounts: Map<String, Int>,
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

                else -> DirectoryList(
                    entries = content.entries,
                    directoryRef = directoryRef,
                    scrollPositionOf = scrollPositionOf,
                    onScrollPositionChanged = onScrollPositionChanged,
                    folderCounts = folderCounts,
                    selection = selection,
                    onEnterDirectory = onEnterDirectory,
                    onOpenFile = onOpenFile,
                    onOpenSubtitle = onOpenSubtitle,
                )
            }
        }
    }
}

/**
 * 目录里那一列条目。
 *
 * ## 滚动位置为什么必须记到组合外面
 *
 * 切目录时 `content` 会先变成 `Loading`（`BrowserRepository.listing` 第一行就发
 * `Loading`），这一列整棵离开组合，`rememberLazyListState()` 自己跟着没了
 * ⇒ 从子目录返回父目录会回到顶部。所以位置记在外面的
 * [BrowserScrollMemory] 里（由 ViewModel 持有），回来时当初始值重新交给列表。
 *
 * ## 为什么每次滚动都写，而不是「离开组合那一刻」再写
 *
 * `DisposableEffect.onDispose` 跑在 **applyChanges**，而新分支的 `remember` 跑在
 * **composition**：同一个目录直接换一份内容时，新的列表会先读到旧值、旧的才把
 * 真实值写进去，恢复就错一间。`snapshotFlow` 不会遇到这种时序：值一变就写。
 *
 * 写得勤也不怕——这份 Map 没有 `State` 包着，写它**不触发重组**（见
 * [BrowserScrollMemory] 的注释），每帧一次 `Int` 写入的代价可以忽略。
 *
 * ## 为什么还要显式滚一次（`initial…` 不够）
 *
 * `rememberLazyListState` 恢复的是 `rememberSaveable` 里的**实例**。只要这一列
 * 没真正离开组合（切目录时 `Loading` 那一帧被合并掉、或两次内容是同一分支），
 * 实例就被复用，`initialFirstVisibleItemIndex` 这个**构造参数被完全忽略**：
 * 新目录沿用上一个目录的滚动位置。反过来，实例确实重建时 `initial…` 又只在
 * 首帧生效。两种情形都要覆盖 ⇒ 拿记住的位置显式 `scrollToItem` 一次，
 * 跟「这个 state 是谁、创建于哪一刻」彻底无关。
 *
 * ## 为什么恢复期间不写回
 *
 * `snapshotFlow` 的**第一次发射**是「这个新列表还没滚」，也就是 (0, 0)——它不是
 * 用户动作，写回去就把刚恢复的位置抹掉了。这就是「有时记得住、有时记不住」
 * 的来源：抹不抹得掉取决于恢复与首次发射谁先落地。恢复落地之前一律不写。
 */
@Composable
private fun DirectoryList(
    entries: List<BrowserEntry>,
    directoryRef: String,
    scrollPositionOf: (String) -> BrowseScrollPosition?,
    onScrollPositionChanged: (String, BrowseScrollPosition) -> Unit,
    folderCounts: Map<String, Int>,
    selection: BrowserSelection,
    onEnterDirectory: (BrowserEntry) -> Unit,
    onOpenFile: (BrowserEntry) -> Unit,
    onOpenSubtitle: (BrowserEntry) -> Unit,
) {
    // 恢复时按**现在**的条目数夹一下：上次停在第 40 行，这次这个目录只剩 12 项
    // （文件被删了），直接把 40 交给列表会落在一片空白上。
    val restored = remember(directoryRef) {
        scrollPositionOf(directoryRef)?.clampTo(entries.size)
    }
    // 首次创建时靠 initial 直接落位（不闪一帧）；实例被复用时 initial 无效，
    // 由下面的 effect 纠正。两条路都要走。
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = restored?.index ?: 0,
        initialFirstVisibleItemScrollOffset = restored?.offset ?: 0,
    )
    // 恢复还在路上时，`snapshotFlow` 的首次发射是 (0, 0)（「这个新列表还没滚」），
    // 那不是用户动作。放它写回去，刚恢复的位置当场被抹掉——这就是「不是百分百」。
    val restoring = remember(directoryRef) { mutableStateOf(restored != null) }
    LaunchedEffect(listState, directoryRef) {
        // 协程比首帧测量早跑，position 还是 0；`scrollToItem` 会自己等布局依赖。
        if (restored != null) {
            listState.scrollToItem(restored.index, restored.offset)
        }
        restoring.value = false
    }
    LaunchedEffect(listState, directoryRef) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (!restoring.value) {
                    onScrollPositionChanged(directoryRef, BrowseScrollPosition(index = index, offset = offset))
                }
            }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items = entries, key = { it.ref }) { entry ->
            BrowserEntryRow(
                entry = entry,
                folderCount = folderCounts[entry.ref],
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
    /** 子文件夹的直接子项数量；null = 还没数出来 / 数不出来，不显示。 */
    folderCount: Int?,
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
                // 同上：长文件名滚动。`mediaId` 与 `MediaEntry.id` 是同一套 id（见
                // `BrowserSelection`），所以能直接与「当前播的那一条」对齐。
                ScrollingTitleText(
                    text = entry.name,
                    entryId = entry.mediaId,
                    page = TitleScrollPage.BROWSE,
                    selected = selected,
                )
            },
            // 后缀用大写显示：它在视觉上是「标签」而不是句子的一部分，
            // 而且在小字号下大写比小写更容易和文件名分开。
            trailingContent = when {
                directory -> {
                    {
                        // 数量放在箭头**左边**：箭头是「点进去」的方向，必须始终
                        // 贴在行的最右侧、位置不变；数字长短不影响它。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (folderCount != null) {
                                Text(
                                    text = stringResource(
                                        R.string.msp_browser_child_count,
                                        folderCount,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
