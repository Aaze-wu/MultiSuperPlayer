package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.data.update.UpdateRelease

/**
 * 启动检查查到新版本时挂在屏幕上的那个框。
 *
 * ## 为什么它在应用根部，而不是在「检查更新」页里
 *
 * 因为它是**应用启动**这件事的一部分，和用户在哪个页面无关。放在更新页里就等于
 * 「启动时查到新版，但用户不去那一页就永远看不到」，而那正是这个功能要解决的问题。
 * 挂在根部还有第二个好处：弹窗的生命周期和导航栈脱钩，用户从更新页返回、
 * 切到别的标签页，这个框还在原地——不会因为一次误触返回键就消失。
 *
 * ## 「立即更新」为什么只是跳过去，不在这里下载
 *
 * 下载要跑几十秒到几分钟，期间要显示进度、可能失败要重试、可能要去系统设置里
 * 打开「未知来源」。那些东西都在更新页上（那里已经有一整套），在一个对话框里
 * 重做一遍必然是第二份、且更容易写歪的一份。更重要的是：装完那一刻需要
 * **拉起系统安装器**，而那个动作由一个 `Channel` 事件驱动、只能在页面活着的时候
 * 被消费——在弹窗里发起下载，用户随手关掉弹窗就会让事件落在一个已经消失的
 * 收集器上，包下好了却永远不会弹安装界面。
 *
 * 于是这个框只负责三件事：告诉用户有新版、给出说明的**开头**、把用户送到
 * 更新页去（那里有完整说明）。
 *
 * ## 三个按钮分别是什么
 *
 * - **立即更新**：跳到更新页并开始下载。这是唯一会改变状态的动作。
 * - **以后再说**：只关掉这一次。12 小时后、下一次启动还会提示——用户要求的是
 *   「有更新就提示」，而这条路径上没有任何一个「不要再问了」的暗示。
 * - **忽略此版本**：写进设置，**这一版**以后都不再提示（`UpdateRules.decide`
 *   会返回 `Ignored`）。这是那个「不要再问了」的出口，所以它必须存在，
 *   否则每次启动都被同一个框拦住、又关不掉的人只会去卸载应用。
 *
 * 返回键与点击外部等同于「以后再说」（[onLater]）：一个手势的解读者应当是
 * **什么都不发生**那一侧，而不是「忽略这一版」这种会写进设置的不可见副作用。
 */
@Composable
fun StartupUpdateDialog(
    release: UpdateRelease,
    onUpdateNow: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val notes = release.notes?.trim().orEmpty()

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.msp_update_section_available)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 说明只露一个开头就够了（下面是完整说明的**入口**，不是它的替代）。
                    // 但仍然要能滚：小屏 + 大字号下这几行也可能占满整屏，
                    // 而 `AlertDialog` 的内容区不会自己滚，会把按钮顶出去。
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.msp_update_available_version, release.tagName),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (notes.isEmpty()) {
                        stringResource(R.string.msp_update_notes_empty)
                    } else {
                        notes
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = STARTUP_NOTE_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.msp_update_startup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdateNow) {
                Text(stringResource(R.string.msp_update_startup_now))
            }
        },
        dismissButton = {
            // 顺序是「次要动作在前、收尾动作在后」，和设置页上其它对话框一致。
            // 「忽略此版本」放在这一组的**最后**：它是这一排里唯一会写进设置的按钮，
            // 不该紧挨着「立即更新」。
            TextButton(onClick = onLater) {
                Text(stringResource(R.string.msp_update_startup_later))
            }
            TextButton(onClick = onIgnore) {
                Text(stringResource(R.string.msp_update_ignore))
            }
        },
    )
}

/**
 * 弹窗里露出的说明行数。
 *
 * 比更新页上的 `COLLAPSED_NOTE_LINES` 少：那边用户是主动来看说明的，
 * 这边他刚打开应用、正要去看片，说明只是「这一版改了什么」的一个提示。
 */
private const val STARTUP_NOTE_LINES = 6
