package com.multisuperplayer.feature.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.settings.DisplaySettings
import com.multisuperplayer.core.ui.text.TitleScrollPage
import com.multisuperplayer.core.ui.text.TitleScrollRules
import org.koin.androidx.compose.koinViewModel

/**
 * 外观 › 长文件名滚动。
 *
 * 单独一页而不是留在外观页里，是因为它现在有**两层**设置：一个总开关，加上
 * 七个页面各自的「这一页所有长名字都滚」。八个开关摊在外观页里会把「主题/强调色」
 * 那一组压得看不见，而这一组又只在「放进来的名字太长」时才有人关心。
 *
 * 从外观页推上来，所以带返回键；[onBack] 只弹一层栈（见 `MspApp.kt` 的路由）。
 */
@Composable
fun TitleScrollSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val display by viewModel.display.collectAsStateWithLifecycle()

    TitleScrollSettingsScreen(
        display = display,
        onBack = onBack,
        onSetEnabled = viewModel::setScrollLongTitles,
        onSetPage = viewModel::setAlwaysScrollPage,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleScrollSettingsScreen(
    display: DisplaySettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSetEnabled: (Boolean) -> Unit = {},
    onSetPage: (TitleScrollPage, Boolean) -> Unit = { _, _ -> },
) {
    // `null` = 用户没设置过 ⇒ UI 层默认（开）。默认值只从 [TitleScrollRules] 取，
    // 不写字面量——与外观页那两个取色开关同理。
    val enabled = display.scrollLongTitles ?: TitleScrollRules.DEFAULT_ENABLED
    // 只认**认得出来的** id：降级安装后仓里会留着新版写下的页面 id，
    // 这里若直接拿原始集合去判，`checked` 会永远 false（用户看见「设置丢了」），
    // 而 `onSetPage` 却又能把它写回去。
    val pages = TitleScrollPage.known(display.alwaysScrollPages)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_scroll_long_titles)) },
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
            item {
                SettingsSwitchRow(
                    // 左右箭头：这个开关的作用就是「文字会沿水平方向移动」。
                    icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                    title = stringResource(R.string.msp_settings_scroll_long_titles),
                    subtitle = stringResource(R.string.msp_settings_scroll_long_titles_desc),
                    checked = enabled,
                    enabled = true,
                    onCheckedChange = onSetEnabled,
                    help = stringResource(R.string.msp_settings_note_scroll_long_titles),
                )
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_always_scroll)) }

            // 逐页的七行**不隐藏**、也不清空：总开关关掉时它们只是置灰，勾痕留着。
            // 清掉的话，用户为了「暂时不想看动画」关一次总开关，回来发现自己设过的
            // 五个页面全没了——而那五个页面正是他挨个点出来的。
            items(TitleScrollPage.entries, key = { it.id }) { page ->
                val checked = page in pages
                SettingsSwitchRow(
                    icon = { Icon(TitleScrollPageLabels.icon(page), contentDescription = null) },
                    title = stringResource(TitleScrollPageLabels.title(page)),
                    subtitle = stringResource(
                        // 三句话覆盖「为什么现在不生效」：置灰却不说的开关，
                        // 用户只会以为它坏了（这也是外观页那条「系统取色被盖住」的教训）。
                        when {
                            !enabled -> R.string.msp_settings_scroll_state_master_off
                            checked -> R.string.msp_settings_scroll_state_always
                            else -> R.string.msp_settings_scroll_state_current
                        },
                    ),
                    checked = checked,
                    // 逐页勾选从属于总开关。判定里也会再挡一次
                    // （`TitleScrollRules.shouldScroll`），这里只是让界面别骗人。
                    enabled = enabled,
                    onCheckedChange = { onSetPage(page, it) },
                )
            }

            item { InfoNote(stringResource(R.string.msp_settings_note_always_scroll)) }
        }
    }
}

/**
 * 七个页面在中文化/英文界面上的名字，以及行首的图标。
 *
 * 抽成 [object] 而不是在 `items {}` 里写 `when`：一张「枚举 → 文案」的对照表值得
 * 被单测钉住——漏掉一项、或者两页复制粘贴成同一句，在界面上都只是一个「点了没反应」
 * 的勾，而 `when` 是表达式时漏项会被编译器拦住，重复项只能靠测试。
 */
internal object TitleScrollPageLabels {

    /**
     * 页面名。
     *
     * 名字必须和用户看到的**那一页**对得上：`browse` 在这里叫「浏览」，
     * 与底部导航那一项同名，用户才知道勾的就是那一个。
     */
    @StringRes
    fun title(page: TitleScrollPage): Int = when (page) {
        TitleScrollPage.LIBRARY -> R.string.msp_settings_scroll_page_library
        TitleScrollPage.BROWSE -> R.string.msp_settings_scroll_page_browse
        TitleScrollPage.RECENT -> R.string.msp_settings_scroll_page_recent
        TitleScrollPage.FAVORITES -> R.string.msp_settings_scroll_page_favorites
        TitleScrollPage.PLAYLISTS -> R.string.msp_settings_scroll_page_playlists
        TitleScrollPage.QUEUE -> R.string.msp_settings_scroll_page_queue
        TitleScrollPage.NETWORK -> R.string.msp_settings_scroll_page_network
    }

    /**
     * 行首图标，尽量与那一页在导航/标题栏上的图标一致。
     *
     * 播放队列用 [Icons.Filled.DragHandle]（两条横线）：它正是队列里拖动手柄的图标，
     * 而「上滑面板里的顺序」这件事比任何抽象图标都直观。列表类图标一律避开
     * `Icons.Outlined.QueueMusic` 之外的非镜像版本——阿拉伯语下它们要画反
     * （见 `MspApp.kt` 底部导航那处的说明）。
     */
    fun icon(page: TitleScrollPage): ImageVector = when (page) {
        TitleScrollPage.LIBRARY -> Icons.Outlined.LibraryMusic
        TitleScrollPage.BROWSE -> Icons.Outlined.FolderOpen
        TitleScrollPage.RECENT -> Icons.Outlined.History
        TitleScrollPage.FAVORITES -> Icons.Outlined.Star
        TitleScrollPage.PLAYLISTS -> Icons.AutoMirrored.Outlined.QueueMusic
        TitleScrollPage.QUEUE -> Icons.Filled.DragHandle
        TitleScrollPage.NETWORK -> Icons.Outlined.Public
    }
}
