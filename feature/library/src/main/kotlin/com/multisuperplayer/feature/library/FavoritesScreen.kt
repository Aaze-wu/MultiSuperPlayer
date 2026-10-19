package com.multisuperplayer.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.favorites.Favorite
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 收藏页入口（有状态）。
 *
 * 和其余页面一样拆成 `Route` + 无状态 [FavoritesScreen]：收藏页的五个分支
 * （读盘中、空、只有文件、只有文件夹、混排）都能直接预览，不必真的先攒一堆收藏。
 */
@Composable
fun FavoritesRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
    onOpenFolder: (Favorite.Folder) -> Unit = {},
) {
    val viewModel: FavoritesViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    FavoritesScreen(
        state = state,
        modifier = modifier,
        message = message,
        onBack = onBack,
        onPlayRequest = onPlayRequest,
        onOpenFolder = onOpenFolder,
        onRemove = viewModel::remove,
    )
}

/**
 * 收藏列表（无状态）。
 *
 * 文件和文件夹混排、顺序就是收藏时间倒序（存储层保证），这一页**不提供排序和搜索**：
 * 上限是 200 条，滚动比找排序按钮快；而且收藏的顺序本身就是用户的心智模型
 * （「我刚收藏的那个在最上面」）。真到了要靠排序才能找到的程度，该做的是
 * 把它从收藏里删掉，而不是给这一页加一个工具栏。
 *
 * 文件夹那一行点进去是「浏览」页的那个目录——复用浏览页的面包屑、排序、多选，
 * 而不是在收藏页里再造一个只读的目录列表。所以点文件夹会**离开这一页**。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    state: FavoritesUiState,
    modifier: Modifier = Modifier,
    message: UiMessage? = null,
    onBack: () -> Unit = {},
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
    onOpenFolder: (Favorite.Folder) -> Unit = {},
    onRemove: (key: String) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = message?.text?.string()
    LaunchedEffect(message?.nonce) {
        val text = messageText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
    }

    // 播放队列只含文件，起始下标也必须是文件序列里的下标（见 FavoritesUiState.fileEntries）。
    val queue = state.fileEntries

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_favorites_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.msp_library_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.isEmpty -> EmptyState(
                    icon = Icons.Outlined.StarBorder,
                    title = stringResource(R.string.msp_favorites_empty_title),
                    description = stringResource(R.string.msp_favorites_empty_desc),
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(
                        items = state.favorites.orEmpty(),
                        key = { _, favorite -> favorite.key },
                    ) { _, favorite ->
                        when (favorite) {
                            is Favorite.File -> {
                                val entry = remember(favorite) { favorite.item.toEntry() }
                                // indexOf 在文件序列里找：混排列表的下标会让「接着播下一条」
                                // 跳到另一个位置（中间还夹着文件夹）。
                                val index = queue.indexOfFirst { it.id == entry.id }
                                MediaEntryRow(
                                    entry = entry,
                                    onClick = {
                                        if (index >= 0) onPlayRequest(queue, index)
                                    },
                                    trailing = {
                                        EntryTrailing {
                                            FavoriteStar(
                                                favorited = true,
                                                onClick = { onRemove(favorite.key) },
                                            )
                                        }
                                    },
                                )
                            }
                            is Favorite.Folder -> FolderRow(
                                folder = favorite,
                                onClick = { onOpenFolder(favorite) },
                                onRemove = { onRemove(favorite.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 文件夹那一行。
 *
 * 副标题是**整条路径**（面包屑拼起来），不是只有目录名：收藏两个都叫 `Music`
 * 的目录时，只有路径能让人分清哪个是哪个。路径用 ` / ` 拼，和浏览页的面包屑同一种读法。
 */
@Composable
private fun FolderRow(
    folder: Favorite.Folder,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val path = remember(folder) { folder.crumbs.joinToString(" / ") { it.label } }
    ListItem(
        headlineContent = {
            Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            // 颜色刻意用 onSurfaceVariant 而不是主题色：这一行的图标不是可点的
            // 按钮，只是「这是文件夹」的记号。
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = { FavoriteStar(favorited = true, onClick = onRemove) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * 行尾的星标。
 *
 * 收藏页里它恒为实心（这一页上的每一条都是收藏），点它 = 取消收藏。
 * 实心星标同时也是「已收藏」这个状态的说明，所以它是**唯一**的取消入口——
 * 再来一个「⋯」菜单只会让这一行多一次点击。
 */
@Composable
private fun FavoriteStar(favorited: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (favorited) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            contentDescription = stringResource(
                if (favorited) R.string.msp_favorites_remove else R.string.msp_favorites_add,
            ),
            tint = if (favorited) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
