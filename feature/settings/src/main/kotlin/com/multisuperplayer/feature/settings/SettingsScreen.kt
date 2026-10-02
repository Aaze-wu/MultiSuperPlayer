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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.player.PlaybackSpeedOptions
import com.multisuperplayer.core.player.SpeedBoostOptions
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
        onSetAspectRatioMode = viewModel::setAspectRatioMode,
        onSetSpeed = viewModel::setSpeed,
        onSetBoostSpeed = viewModel::setBoostSpeed,
        onSetRememberPosition = viewModel::setRememberPosition,
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
    onSetAspectRatioMode: (AspectRatioMode) -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onSetBoostSpeed: (Float) -> Unit = {},
    onSetRememberPosition: (Boolean) -> Unit = {},
    onOpenTranslationSettings: () -> Unit = {},
) {
    val baseTheme = MspBaseTheme.fromId(theme.baseThemeId)
    val accent = MspAccent.fromId(theme.accentId)
    // 封面取色与系统取色都是「有值才生效」的可空布尔；读的地方统一在这里兜底一次，
    // 避免下面三个开关各自写一遍 `?: true` / `?: false` 而写反其中一个。
    val artworkColorEnabled = theme.colorFromArtwork ?: false
    val dynamicColorPreferred = theme.useDynamicColor ?: true

    // 当前打开的选择对话框（null = 没开）。
    //
    // 用「对话框 + 当前值」而不是像主题基底那样把选项全铺在页面上：画面比例 4 项、
    // 倍速 10 项，全铺开会让「播放」这一段比你真正要改的那一行长三倍。
    var openDialog: SettingsDialog? by remember { mutableStateOf(null) }

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

            item {
                // 画面比例的**默认值**。不是「当前值」：播放页里临时切到「裁剪」
                // 看完一部片子，不应该让下一部也默认被裁掉两边。
                val aspect = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT
                SettingChoiceRow(
                    icon = Icons.Outlined.AspectRatio,
                    title = "默认画面比例",
                    value = aspect.label,
                    subtitle = "只影响之后打开的文件。在播放页里临时改的比例不会写到这里。",
                    onClick = { openDialog = SettingsDialog.ASPECT_RATIO },
                )
            }

            item {
                // 和画面比例相反，这个是**全局**的：播放页里点倍速也会写回同一个值，
                // 所以这里显示的就是「下次打开会用的速度」，不需要额外说明。
                val speed = playback.speed ?: PlaybackSpeedOptions.DEFAULT
                SettingChoiceRow(
                    icon = Icons.Outlined.Speed,
                    title = "默认倍速",
                    value = PlaybackSpeedOptions.format(speed),
                    subtitle = "跨文件保留：播放页里改了倍速，这里也会跟着变。",
                    onClick = { openDialog = SettingsDialog.SPEED },
                )
            }

            item {
                // 长按画面的倍速。和「默认倍速」是两件事：默认倍速是「我想一直
                // 用这个速度播」，这个是「我想临时听快一点」——所以它的档位表里
                // 没有 0.5×/0.75× 这种「比原速慢」的值，最小值就是 1.5×。
                SettingChoiceRow(
                    icon = Icons.Outlined.FastForward,
                    title = "长按倍速",
                    value = SpeedBoostOptions.format(playback.boostSpeed),
                    subtitle = "按住画面时用这个速度，松手回到原来的速度。",
                    onClick = { openDialog = SettingsDialog.BOOST_SPEED },
                )
            }

            item {
                val remember = playback.rememberPosition ?: true
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                    title = "记住播放位置",
                    subtitle = if (remember) {
                        "下次打开同一个文件时接着上次的位置播"
                    } else {
                        "每次都从头播。已经记住的位置不会被删掉，重新打开这个开关就能继续用。"
                    },
                    checked = remember,
                    enabled = true,
                    onCheckedChange = onSetRememberPosition,
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

    // 对话框画在 `Scaffold` 外面，而不是塞进 `LazyColumn` 的 item 里：
    // 放进 item 的话它会随列表滚走，而对话框是浮层，本就不该有自己的滚动位置。
    when (openDialog) {
        SettingsDialog.ASPECT_RATIO -> ChoiceDialog(
            title = "默认画面比例",
            options = AspectRatioMode.entries,
            selected = playback.aspectRatioMode ?: AspectRatioMode.DEFAULT,
            label = { it.label },
            description = { it.description },
            // 选完就关：只有一个选项要选，让用户再去按一次「确定」是多余的一步。
            onSelect = { mode ->
                onSetAspectRatioMode(mode)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        SettingsDialog.SPEED -> ChoiceDialog(
            title = "默认倍速",
            options = PlaybackSpeedOptions.PRESETS,
            // 存的值可能不在档位表里（改了档位表、或被别的入口写进来的旧值）：
            // 用最近档位高亮，不能一个都不亮——那看起来像「没设置过」。
            selected = PlaybackSpeedOptions.nearestPreset(
                playback.speed ?: PlaybackSpeedOptions.DEFAULT,
            ),
            label = { PlaybackSpeedOptions.format(it) },
            description = { if (it == PlaybackSpeedOptions.DEFAULT) "正常速度" else null },
            onSelect = { speed ->
                onSetSpeed(speed)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        SettingsDialog.BOOST_SPEED -> ChoiceDialog(
            title = "长按倍速",
            // 注意这里**不能**用 `PlaybackSpeedOptions.PRESETS`：那张表有 10 档，
            // 包含 0.5×/0.75×，而「按住反而变慢」既不是这个功能的意图，也会让
            // 用户以为按住是在出问题。
            options = SpeedBoostOptions.PRESETS,
            selected = SpeedBoostOptions.normalize(playback.boostSpeed),
            label = { SpeedBoostOptions.format(it) },
            description = { if (it == SpeedBoostOptions.DEFAULT) "默认值" else null },
            onSelect = { speed ->
                onSetBoostSpeed(speed)
                openDialog = null
            },
            onDismiss = { openDialog = null },
        )

        null -> Unit
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

// --------------------------------------------------------------------- 选择行 / 选择对话框

/** 设置页上会弹出的选择对话框。 */
private enum class SettingsDialog { ASPECT_RATIO, SPEED, BOOST_SPEED }

/**
 * 「当前值 + 点开选择」的一行。
 *
 * 右侧把当前值直接写出来，而不是只画一个箭头：用户扫一眼设置页就能知道
 * 「默认画面比例是裁剪」，不用逐个点进去确认。
 */
@Composable
private fun SettingChoiceRow(
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
    Spacer(Modifier.height(4.dp))
}

/**
 * 单选对话框。
 *
 * 每个选项都用 [selectable] + `Role.RadioButton`：整行是触控目标，读屏能念出
 * 「已选中/未选中」，而不会让点击区域裂成「文字」和「小圆点」两块。
 *
 * 列表套 `verticalScroll` + `heightIn`：倍速有 10 个档位，小屏横屏时
 * 全铺开会把对话框顶出屏幕外，而 `AlertDialog` 的内容区**不会**自己滚。
 */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String?,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // onClick = null：整行已经接收点击了，再挂一次会点一下触发两次。
                            RadioButton(selected = isSelected, onClick = null)
                            Text(
                                text = label(option),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        // 说明文字缩进到和标题同一个左边缘，看起来是标题的补充而不是
                        // 一个独立的选项行。为空时整块不画，不留一条空白。
                        description(option)?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 48.dp),
                            )
                        }
                    }
                }
            }
        },
        // 只有「取消」：选择本身即生效，再放一个「确定」等于让人确认两次。
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
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
