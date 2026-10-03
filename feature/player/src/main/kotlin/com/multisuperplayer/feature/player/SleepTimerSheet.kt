package com.multisuperplayer.feature.player

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.player.SleepTimerOptions
import com.multisuperplayer.core.player.SleepTimerRules
import com.multisuperplayer.core.player.SleepTimerState
import com.multisuperplayer.core.ui.text.string
import kotlinx.coroutines.delay

/**
 * 睡眠定时面板。
 *
 * ## 面板上为什么只有档位芯片，没有滑块
 *
 * 「我要睡着了」这个决定本身就很粗（见 [SleepTimerOptions]），滑块会让人以为
 * 可以取任意时长，而拖出来的值最终还是要吸附到某一档上——那种「拖了但没变」的
 * 感觉比少几个选项糟糕得多（倍速面板当初也是这个理由）。
 *
 * ## 「本集结束」为什么和那七档并排，而不单列一格
 *
 * 它就是一个**档位**：用户要么选时间，要么选「放完这一集」。单列出来会显得它
 * 是另一种操作（比如「立即停止」），而它其实是最常用的那一档
 * ——不需要猜自己能几分钟睡着。
 *
 * ## 关掉定时为什么是文字按钮而不是再放一个「关闭」芯片
 *
 * 芯片是**互斥的一组值**，而「关掉」不是一个值：它把所有芯片都取消选中。
 * 混在一排里，用户会以为它和第 8 档是并列的（点了之后「关闭」会变成选中状态）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerSleepTimerSheet(
    state: SleepTimerState,
    onSelectMinutes: (Int) -> Unit,
    onSelectUntilItemEnd: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.msp_player_sleep_timer),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // 当前状态。「没有定时」时这一块**整个不画**（不是画一行「未开启」）：
            // 那一行会被读成「这里有一个可以开关的东西」，而正确的心智是
            // 「现在没有定时，点一个档位才有」。
            SleepTimerStatus(state = state, modifier = Modifier.padding(bottom = 12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 高亮用**相等**档位（`presetFor`）而不是最近档位：内核里的时长不一定
                // 来自这张表（将来有自定义时长），认不出来就整排都不选中——比高亮一个
                // 用户没选过的档位好。
                val selected = SleepTimerOptions.presetFor(state)
                SleepTimerOptions.PRESETS_MINUTES.forEach { minutes ->
                    FilterChip(
                        selected = minutes == selected,
                        onClick = { onSelectMinutes(minutes) },
                        label = { Text(SleepTimerOptions.label(minutes).string()) },
                    )
                }
                FilterChip(
                    selected = state is SleepTimerState.UntilItemEnd,
                    onClick = onSelectUntilItemEnd,
                    label = { Text(SleepTimerOptions.untilItemEndLabel().string()) },
                )
            }

            // 只在真的挂着定时时才给「关掉」：没有定时的时候这个按钮点一下没有任何
            // 效果，而一个点了没反应的按钮比没有按钮更让人困惑。
            if (state !is SleepTimerState.Off) {
                TextButton(onClick = onClear, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = stringResource(R.string.msp_player_sleep_timer_clear),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Text(
                text = stringResource(R.string.msp_player_sleep_timer_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * 面板上那行「现在是什么状态」。
 *
 * 没有定时时什么都不画：见 [PlayerSleepTimerSheet] 里的注释。
 */
@Composable
private fun SleepTimerStatus(state: SleepTimerState, modifier: Modifier = Modifier) {
    when (state) {
        is SleepTimerState.Off -> Unit

        is SleepTimerState.UntilItemEnd -> Text(
            text = stringResource(R.string.msp_player_sleep_timer_until_item_end_active),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )

        is SleepTimerState.Countdown -> SleepTimerCountdown(state = state, modifier = modifier)
    }
}

/**
 * 倒计时那一行（时间 + 剩余比例的细线）。
 *
 * ## 时钟归这一个小叶子，不归面板、更不归播放页
 *
 * 倒计时是每秒都在变的值。如果它由上面某一层算出来再传进来，那一层的重组
 * 就变成每秒一次——面板关着的时候也在算，而屏幕上根本看不到它。
 * 这里自己开一个 tick，读的人只有这两三个节点。
 *
 * ## 为什么是 0.5 秒一跳，而不是 1 秒
 *
 * `delay(1000)` 和真实时钟的相位是随机的：屏幕上那个数字最长会比真值滞后
 * 将近一秒，表现为「定时明明到点了却还显示 `0:01`」（而内核是 200ms 一跳、
 * 早就暂停了）。半个周期把滞后压到 0.5 秒以内，代价只是这一行多重组一次。
 *
 * key 用 `state`（而不是 `Unit`）：换一档时时钟要跟着新截止时刻重新起算，
 * 不然那半秒里显示的会是上一档的剩余量。
 */
@Composable
private fun SleepTimerCountdown(state: SleepTimerState.Countdown, modifier: Modifier = Modifier) {
    var nowMs by remember(state) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(COUNTDOWN_REFRESH_MS)
        }
    }

    val remainingMs = SleepTimerRules.remainingMs(state, nowMs) ?: 0L
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.msp_player_sleep_timer_remaining,
                SleepTimerRules.formatRemaining(remainingMs),
            ),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            // 等宽数字：不然每秒一变时整行会左右抖一下（`1` 比 `0` 窄）。
            maxLines = 1,
        )
        LinearProgressIndicator(
            // 画「还剩多少」而不是「过了多久」：定时器上唯一有意义的方向是
            // 「还有这么长」，条越短越接近睡着。
            progress = { 1f - SleepTimerRules.progress(state, nowMs) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/**
 * 倒计时那行的刷新间隔。
 *
 * 见 [SleepTimerCountdown] 的注释：这个值决定「屏幕上的数字最多滞后真值多久」，
 * 500ms 是「看得出来不滞后」和「别白重组」之间的折中。
 */
private const val COUNTDOWN_REFRESH_MS = 500L
