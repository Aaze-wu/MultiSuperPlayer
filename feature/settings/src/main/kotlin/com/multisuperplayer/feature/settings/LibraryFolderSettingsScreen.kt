package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

/**
 * 设置 › 媒体库 › 只看哪些文件夹。
 *
 * ## 为什么这一页是个真的导航目的地
 *
 * 勾选动作要能一次次撤销，用户还得能一眼看到自己勾的是哪一层目录；塞进弹窗
 * 里的话，长清单会变成一个占半屏的滚动框，而它底下那页什么都不能做。所以它
 * 和「长文件名滚动」一样是独立的子页，返回键只回设置首页（路由见 `MspApp.kt`）。
 *
 * ## 这一页不改文件
 *
 * 所有勾只影响**媒体库列表**显不显示，不动磁盘上的任何东西：没勾的文件夹依然
 * 能在「浏览」页里翻到、能播、能被别的应用看到。这句话必须在页面上说出来，
 * 否则「取消勾选」看起来像是在删文件（而这一页的用词是「勾选」，很容易被读成
 * 「删除」）。
 *
 * ## 折叠只管「画几行」
 *
 * 行尾的箭头把一层子目录收起来，但它与勾选**互不影响**：收起来的子树里的文件
 * 该进库还是进库（否则「收起来」会被当成「不选」）。首屏默认只展开根，免得目录
 * 一多就要滚很远（见 [defaultCollapsedPaths]）。
 */
@Composable
fun LibraryFolderSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryFolderSettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LibraryFolderSettingsScreen(
        state = state,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onClearAll = viewModel::clearAll,
        onToggleExpanded = viewModel::toggleExpanded,
        modifier = modifier,
    )
}

/** 无 ViewModel 版本，方便预览与单测喂状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFolderSettingsScreen(
    state: LibraryFolderSettingsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onToggle: (String) -> Unit = {},
    onClearAll: () -> Unit = {},
    onToggleExpanded: (String) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_library_folders)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.msp_settings_back),
                        )
                    }
                },
                actions = {
                    // 「全不选」只在真有勾的时候出现：一个按下去什么都不会变的按钮
                    // 只会让人怀疑自己点漏了（与首页「恢复所有提示」同一规则）。
                    // 系统不支持时也不出现：勾本身就不生效，清空更没有意义。
                    if (state.selectedCount > 0 && !state.unsupported) {
                        TextButton(onClick = onClearAll) {
                            Text(stringResource(R.string.msp_settings_library_folders_clear))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // 首屏的说明分四种口吻：系统不支持（这功能在这台设备上不生效）/ 读不出来
            // （别去动勾）/ 一个都没勾（媒体库现在是空的）/ 正常（勾了的才进库）。
            // 全写成同一句「选择要显示的文件夹」等于什么都没说。
            // 「不支持」排在最前：它是平台硬事实，任何一句别的话都会把用户引到一条死路上。
            item(key = "note") {
                InfoNote(
                    when {
                        state.unsupported -> stringResource(R.string.msp_settings_library_folders_unsupported_note)
                        state.unknown -> stringResource(R.string.msp_settings_library_folders_unknown)
                        state.selectedCount == 0 -> stringResource(R.string.msp_settings_library_folders_none)
                        else -> stringResource(R.string.msp_settings_library_folders_note)
                    },
                )
            }
            when {
                // 这几个状态都不该让人在空列表上乱点：原因不同，要做的事也不同。
                state.needsPermission -> item(key = "permission") {
                    InfoNote(stringResource(R.string.msp_settings_library_folders_needs_permission))
                }
                state.failed -> item(key = "failed") {
                    InfoNote(stringResource(R.string.msp_settings_library_folders_failed))
                }
                state.loading -> item(key = "loading") {
                    InfoNote(stringResource(R.string.msp_settings_library_folders_loading))
                }
                state.rows.isEmpty() -> item(key = "empty") {
                    InfoNote(stringResource(R.string.msp_settings_library_folders_empty))
                }
                else -> {
                    item(key = "header") {
                        SectionHeader(stringResource(R.string.msp_settings_library_folders_section))
                    }
                    // 画可见行（`state.rows` 去掉收起来的子树的子孙）；
                    // 勾选算的还是整棵树，见 `LibraryFolderSettingsUiState.visibleRows`。
                    items(state.visibleRows, key = { it.path }) { row ->
                        FolderRowItem(
                            row = row,
                            expanded = row.path !in state.collapsed,
                            onToggle = onToggle,
                            onToggleExpanded = onToggleExpanded,
                            // 不支持时行治灰：勾了也不会生效，能点比没得点更难理解。
                            enabled = !state.unsupported,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个文件夹（一行）。
 *
 * 行分三块：勾选框（只管「算不算数」）、名字 + 文件数（整行点击 = 切勾）、
 * 行尾箭头（只管「看不看见子目录」）。两个动作必须分开：把折叠做成「点整行展开」，
 * 用户想勾一个父目录时就会莫名其妙地展开/收起。
 *
 * 折叠在这里**没有**「收起来就等于不选」的含义：勾父目录时所有子孙都显示成勾，
 * 那是**过滤结果**，不是「用户点过哪几行」；收起一棵子树之后这些勾只是暂时看不见，
 * 打开抽屉再看就还在（默认展开已勾选那条链，见 [defaultCollapsedPaths]）。
 */
@Composable
private fun FolderRowItem(
    row: FolderRow,
    expanded: Boolean,
    onToggle: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    enabled: Boolean = true,
) {
    // 缩进放在 fillMaxWidth 之前：反过来（先铺满再 pad）会让行本身超出边界，
    // 点击热区跟着挪到屏幕外一截。
    ListItem(
        modifier = Modifier
            .padding(start = (row.depth * 16).dp)
            .fillMaxWidth()
            .toggleable(
                value = row.checked,
                // `enabled = false` 时整行既不可点、也不会有按下反馈，
                // 但勾选状态照旧显示（用户能看出库里本来勾了什么）。
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle(row.path) },
            ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            // `onCheckedChange = null`：整行都在 toggleable 里，Switch/Checkbox
            // 自己再响一次会变成「点一下勾两次」（与 SettingsSwitchRow 同理）。
            Checkbox(checked = row.checked, onCheckedChange = null, enabled = enabled)
        },
        headlineContent = {
            Text(
                text = if (row.path.isEmpty()) {
                    // 卷根没有名字（名字是 `""`），但它是**唯一**一个「整个存储」的勾，
                    // 空着不写会变成一个没有标题的复选框。
                    stringResource(R.string.msp_settings_library_folder_root)
                } else {
                    row.name
                },
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(
                    R.string.msp_settings_library_folder_files,
                    row.totalCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            // 叶子行不给按钮：一个按下去什么都不会变的箭头，比没有箭头更让人困惑。
            if (row.hasChildren) {
                // 这里**故意**不看 `enabled`：箭头改的是「画几行」，跟过滤能不能生效无关。
                // 系统不支持时勾变灰了，清单还是该能收起来。
                IconButton(onClick = { onToggleExpanded(row.path) }) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.KeyboardArrowDown
                        } else {
                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                        },
                        contentDescription = stringResource(
                            if (expanded) {
                                R.string.msp_settings_library_folder_collapse
                            } else {
                                R.string.msp_settings_library_folder_expand
                            },
                        ),
                        // 与勾选框同色系：箭头是辅助动作，不该比勾更抢眼。
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
