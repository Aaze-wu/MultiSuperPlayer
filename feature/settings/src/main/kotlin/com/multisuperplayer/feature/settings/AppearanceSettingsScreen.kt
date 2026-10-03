package com.multisuperplayer.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.material.icons.outlined.Translate
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.AppLanguage
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.ui.text.string
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspThemeDefaults
import org.koin.androidx.compose.koinViewModel

/**
 * 外观设置：主题基底、强调色、两个取色来源、界面语言。
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
    val language by viewModel.language.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AppearanceSettingsScreen(
        theme = theme,
        language = language,
        onBack = onBack,
        onSelectBaseTheme = viewModel::selectBaseTheme,
        onSelectAccent = viewModel::selectAccent,
        onSetDynamicColor = viewModel::setDynamicColor,
        onSetColorFromArtwork = viewModel::setColorFromArtwork,
        onSelectLanguage = { selected ->
            viewModel.setLanguage(selected)
            // 低于 Android 13 必须先写后重建：写进去的是 SharedPreferences，
            // 而 `Resources` 只会在下一次 `attachBaseContext` 时重新读它。
            // 33 以上不能重建——系统会自己重建，手动再来一次会闪两下。
            if (viewModel.localeRequiresManualRecreate) {
                context.findActivity()?.recreate()
            }
        },
        modifier = modifier,
    )
}

/**
 * 从 Context 上找到真正的 Activity。
 *
 * Compose 给的 `LocalContext` 可能是包了好几层的 `ContextWrapper`（主题包装、
 * `ContextThemeWrapper`、[androidx.activity.ComponentActivity] 自己的包装），
 * 一层 `as? Activity` 会静默地拿到 null——症状就是「切了语言没反应」，但不报错。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    theme: ThemeSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.DEFAULT,
    onSelectBaseTheme: (MspBaseTheme) -> Unit = {},
    onSelectAccent: (MspAccent) -> Unit = {},
    onSetDynamicColor: (Boolean) -> Unit = {},
    onSetColorFromArtwork: (Boolean) -> Unit = {},
    onSelectLanguage: (AppLanguage) -> Unit = {},
) {
    val baseTheme = MspBaseTheme.fromId(theme.baseThemeId)
    val accent = MspAccent.fromId(theme.accentId)
    // 两个「取色」开关都是「有值才生效」的可空布尔；读的地方统一在这里兜底一次，
    // 避免下面几处各自写一遍 `?:` 而写反其中一个。默认值只从
    // [MspThemeDefaults] 来，不写字面量——这里以前写的是 `?: true`，
    // 正是「点强调色没反应」的成因。
    val artworkColorEnabled = theme.colorFromArtwork ?: MspThemeDefaults.COLOR_FROM_ARTWORK
    val dynamicColorPreferred = theme.useDynamicColor ?: MspThemeDefaults.USE_DYNAMIC_COLOR
    // 系统取色的两个前置条件。纯黑模式下系统取色给的是一堆深灰，正好把「省像素」
    // 这件事毁掉，所以那套基底下面即使系统支持也不走系统取色。
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dynamicColorBlockedByOled = baseTheme == MspBaseTheme.BLACK
    val dynamicColorUsable = dynamicColorSupported && !dynamicColorBlockedByOled

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.msp_settings_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader(stringResource(R.string.msp_settings_section_base_theme)) }

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
                    help = stringResource(R.string.msp_settings_theme_note_accent),
                )
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_color_source)) }
            item {
                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Album, contentDescription = null) },
                    title = stringResource(R.string.msp_settings_artwork_color),
                    subtitle = stringResource(R.string.msp_settings_artwork_color_desc),
                    checked = artworkColorEnabled,
                    enabled = true,
                    onCheckedChange = onSetColorFromArtwork,
                    help = stringResource(R.string.msp_settings_theme_note_artwork),
                )
            }

            item {
                // 被封面取色盖住时也禁用，但**勾选状态仍然按用户自己的选择显示**：
                // 把勾去掉会让他以为「这个开关被系统关了」，而不是「被另一个开关盖住了」。
                val enabled = dynamicColorUsable && !artworkColorEnabled
                val checked = dynamicColorUsable && dynamicColorPreferred

                SettingsSwitchRow(
                    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    title = stringResource(R.string.msp_settings_dynamic_color),
                    subtitle = when {
                        artworkColorEnabled -> stringResource(R.string.msp_settings_dynamic_color_covered)
                        !dynamicColorSupported -> stringResource(R.string.msp_settings_dynamic_color_needs_api31)
                        dynamicColorBlockedByOled -> stringResource(R.string.msp_settings_dynamic_color_blocked)
                        else -> stringResource(R.string.msp_settings_dynamic_color_desc)
                    },
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onSetDynamicColor,
                    help = stringResource(R.string.msp_settings_theme_note_dynamic),
                )
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_language)) }
            item {
                // 用 RadioButton 而不是「点开一个对话框选」：只有四种语言，
                // 摊开比藏起来少一次点击，而且当前选的是哪一项一眼就能看到。
                Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                    AppLanguage.entries.forEach { candidate ->
                        LanguageRow(
                            language = candidate,
                            selected = candidate == language,
                            onSelect = { onSelectLanguage(candidate) },
                        )
                    }
                }
            }
            item {
                InfoNote(
                    text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stringResource(R.string.msp_settings_language_note_system)
                    } else {
                        stringResource(R.string.msp_settings_language_note_app_only)
                    },
                )
            }
        }
    }
}

// --------------------------------------------------------------------- 语言

@Composable
private fun LanguageRow(
    language: AppLanguage,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Outlined.Translate, contentDescription = null) },
        headlineContent = {
            // 「跟随系统」这一项没有自称（它是个相对概念），所以它是唯一跟着
            // 界面语言翻译的一项；其余三项一律用自己的语言写自己的名字，
            // 否则一个只会中文的用户在英文界面里就找不到自己的语言了。
            Text(language.endonym ?: stringResource(R.string.msp_settings_language_follow_system))
        },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
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
        headlineContent = { Text(candidate.label.string()) },
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
    help: String,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        // 强调色是这一页唯一「选一个颜色」的控件，也是唯一没有副标题的控件，
        // 所以它的说明只能挂在标签旁边的问号上。
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.msp_settings_accent_label),
                style = MaterialTheme.typography.bodyLarge,
            )
            SettingHelpIcon(
                title = stringResource(R.string.msp_settings_accent_label),
                text = help,
            )
        }
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
    // 先算好再进 semantics 的 lambda：那里面不是可组合上下文，拿不到 stringResource。
    val accentLabel = accent.label.string()

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
            .semantics { contentDescription = accentLabel },
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
