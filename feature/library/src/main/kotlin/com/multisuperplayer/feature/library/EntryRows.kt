package com.multisuperplayer.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.ui.text.displayTitle
import com.multisuperplayer.core.ui.text.string

/**
 * 三个页面共用的条目行。
 *
 * 原本它是 `LibraryScreen.kt` 里的私有函数。搬到公共文件里，是因为「最近播放」
 * 和「播放列表」要画**同一行**——标题、类型图标、时长、选中态的配色全都一样，
 * 只有副标题的来源不同。复制一份的代价不是多几十行代码，而是以后改配色时
 * 只改到其中一份，页面上出现两种深浅不同的「已选中」。
 *
 * [supporting] 留成 `String?` 而不是 `MspText`：调用方传进来的已经是拼好的整句
 * （「播放到 12:34 · 2026-02-14 09:31」），里面既有用户数据又有本地化文案，
 * 再往上抽象一层只会让每个调用点多写一个 `MspText.join`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaEntryRow(
    entry: MediaEntry,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    supporting: String? = null,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        ListItem(
            headlineContent = {
                Text(entry.displayTitle().string(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    // 库里读不到艺术家/专辑时退回类型名（「音频」/「视频」）：
                    // 空白副标题会让整行看起来像加载失败。
                    text = supporting ?: entry.subtitle.ifBlank { entry.kind.label() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                if (selectionMode) {
                    // onCheckedChange = null：点勾选框和点整行必须是同一个动作，
                    // 否则会出现「点空白处进多选、点勾选框没反应」这种不一致。
                    Checkbox(checked = selected, onCheckedChange = null)
                } else {
                    Icon(imageVector = entry.kind.icon, contentDescription = entry.kind.label())
                }
            },
            trailingContent = trailing ?: { EntryDuration(entry) },
        )
    }
}

/**
 * 时长。时长未知时**不显示** `00:00`：那会被读成「这条是空文件」，
 * 而时长缺失其实是常态（有些容器不在 MediaStore 里存时长）。
 */
@Composable
internal fun EntryDuration(entry: MediaEntry) {
    if (entry.durationMs <= 0L) return
    Text(
        text = TimeFormat.clock(entry.durationMs),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 空状态。
 *
 * 三种页面（没有媒体、没有播放列表、没有最近播放）共用一份版式：
 * 图标 + 标题 + 说明 + 可选的行动按钮。**行动按钮必须可选**——
 * 「最近播放是空的」这件事没有任何按钮能让它变得非空，
 * 硬塞一个「去媒体库」的按钮其实是在解决另一个页面的问题。
 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (actionLabel != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) {
                Text(actionLabel)
            }
        }
    }
}

/** 一行里两个元素并排时的间距。放在这里是为了三个页面用同一个值。 */
internal val EntryRowTrailingSpacing = 4.dp

/**
 * 多选集合存成一个 list 才能进 `rememberSaveable`（`Set` 不是可保存类型）。
 *
 * 存在这里而不是某个页面里：媒体库页和浏览页的选中集合是同一个东西，
 * 两个页面各写一份 saver，迟早会有一边忘了处理空集（restore 出一个 `null`）。
 */
internal val MEDIA_SELECTION_SAVER = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

/**
 * 多选时的操作条。
 *
 * 标题位置放「已选 N 项」，返回图标当「退出多选」——
 * 用户按下返回键的直觉就是「取消当前这个状态」，没必要为它单独发明一个按钮。
 *
 * 媒体库页和浏览页共用这一条：两个页面上的手势、图标、文案完全一样，
 * 而复制一份的代价是以后加一个动作时会漏掉其中一边。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlay: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.msp_library_selection_count, count)) },
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.msp_library_exit_selection),
                )
            }
        },
        actions = {
            IconButton(onClick = if (allSelected) onClearSelection else onSelectAll) {
                if (allSelected) {
                    Icon(
                        imageVector = Icons.Outlined.Deselect,
                        contentDescription = stringResource(R.string.msp_library_clear_selection),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = stringResource(R.string.msp_library_select_all),
                    )
                }
            }
            IconButton(onClick = onAddToPlaylist) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.PlaylistAdd,
                    contentDescription = stringResource(R.string.msp_library_add_to_playlist),
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.msp_library_play_selection),
                )
            }
        },
    )
}

/** 把「时长 + 自定义按钮」拼成一个 trailing 组合。 */
@Composable
internal fun EntryTrailing(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EntryRowTrailingSpacing),
        content = { content() },
    )
}

/**
 * 提示条（权限不全 / 扫描被截断 / 播放列表里有失效条目）。
 *
 * 必须显示。否则用户看到的是一个**看起来完整**的列表，会以为文件丢了，
 * 而实际上它们只是被权限或层级限制挡在了扫描之外。
 */
@Composable
internal fun Banner(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
) {
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 类型名。写成 `@Composable` 函数而不是 `val`：文案在资源里，只能在组合期解析。
 */
@Composable
internal fun MediaKind.label(): String = stringResource(
    when (this) {
        MediaKind.AUDIO -> R.string.msp_library_kind_audio
        MediaKind.VIDEO -> R.string.msp_library_kind_video
        MediaKind.UNKNOWN -> R.string.msp_library_kind_media
    },
)

/**
 * 类型图标。
 *
 * 项目里没有图片加载库（也不打算为了封面再引一个），所以封面位置画类型图标：
 * 它至少能回答「这条是音频还是视频」，而一个永远空着的占位图什么都回答不了。
 */
internal val MediaKind.icon: ImageVector
    get() = when (this) {
        MediaKind.VIDEO -> Icons.Outlined.Movie
        MediaKind.AUDIO, MediaKind.UNKNOWN -> Icons.Outlined.MusicNote
    }
