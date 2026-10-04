package com.multisuperplayer.feature.settings

import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ColorLens
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.AppLanguage
import com.multisuperplayer.core.data.settings.CustomAccent
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.ui.text.string
import com.multisuperplayer.core.ui.theme.CustomAccentRanges
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import com.multisuperplayer.core.ui.theme.MspThemeDefaults
import com.multisuperplayer.core.ui.theme.customAccentColors
import org.koin.androidx.compose.koinViewModel
import kotlin.math.roundToInt

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
        onSelectCustomAccent = viewModel::selectCustomAccent,
        onClearCustomAccent = viewModel::clearCustomAccent,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    theme: ThemeSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.DEFAULT,
    onSelectBaseTheme: (MspBaseTheme) -> Unit = {},
    onSelectAccent: (MspAccent) -> Unit = {},
    onSelectCustomAccent: (CustomAccent) -> Unit = {},
    onClearCustomAccent: () -> Unit = {},
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
    // 强调色选择弹窗。这一页只此一个对话框，所以用一个布尔量而不是枚举；
    // 它渲染在 `Scaffold` 外面，不会随列表滚动被回收（见文件末尾）。
    var showAccentPicker by remember { mutableStateOf(false) }

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
                val custom = theme.customAccent
                // 被盖子盖住时必须说出来。强调色的上面还压着两个取色开关，
                // 此刻点开列表换一个颜色，界面上可能**一点变化都没有**——
                // 不说清这件事，用户看到的就是「这里坏了」。
                val coveredBy = when {
                    artworkColorEnabled -> R.string.msp_settings_accent_covered_artwork
                    dynamicColorUsable && dynamicColorPreferred ->
                        R.string.msp_settings_accent_covered_dynamic
                    else -> null
                }
                SettingChoiceRow(
                    // 整页只有它一个「选颜色」的设置项，而颜色本身就是它的值，
                    // 所以右侧的值旁边要画一块色（`SettingChoiceRow` 的 valueSwatch）。
                    icon = Icons.Outlined.ColorLens,
                    title = stringResource(R.string.msp_settings_accent_label),
                    value = if (custom != null) {
                        stringResource(R.string.msp_settings_custom_accent_option)
                    } else {
                        accent.label.string()
                    },
                    valueSwatch = accentSwatchColor(customAccent = custom, preset = accent),
                    subtitle = if (coveredBy != null) {
                        stringResource(coveredBy)
                    } else {
                        stringResource(R.string.msp_settings_accent_desc)
                    },
                    onClick = { showAccentPicker = true },
                    help = stringResource(R.string.msp_settings_theme_note_accent),
                )
            }

            // 滑块只在「自定义」被选中时出现：六个预设全部在弹窗里，它们不需要滑块；
            // 而常驻的代价是这一页永远多出三根意义不明的滑块（它们与上面那一排
            // 颜色选项没有任何视觉联系，用户不知道自己在拖什么）。
            val customAccent = theme.customAccent
            if (customAccent != null) {
                item {
                    CustomAccentSection(
                        current = customAccent,
                        onSelect = onSelectCustomAccent,
                        onClear = onClearCustomAccent,
                        help = stringResource(R.string.msp_settings_custom_accent_note),
                    )
                }
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

    // 对话框放在 `Scaffold` **外面**：`AlertDialog` 是独立窗口，放进 `LazyColumn` 的 item 里
    // 反而会跟着列表一起被回收（见 `PlaybackSettingsScreen` 里同样的做法）。
    if (showAccentPicker) {
        val custom = theme.customAccent
        // 选项类型写成 `MspAccent?`：`null` 就是「自定义」那一项。不给它编一个假的
        // 枚举值，是因为它真的不是任何一项预设——那样反而要让 `fromId` 接受一个
        // 不存在的 id。
        ChoiceDialog(
            title = stringResource(R.string.msp_settings_accent_label),
            options = MspAccent.entries + listOf<MspAccent?>(null),
            // 自定义生效时一个预设都不勾：让某一项亮着会让人以为「我用的是它，
            // 只是颜色被改了」。
            selected = if (custom != null) null else accent,
            label = { option ->
                option?.label?.string()
                    ?: stringResource(R.string.msp_settings_custom_accent_option)
            },
            description = { option ->
                if (option == null) {
                    stringResource(R.string.msp_settings_custom_accent_option_desc)
                } else {
                    null
                }
            },
            leading = { option ->
                // 还没存过自定义色时，「自定义」那一项画的是**选中它会得到的那个颜色**
                // （与 onSelect 里落盘的是同一个值），而不是一个空圈——
                // 空圈看起来像这个选项不可用。
                AccentOptionDot(
                    color = if (option != null) {
                        option.lightPrimary
                    } else {
                        accentSwatchColor(customAccent = custom ?: DEFAULT_CUSTOM_ACCENT, preset = accent)
                    },
                )
            },
            onSelect = { option ->
                if (option == null) {
                    // 选中「自定义」要**立刻**落盘一个颜色。只展开滑块是不够的：
                    // 此刻界面上真正生效的还是原来那个预设色，用户会以为这一下什么都没发生，
                    // 而下面的滑块又只属于「已选中自定义」这一态。
                    // 已经存过自定义色就原样再存一次（值不变，幂等）。
                    onSelectCustomAccent(custom ?: DEFAULT_CUSTOM_ACCENT)
                } else {
                    onSelectAccent(option)
                }
                showAccentPicker = false
            },
            onDismiss = { showAccentPicker = false },
        )
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

/**
 * 「选中自定义」时先落盘的那个颜色。
 *
 * 弹窗里那项的画色与真的选中它时写入的值必须**是同一个**：两处各写一份的话，
 * 用户看到的小色点会和他真正得到的东西不是一种颜色。
 */
private val DEFAULT_CUSTOM_ACCENT = CustomAccent(
    hueDegrees = CustomAccentRanges.DEFAULT_HUE,
    saturation = CustomAccentRanges.DEFAULT_SATURATION,
    lightness = CustomAccentRanges.DEFAULT_LIGHTNESS,
)

/**
 * 强调色那一行右侧的色块（以及弹窗里每项左边的小色点）取哪个颜色。
 *
 * 自定义色优先——它才是真正生效的那一个（`MspTheme` 的优先级是「自定义 > 预设」）。
 * 两个取色开关的「覆盖」不进这里：色块要回答的是「我选的是哪个颜色」，
 * 而「它此刻被盖住了」由副标题说。
 *
 * 两种来源统一取「亮色基底下的主色」（[MspAccent.lightPrimary] 与
 * [customAccentColors] 的 `lightPrimary`）：色块在深色主题下也要能认出色相。
 */
private fun accentSwatchColor(customAccent: CustomAccent?, preset: MspAccent): Color =
    customAccent?.let { custom ->
        customAccentColors(
            hueDegrees = custom.hueDegrees,
            saturation = custom.saturation,
            lightness = custom.lightness,
        ).lightPrimary
    } ?: preset.lightPrimary

/**
 * 选项左边的小色点。
 *
 * 列表里只有「颜色」这一项需要它：六个预设的名字（靛蓝 / 紫罗兰 / ……）
 * 单看文字根本无法选中想要的那个。纯装饰，`contentDescription` 留空——
 * 选项的名字就在它右边，读屏用户不需要再听一遍颜色名。
 */
@Composable
private fun AccentOptionDot(color: Color) {
    Box(
        modifier = Modifier
            .padding(start = 12.dp)
            .size(20.dp)
            .background(color, CircleShape),
    )
}

// ------------------------------------------------------------- 自定义强调色

/**
 * 自定义强调色：三根滑块 + 一块实时预览。
 *
 * 只在**自定义色已经生效时**才被渲染（所以 [current] 不是可空的）：它是「已经在用自定义」
 * 这一态的控制面板，不是入口。做成常驻的代价是每个用户都得多看三根滑块，而它们默认
 * 拖了还会把预设色改成自定义色——那正是「我什么都没动，主题却变了」的来源。
 *
 * 三件和「普通设置项」不一样的事都写在帮助问号里（`msp_settings_custom_accent_note`），
 * 因为它们全部反直觉：
 * - 拖动时**只改本地状态**，松手（`onValueChangeFinished`）才写 DataStore。每一帧都写盘
 *   会给 DataStore 制造几十次写入，而且用户拖到一半退出去会留下一个他从没看过的颜色。
 * - 明度只喂给**亮色**主色；深色主色由 [customAccentColors] 取相反的明度算出来。
 * - 明度范围是 10%~80%，不是 0~100%（纯黑/纯白强调色等于没有强调色）。
 *
 * 滑块的初始位置取的是**已保存的自定义色**，而不是当前生效预设的颜色：预设色转 HSL 是一个
 * 有损的反函数（要在色域边界上求最近点），做出来只会是「看起来差不多、拖一下就跳」。
 */
@Composable
private fun CustomAccentSection(
    current: CustomAccent,
    onSelect: (CustomAccent) -> Unit,
    onClear: () -> Unit,
    help: String,
) {
    // 初值只在**挂载时**取一次（`current` 不做 key）：保存成功的那一刻就是用户松手
    // 的那一刻，跟着 `current` 重置会把正在拖的另一根滑块拽回去。
    var hue by remember { mutableFloatStateOf(current.hueDegrees) }
    var saturation by remember { mutableFloatStateOf(current.saturation) }
    var lightness by remember { mutableFloatStateOf(current.lightness) }
    // 落盘前统一收敛一次：滑块的 range 已经在边界上，但「存进去的一定合法」这件事
    // 不能只靠 UI 的 range 保证——DataStore 里躺着一个越界值的话，读回来的配色会直接崩。
    val commit = {
        onSelect(
            CustomAccent(
                hueDegrees = CustomAccentRanges.normalizeHue(hue),
                saturation = CustomAccentRanges.clampSaturation(saturation),
                lightness = CustomAccentRanges.clampLightness(lightness),
            ),
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.msp_settings_section_custom_accent),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SettingHelpIcon(
                title = stringResource(R.string.msp_settings_section_custom_accent),
                text = help,
            )
        }

        // 预览的两个底板颜色**写死**，不跟当前主题走：它要回答的正是
        // 「这个颜色换到另一种基底上会是什么样」，跟着当前主题就只能看到自己这一种。
        val preview = customAccentColors(hue, saturation, lightness)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccentPreviewChip(
                background = Color.White,
                onBackground = Color(0xFF1B1B1B),
                primary = preview.lightPrimary,
                container = preview.lightContainer,
                label = MspBaseTheme.LIGHT.label.string(),
            )
            AccentPreviewChip(
                background = Color(0xFF121212),
                onBackground = Color(0xFFE6E6E6),
                primary = preview.darkPrimary,
                container = preview.darkContainer,
                label = MspBaseTheme.DARK.label.string(),
            )
        }

        AccentSlider(
            label = stringResource(R.string.msp_settings_custom_accent_hue),
            value = hue,
            valueText = "${hue.roundToInt()}°",
            range = CustomAccentRanges.HUE_MIN..CustomAccentRanges.HUE_MAX,
            onValueChange = { hue = it },
            onCommit = commit,
        )
        AccentSlider(
            label = stringResource(R.string.msp_settings_custom_accent_saturation),
            value = saturation,
            valueText = saturation.toPercentText(),
            range = CustomAccentRanges.SATURATION_MIN..CustomAccentRanges.SATURATION_MAX,
            onValueChange = { saturation = it },
            onCommit = commit,
        )
        AccentSlider(
            label = stringResource(R.string.msp_settings_custom_accent_lightness),
            value = lightness,
            valueText = lightness.toPercentText(),
            range = CustomAccentRanges.LIGHTNESS_MIN..CustomAccentRanges.LIGHTNESS_MAX,
            onValueChange = { lightness = it },
            onCommit = commit,
        )

        TextButton(onClick = onClear, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.msp_settings_custom_accent_revert))
        }
    }
}

