package com.multisuperplayer.feature.library

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.log.MspLog
import org.koin.androidx.compose.koinViewModel

private const val BROWSE_TAG = "BrowseRoute"

/**
 * 浏览（SAF 授权目录）入口（有状态）。
 *
 * 这里有两件只有界面层才能做的事：
 *
 * 1. **系统目录选择器**：它是个 Activity，只能在 Activity 作用域里起（`rememberLauncherForActivityResult`）。
 * 2. **持久化授权**：选择器只在回调里给一次临时授权，必须自己
 *    `takePersistableUriPermission` 一次才能跨重启有效。这一步失败**必须记日志**——
 *    不记的话，用户看到的现象会是「添加成功了，但重启后这个目录里什么都没有」，
 *    而这条日志是唯一能把它和「目录本来就是空的」区分开的东西。
 *
 * 移除时反过来 `releasePersistableUriPermission`：不释放的话，系统设置里那份
 * 授权会一直挂着（用户以为已经撤销了），而我们这边已经没有它的记录了。
 * 释放失败不算错误（可能本来就没持久化成功），记日志继续。
 */
@Composable
fun BrowseRoute(modifier: Modifier = Modifier) {
    val viewModel: BrowseViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { error ->
            MspLog.w(BROWSE_TAG) { "持久化目录授权失败：$uri（$error）" }
        }
        viewModel.addTree(uri.toString())
    }

    BrowseScreen(
        state = state,
        modifier = modifier,
        onAddTree = { picker.launch(null) },
        onRemoveTree = { uri ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { error ->
                MspLog.w(BROWSE_TAG) { "释放目录授权失败：$uri（$error）" }
            }
            viewModel.removeTree(uri)
        },
    )
}

/**
 * 浏览（无状态）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    modifier: Modifier = Modifier,
    onAddTree: () -> Unit = {},
    onRemoveTree: (String) -> Unit = {},
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
            // 这一行说明是必须的：否则用户会以为「媒体库里的内容」也需要在这里
            // 一个个授权，而实际上系统媒体库的部分本来就已经在媒体库页里了。
            Banner(
                icon = Icons.Outlined.Info,
                text = stringResource(R.string.msp_browse_hint),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.fillMaxSize()) {
                val trees = state.trees
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    trees.isNullOrEmpty() -> EmptyState(
                        icon = Icons.Outlined.FolderOpen,
                        title = stringResource(R.string.msp_browse_empty_title),
                        description = stringResource(R.string.msp_browse_empty_desc),
                        actionLabel = stringResource(R.string.msp_browse_add),
                        onAction = onAddTree,
                    )

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(trees, key = { it.info.uri }) { tree ->
                            BrowseTreeRow(
                                tree = tree,
                                onRemove = { onRemoveTree(tree.info.uri) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseTreeRow(tree: BrowseTree, onRemove: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = tree.info.label
                    ?: stringResource(R.string.msp_browse_volume_root),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            if (tree.accessible) {
                // 只显示 uri 而不做「美化」：撤销授权之后这串东西是用户
                // 唯一能拿去和系统设置里那条授权对上号的线索。
                Text(tree.info.uri, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(
                    text = stringResource(R.string.msp_browse_tree_inaccessible),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (tree.accessible) {
                    Icons.Outlined.FolderOpen
                } else {
                    Icons.Outlined.FolderOff
                },
                contentDescription = null,
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.msp_browse_remove),
                )
            }
        },
    )
}
