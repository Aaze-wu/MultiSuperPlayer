package com.multisuperplayer.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspThemeDefaults
import org.koin.androidx.compose.koinViewModel

/**
 * 外观设置：主题基底、强调色、两个取色来源。
 *
 * 从设置入口页推上来，所以带返回键；返回时 [onBack] 只弹一层栈（见 `MspApp.kt` 的路由），
 * 不会回到「媒体库」。
 */
@Composable
fun AppearanceSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()

    AppearanceSettingsScreen(
        theme = theme,
        onBack = onBack,
        onSelectBaseTheme = viewModel::selectBaseTheme,
        onSelectAccent = viewModel::selectAccent,
        onSetDynamicColor = viewModel::setDynamicColor,
        onSetColorFromArtwork = viewModel::setColorFromArtwork,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    theme: ThemeSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectBaseTheme: (MspBaseTheme) -> Unit = {},
    onSelectAccent: (MspAccent) -> Unit = {},
    onSetDynamicColor: (Boolean) -> Unit = {},
    onSetColorFromArtwork: (Boolean) -> Unit = {},
) {
    val baseTheme = MspBaseTheme.fromId(theme.baseThemeId)
    val accent = MspAccent.fromId(theme.accentId)
    // 两个「取色」开关都是「有值才生效」的可空布尔；读的地方统一在这里兜底一次，
    // 避免下面几处各自写一遍 `?:` 而写反其中一个。默认值只从
    // [MspThemeDefaults] 来，不写字面量——这里以前写的是 `?: true`，
    // 正是「点强调色没反应」的成因。
    val artworkColorEnabled = theme.colorFromArtwork ?: MspThemeDefaults.COLOR_FROM_ARTWORK
    val dynamicColorPreferred = theme.useDynamicColor ?: MspThemeDefaults.USE_DYNAMIC_COLOR
    // 系统取色的两个前置条件。算在这里而不是下面那个 item 里，是因为
    // 「关于主题」那段文案也要用——只在本系统取色真的生效时才能说「配色来自系统取色」。
    // 纯黑模式下系统取色给的是一堆深灰，正好把「省像素」这件事毁掉，
    // 所以那套基底下面即使系统支持也不走系统取色。
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicColorBlockedByOled = baseTheme == MspBaseTheme.BLACK
    val dynamicColorUsable = dynamicColorSupported && !dynamicColorBlockedByOled
    // 真正生效的那个取色来源：两者都开着时封面取色赢。
    val dynamicColorActive = dynamicColorUsable && dynamicColorPreferred && !artworkColorEnabled

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("外观") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("主题基底") }

            items(MspBaseTheme.entries, key = { it.id }) { candidate ->
                BaseThemeRow(
                    candidate = candidate,
                    selected = candidate == baseTheme,
                    onSelect = { onSelectBaseTheme(candidate) },
                )
            }

            item {
                AccentPicker(
                    selected = accent,
                    onSelect = onSelectAccent,
                )
            }

            item { SectionHeader("取色来源") }
            item {
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Album, contentDescription = null) },
                    title = "封面取色",
                    subtitle = "用正在播放的封面生成主题色",
                    checked = artworkColorEnabled,
                    enabled = true,
                    onCheckedChange = onSetColorFromArtwork,
                )
            }

            item {
                // 被封面取色盖住时也禁用，但**勾选状态仍然按用户自己的选择显示**：
                // 把勾去掉会让他以为「这个开关被系统关了」，而不是「被另一个开关盖住了」。
                val enabled = dynamicColorUsable && !artworkColorEnabled
                val checked = dynamicColorUsable && dynamicColorPreferred

                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    title = "跟随系统取色",
                    subtitle = when {
                        artworkColorEnabled -> "已被封面取色覆盖"
                        !dynamicColorSupported -> "需要 Android 12 及以上"
                        dynamicColorBlockedByOled -> "纯黑模式下不可用（系统取色会给出一堆深灰）"
                        else -> "使用系统壁纸生成的主题色（选强调色时会自动关掉）"
                    },
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onSetDynamicColor,
                )
            }

            item { SectionHeader("关于主题") }
            item {
                // 说清楚「为什么我选的颜色没生效」——这是本页最容易让人困惑的一点。
                // 但光描述现象没用：选强调色**会自动**关掉盖住它的那两个开关，
                // 所以每条文案都要给出下一步动作。
                InfoNote(
                    text = when {
                        artworkColorEnabled ->
                            "现在的配色来自封面取色。它盖住了强调色，但选一个强调色就可以" +
                                "切回来（那会自动关掉封面取色）；封面里没有可用颜色时（比如黑白封面）" +
                                "会回退到强调色，所以强调色仍然有用。"

                        dynamicColorActive ->
                            "现在的配色来自系统取色。选一个强调色就会自动关掉系统取色，" +
                                "配色立刻跟随强调色。"

                        else ->
                            "强调色会立即应用到整个应用：标题、按钮、进度条和歌词高亮都跟随它。"
                    },
                )
            }
        }
    }
}

// --------------------------------------------------------------------- 主题基底

@Composable
private fun BaseThemeRow(
    candidate: MspBaseTheme,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            // selectable + Role.RadioButton：让整行都是触控目标，并且读屏能念出
            // 「已选中/未选中」，而不是只能靠点那个小圆点。
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.DarkMode,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(candidate.displayName) },
        trailingContent = {
            // 用 RadioButton 的 onClick = null：整行已经接收点击了，
            // 再挂一次会出现「点圆点触发两次」。
            RadioButton(selected = selected, onClick = null)
        },
    )
}

// --------------------------------------------------------------------- 强调色

@Composable
private fun AccentPicker(
    selected: MspAccent,
    onSelect: (MspAccent) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = "强调色",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MspAccent.entries.forEach { candidate ->
                AccentSwatch(
                    accent = candidate,
                    selected = candidate == selected,
                    onClick = { onSelect(candidate) },
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    accent: MspAccent,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 色块用「亮色基底下的主色」：主题基底是深色时也能看清这是哪个颜色，
    // 因为真正需要辨认的是色相，不是它在当前主题下的具体取值。
    val swatch = accent.lightPrimary

    Box(
        modifier = Modifier
            .size(48.dp)
            .background(swatch, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // 颜色本身对读屏用户不可见，必须给出名字，否则这一行是六个无名圆圈。
            .semantics { contentDescription = accent.displayName },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
