package com.multisuperplayer.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.player.MspPlaybackState
import com.multisuperplayer.core.player.progressOf
import kotlinx.coroutines.flow.StateFlow

/**
 * 迷你播放器：贴在底部导航栏上面的一条。
 *
 * 它取代了「正在播放」这个标签。原来那一版的问题是：用户在媒体库里翻歌时
 * 看不到正在放什么，必须切到播放页——而切过去之后，媒体库的滚动位置、
 * 筛选词都没了（导航栈被压回起点）。
 *
 * 所以这里的原则是「不打断」：
 *
 * 1. 它**不占用**任何标签位，只是叠在导航栏上方；
 * 2. 点它才进播放页，其余时候用户该干嘛干嘛；
 * 3. 全屏播放时整条 bottomBar（含它）一起消失，见 `MspApp` 里的说明。
 *
 * 只放「标题 + 一行副标题 + 播放/暂停 + 一条进度细线」，不放时间码。
 * 时间码每 200ms 变一次，放上来会让人盯着一个跳动的数字看，而那正是
 * 播放页存在的意义。
 */
@Composable
internal fun MiniPlayer(
    entry: MediaEntry,
    state: MspPlaybackState,
    position: StateFlow<Long>,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 进度线放在最上面：贴着导航栏说「这是播放状态」，
            // 而且它只有 2dp 高，不会把这一条撑胖。
            MiniPlayerProgressLine(position = position, durationMs = state.durationMs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title.ifBlank { entry.id },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 空副标题不占位：视频条目往往没有 artist/album，
                    // 留一行空白只会让这一条显得没对齐。
                    if (entry.subtitle.isNotBlank()) {
                        Text(
                            text = entry.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (state.isPlaying) {
                            Icons.Filled.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        },
                        // 图标本身说明了动作，但无障碍读屏需要一句话。
                        contentDescription = if (state.isPlaying) {
                            stringResource(R.string.msp_mini_player_pause)
                        } else {
                            stringResource(R.string.msp_mini_player_play)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 进度细线。
 *
 * **单独做成一个组件**，好让「每 200ms 重组一次」只发生在这里。
 * 位置读在谁身上，谁就跟着重组；读在 [MiniPlayer] 里的话，
 * 标题、副标题、按钮每秒都要跟着重画五遍，而它们一个字都没变。
 */
@Composable
private fun MiniPlayerProgressLine(position: StateFlow<Long>, durationMs: Long) {
    val currentMs by position.collectAsStateWithLifecycle()
    val progress = progressOf(currentMs, durationMs)

    // 时长未知（直播流、元数据还没解析出来）时画一条静态的空线，
    // 而不是画一条永远停在 0 的进度条——后者看起来像「卡在开头」。
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
    )
}
