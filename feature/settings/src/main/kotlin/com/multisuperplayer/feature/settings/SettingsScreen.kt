package com.multisuperplayer.feature.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import org.koin.androidx.compose.koinViewModel

/**
 * 带依赖注入入口的设置页。
 *
 * 与 [SettingsScreen] 分开，是为了让后者保持「无 ViewModel、无状态」——
 * 这样它既能在预览里直接喂假数据，也能被以后的主题实时预览复用。
 */
@Composable
fun SettingsRoute(
    modifier: Modifier = Modifier,
    onOpenTranslationSettings: () -> Unit = {},
) {
    val viewModel: SettingsViewModel = koinViewModel()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()

    SettingsScreen(
        theme = theme,
        translation = translation,
        playback = playback,
        softwareDecodingAvailable = viewModel.softwareDecodingAvailable,
        modifier = modifier,
        onSelectBaseTheme = viewModel::selectBaseTheme,
        onSelectAccent = viewModel::selectAccent,
        onSetDynamicColor = viewModel::setDynamicColor,
        onSetColorFromArtwork = viewModel::setColorFromArtwork,
        onSetForceSoftwareDecoding = viewModel::setForceSoftwareDecoding,
        onOpenTranslationSettings = onOpenTranslationSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    theme: ThemeSettings,
    modifier: Modifier = Modifier,
    translation: TranslationSettings = TranslationSettings(),
    playback: PlaybackSettings = PlaybackSettings(),
    softwareDecodingAvailable: Boolean = true,
    onSelectBaseTheme: (MspBaseTheme) -> Unit = {},
    onSelectAccent: (MspAccent) -> Unit = {},
    onSetDynamicColor: (Boolean) -> Unit = {},
    onSetColorFromArtwork: (Boolean) -> Unit = {},
    onSetForceSoftwareDecoding: (Boolean) -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
) {
    val baseTheme = MspBaseTheme.fromId(theme.baseThemeId)
    val accent = MspAccent.fromId(theme.accentId)
    // 封面取色与系统取色都是「有值才生效」的可空布尔；读的地方统一在这里兜底一次，
    // 避免下面三个开关各自写一遍 `?: true` / `?: false` 而写反其中一个。
    val artworkColorEnabled = theme.colorFromArtwork ?: false
    val dynamicColorPreferred = theme.useDynamicColor ?: true

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader("外观") }

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
                val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                // 纯黑模式下系统取色给的是一堆深灰，正好把「省像素」这件事毁掉，
                // 所以那套基底下面即使系统支持也不走系统取色——开关这时是禁用的，
                // 并且必须说明原因，否则用户只会觉得开关坏了。
                val blockedByOled = baseTheme == MspBaseTheme.BLACK
                val usable = dynamicSupported && !blockedByOled
                // 被封面取色盖住时也禁用，但**勾选状态仍然按用户自己的选择显示**：
                // 把勾去掉会让他以为「这个开关被系统关了」，而不是「被另一个开关盖住了」。
                val enabled = usable && !artworkColorEnabled
                val checked = usable && dynamicColorPreferred

                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    title = "跟随系统取色",
                    subtitle = when {
                        artworkColorEnabled -> "已被封面取色覆盖"
                        !dynamicSupported -> "需要 Android 12 及以上"
                        blockedByOled -> "纯黑模式下不可用（系统取色会给出一堆深灰）"
                        else -> "使用系统壁纸生成的主题色"
                    },
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onSetDynamicColor,
                )
            }

            item { SectionHeader("关于主题") }
            item {
                // 说清楚「为什么我选的颜色没生效」——这是本页最容易让人困惑的一点：
                // 上面两个开关打开时，选强调色是不会立刻看到变化的。
                InfoNote(
                    text = when {
                        artworkColorEnabled ->
                            "优先级：封面取色 > 系统取色 > 强调色。封面里没有可用的颜色时" +
                                "（比如黑白封面）会回退到下面选的强调色，所以强调色仍然有用。"

                        dynamicColorPreferred && baseTheme != MspBaseTheme.BLACK ->
                            "系统取色开启时，下面的强调色在选择后不会立刻生效；" +
                                "关掉系统取色即可使用。"

                        else ->
                            "强调色会立即应用到整个应用：标题、按钮、进度条和歌词高亮都跟随它。"
                    },
                )
            }

            item { SectionHeader("播放") }
            item {
                // 这个开关**不当**「让更多文件能放」用：内核默认就是
                // 「系统解码器优先，解不了/解失败自动换 FFmpeg」。它真正解决的是
                // 另一类问题——硬件解码器不报错，但画面花屏、变色、音画不同步。
                // 不说清楚的话，所有「放不了」的用户都会先来拨它，然后觉得没用。
                val force = playback.forceSoftwareDecoding ?: false
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                    title = "强制软件解码",
                    subtitle = when {
                        !softwareDecodingAvailable ->
                            "本安装包不含 FFmpeg（CPU 架构不受支持），打开也不会生效"

                        force ->
                            "已用 FFmpeg 解码。画面异常时用它排查；代价是耗电和发热明显变高。"

                        else ->
                            "默认不勾：系统解码器放不了或放错时，内核会自动改用内置的 FFmpeg。" +
                                "只有当画面花屏/变色/音画不同步（硬件解码器出错）时才需要勾上。"
                    },
                    checked = force && softwareDecodingAvailable,
                    enabled = softwareDecodingAvailable,
                    onCheckedChange = onSetForceSoftwareDecoding,
                )
            }

            item { SectionHeader("字幕翻译") }
            item {
                TranslationSummaryRow(
                    translation = translation,
                    onOpen = onOpenTranslationSettings,
                )
            }
        }
    }
}

/**
 * 设置页里的翻译入口。
 *
 * 这一行不直接把所有设置摊开，而是先显示「现在是什么状态」：
 * 服务商/模型/目标语言，以及一句「还差什么」。用户从播放页翻不出来时
 * 通常只想确认这两件事，不该让他先点进三个子页。
 */
@Composable
private fun TranslationSummaryRow(translation: TranslationSettings, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = translation.provider.displayName,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = buildString {
                append(translation.model.ifBlank { "未填模型名" })
                append("\n译成：")
                append(translation.target.label)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                translation.ready -> "已就绪，点这里可以改服务商、密钥或术语表"
                else -> "还不能翻译（缺：${translation.missingItems.joinToString("、")}），点这里去填"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (translation.ready) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

// --------------------------------------------------------------------- 分区

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun InfoNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

// --------------------------------------------------------------------- 开关行

@Composable
internal fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    ListItem(
        // 整行可点，不只是那个开关：小屏上点 32dp 的开关很容易失手。
        modifier = Modifier.fillMaxWidth().selectable(
            selected = checked,
            enabled = enabled,
            role = Role.Switch,
            onClick = { onCheckedChange(!checked) },
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon,
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            // onClick = null：整行的 selectable 已经负责切换。
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
    Spacer(Modifier.height(4.dp))
}