/**
 * 一块预览底板：一个主色圆点 + 一条容器色横条，画在指定的背景色上。
 *
 * 主色和容器色要一起露出来是因为它们在主题里承担不同的活：主色负责吸引注意（按钮、进度、
 * 歌词高亮），容器色负责承载内容（卡片、选中的条目）。只看主色无法判断这个颜色放到界面上
 * 会不会「一大块糊在一起」。
 */
@Composable
private fun AccentPreviewChip(
    background: Color,
    onBackground: Color,
    primary: Color,
    container: Color,
    label: String,
) {
    Column(
        modifier = Modifier
            .width(132.dp)
            .background(background, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(22.dp).background(primary, CircleShape))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = onBackground,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .background(container, RoundedCornerShape(5.dp)),
        )
    }
}

@Composable
private fun AccentSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                // 等宽数字：不然拖动时数字位数一变（9% → 10%），整行会跟着左右抖。
                // `fontFeatureSettings` 是 TextStyle 上的属性，没有直接开在 Text 上的重载，
                // 所以要在 typography 上 copy 一份。
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            // 只有这里才落盘，见 [CustomAccentSection] 的注释。
            onValueChangeFinished = onCommit,
        )
    }
}

/**
 * 滑块上的百分比。
 *
 * 用 `roundToInt()` 而不是 `toInt()`：截断会把 0.799 显示成「79%」，而它的四舍五入结果是
 * 80%——一个显示着「79%」却已经拖到头的滑块只会让人以为控件坏了。
 */
private fun Float.toPercentText(): String = "${(this * 100f).roundToInt()}%"
