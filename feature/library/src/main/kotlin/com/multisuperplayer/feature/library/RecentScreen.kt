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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
                            supporting = stringResource(
                                R.string.msp_recent_supporting,
                                TimeFormat.clock(row.positionMs),
                                TimeFormat.dateTime(row.playedAtMs),
                            ),
                            onClick = { onPlayRequest(rows.map { it.entry }, index) },
                        )
                    }
                }
            }
        }
    }
}
