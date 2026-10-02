package com.multisuperplayer.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.model.MediaEntry
import org.koin.androidx.compose.koinViewModel

/**
 * 最近播放入口（有状态）。
 */
@Composable
fun RecentRoute(
    modifier: Modifier = Modifier,
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    val viewModel: RecentViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 这一页的内容会被外部改变：去播放器页播完一条再回来、在设置里开关「记录最近播放」。
    // 而这个 ViewModel 挂在导航栈上的「最近」入口上，切标签时导航栈**不会**销毁它
    // （见 `MspApp` 里关于 saveState/restoreState 的注释），所以 `init` 里那一次读取
    // 只代表应用刚启动时的样子——不在这里重读，用户看到的永远是旧快照：记录明明
    // 写进了磁盘，界面上却没有，长得完全像「没保存」。
    //
    // 用 `ON_RESUME` 而不是 `LaunchedEffect`：后者只在进入组合时跑一次，而这一页
    // 在切标签时**不会**离开组合（Scaffold 里的导航容器只换内容）。与 `BrowseScreen`
    // 同一套写法，两处要保持一致。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    RecentScreen(
        state = state,
        modifier = modifier,
        onRefresh = viewModel::refresh,
        onPlayRequest = onPlayRequest,
    )
}

/**
 * 最近播放（无状态）。
 *
 * 顺序完全交给数据层（`RecentPlayRules` 按播放时间倒序），这里不再排一次：
 * 两处排序规则迟早会分叉，而「最近播放的顺序不对」这种事用户立刻就会注意到。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen(
    state: RecentUiState,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit = {},
    onPlayRequest: (entries: List<MediaEntry>, startIndex: Int) -> Unit = { _, _ -> },
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_recent_title)) },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.msp_recent_refresh),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val rows = state.rows
            when {
                state.disabled -> EmptyState(
                    icon = Icons.Outlined.VisibilityOff,
                    title = stringResource(R.string.msp_recent_disabled_title),
                    description = stringResource(R.string.msp_recent_disabled_desc),
                )

                state.blocked -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.msp_recent_blocked_title),
                    description = stringResource(R.string.msp_recent_blocked_desc),
                )

                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                rows.isNullOrEmpty() -> EmptyState(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.msp_recent_empty_title),
                    description = stringResource(R.string.msp_recent_empty_desc),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> row.entry.id }) { index, row ->
                        MediaEntryRow(
                            entry = row.entry,
                            // 位置为 0 是**一条正常记录**（刚播过，但下次从头播：短片、
                            // 或播到只剩尾巴的那种）。显示「播放到 00:00」会被读成
                            // 「没记下位置」，所以这一支单独给一句话。
                            supporting = if (row.positionMs <= 0L) {
                                stringResource(
                                    R.string.msp_recent_supporting_from_start,
                                    TimeFormat.dateTime(row.playedAtMs),
                                )
                            } else {
                                stringResource(
                                    R.string.msp_recent_supporting,
                                    TimeFormat.clock(row.positionMs),
                                    TimeFormat.dateTime(row.playedAtMs),
                                )
                            },
                            onClick = { onPlayRequest(rows.map { it.entry }, index) },
                        )
                    }
                }
            }
        }
    }
}
