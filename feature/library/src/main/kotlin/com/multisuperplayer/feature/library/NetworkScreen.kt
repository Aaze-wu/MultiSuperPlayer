package com.multisuperplayer.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.remote.RemoteUrlRules
import com.multisuperplayer.core.model.MediaEntry
import org.koin.androidx.compose.koinViewModel

/**
 * 网络地址页（手输链接直接播放）。
 *
 * 它住在 `feature:library` 里、从浏览页进来，而不是单独一个标签页：这一页做的事
 * 和「授权一个文件夹」是同一类——**告诉应用从哪儿找片子**。多一个底部标签意味着
 * 底部栏永远多一格，而绝大多数用不到它的人每次都要多看它一眼。
 *
 * ## 为什么播完不留下来
 *
 * 点一条就跳播放页（和媒体库、浏览页、最近播放完全同一条路），历史记录留在原地。
 * 用户「再放一次刚才那一条」靠的是列表里的那一行，而不是返回这一页。
 */
@Composable
fun NetworkRoute(
    onPlayRequest: (List<MediaEntry>, Int) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: NetworkViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 「记进历史」和「开始播放」在一次点击里必须成对发生：
    //
    // - 只记不放，用户按了没反应；
    // - 只放不记，这条地址下次就得重新敲一遍——而「重新敲一遍」正是历史存在的理由。
    //
    // 顺序是先记后放：`remember` 只是往 `viewModelScope` 扔一个写盘任务，不阻塞；
    // 而 `onPlayRequest` 会导航走，ViewModel 所在的返回栈条目可能很快被销毁。
    val play: (String) -> Unit = { url ->
        viewModel.remember(url)
        onPlayRequest(listOf(RemoteUrlRules.toEntry(url)), 0)
    }

    NetworkScreen(
        state = state,
        modifier = modifier,
        onInputChange = viewModel::setInput,
        onPlay = play,
        onRemove = viewModel::remove,
        onBack = onBack,
    )
}

/**
 * 网络地址页本体。
 *
 * 分成「Route（接 ViewModel）」和「Screen（纯参数）」两层是这一层的既有写法：
 * 单测能直接给 Screen 喂一个状态，不必先把 Koin 装起来
 * （`feature:library` 已经有 `BrowseUiStateTest` 这类用例）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkScreen(
    state: NetworkUiState,
    modifier: Modifier = Modifier,
    onInputChange: (String) -> Unit = {},
    onPlay: (String) -> Unit = {},
    onRemove: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_network_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.msp_library_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            NetworkUrlField(state = state, onInputChange = onInputChange, onPlay = onPlay)

            Text(
                text = stringResource(R.string.msp_network_history),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )

            // 三态：还在读 / 读到了但一条都没有 / 有内容。
            // `history == null` 不能并进「空列表」那一支：DataStore 第一次发射要走磁盘，
            // 中间那一瞬间界面会闪一下「还没有播放过网络地址」——而这句是**假话**。
            val history = state.history
            when {
                history == null -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                history.isEmpty() -> Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Public,
                        title = stringResource(R.string.msp_network_empty_title),
                        description = stringResource(R.string.msp_network_empty_desc),
                    )
                }

                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    items(items = history, key = { it }) { url ->
                        NetworkUrlRow(
                            url = url,
                            onPlay = { onPlay(url) },
                            onRemove = { onRemove(url) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 地址输入框。
 *
 * 反馈是**即时**的：`isError` 跟着 [NetworkUiState.invalid] 走，而它每次都由
 * [RemoteUrlRules.normalize] 现算。等到用户按下播放才告诉他「这个地址不对」，
 * 是把一次可以当场改掉的错拖到了另一屏（点播放会直接跳播放页，那里只显示播放失败）。
 *
 * 键盘上的「完成」键（[ImeAction.Go]）和右边的播放按钮是同一个动作，两个都要有：
 * 外接键盘的用户不会去点那个图标，而单手拿手机的人敲完地址就在键盘上方。
 */
@Composable
private fun NetworkUrlField(
    state: NetworkUiState,
    onInputChange: (String) -> Unit,
    onPlay: (String) -> Unit,
) {
    val url = state.url
    OutlinedTextField(
        value = state.input,
        onValueChange = onInputChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        singleLine = true,
        label = { Text(stringResource(R.string.msp_network_input_label)) },
        isError = state.invalid,
        supportingText = {
            Text(
                text = if (state.invalid) {
                    stringResource(R.string.msp_network_invalid)
                } else {
                    stringResource(R.string.msp_network_input_hint)
                },
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { url?.let(onPlay) }),
        trailingIcon = {
            IconButton(onClick = { url?.let(onPlay) }, enabled = url != null) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.msp_network_play),
                )
            }
        },
    )
}

/**
 * 历史里的一行：主标题是推断出来的名字，副标题是完整地址。
 *
 * 两个都要：只给名字的话，同一个文件名在两个不同服务器上没法区分；只给地址的话，
 * 一屏十条 `https://…/…/…mp4` 里认不出哪条是哪条。名字用 [RemoteUrlRules.titleOf]
 * （去掉路径里的目录和前一个后缀），它和**媒体库、播放页看到的是同一个名字**——
 * 取名字的规则只有一处，改了不会只有一边跟着变。
 */
@Composable
private fun NetworkUrlRow(
    url: String,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onPlay),
        leadingContent = {
            Icon(
                Icons.Outlined.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = {
            Text(
                text = RemoteUrlRules.titleOf(url),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.msp_network_remove),
                )
            }
        },
    )
}
