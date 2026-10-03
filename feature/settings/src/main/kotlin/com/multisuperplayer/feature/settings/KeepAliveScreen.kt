package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.power.DeviceVendor
import com.multisuperplayer.core.data.power.KeepAliveState
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 「后台保活」页。
 *
 * ## 它解决的是哪半个问题（这一页的存在理由）
 *
 * 播放本身**已经**是稳的：`MspPlaybackService` 是 `MediaSessionService`，播放中有
 * 前台服务（`foregroundServiceType="mediaPlayback"`），并且 ExoPlayer 配了
 * `setWakeMode(WAKE_MODE_NETWORK)`。真机上实测过两种场景：Doze 深度休眠
 * （`force-idle` 后 70 秒仍在推进，还自动连播到了下一首）和从最近任务里划掉
 * （前台服务存活、仍在播）。所以这一页**不是**「不加就播不了」的开关。
 *
 * 它管的是这套机制盖不住的两件事：
 * 1. **网络流**：Doze 期间系统会掐断应用的网络访问，本地文件不受影响；
 * 2. **厂商后台管理**：锁屏后清理、禁止自启动这些策略来自厂商自己的管家，
 *    它们**不看** AOSP 的电池优化白名单，也是真机上真正会杀掉播放的那一类。
 *
 * 界面上的说明文案（`msp_keep_alive_switch_note`）必须保住这个分寸：说成
 * 「不开就播不了」是谎话，用户拨一次开关发现没有区别之后，就不会再信这一页了。
 *
 * ## 和「权限」页的分工
 *
 * 这里做的两件事都要跳到**系统页面**去，所以它天然像权限页。区别在于：
 * 权限页列的是「应用声明过什么」，
 * 而这里的两项不是权限——电池优化白名单是「用户对系统的授权」，
 * 厂商后台管理连个 API 都没有（只能靠包名猜页面）。所以它们是两页，
 * 而不是权限页上的两行。
 */
@Composable
fun KeepAliveRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: KeepAliveViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 白名单是**这一页之外**发生的改变（用户去系统页面里加的），没有任何回调会通知我们。
    // 和「所有文件访问」是同一类状态，只有回到前台时重读一次才知道对不对。
    // 少了这个观察者，用户申请完回来会看到开关还关着——他会再申请一次，
    // 而第二次申请的系统框已经是「已经在白名单里」那个状态了。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    KeepAliveScreen(
        state = state,
        vendor = viewModel.vendor,
        onBack = onBack,
        // Activity 现场取：`findActivity()` 在 Composition 期间可能还是 null
        // （这一页刚被创建时），而三个动作都只在点击那一刻需要它。
        onCheckedChange = { wanted ->
            val activity = context.findActivity()
            if (wanted) viewModel.requestUnrestricted(activity) else viewModel.openOptimizationList(activity)
        },
        onOpenVendorSettings = { viewModel.openVendorSettings(context.findActivity()) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveScreen(
    state: KeepAliveState,
    vendor: DeviceVendor,
    onBack: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onOpenVendorSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_keep_alive)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
            item { SectionHeader(stringResource(R.string.msp_keep_alive_section_switch)) }

            item {
                SettingsSwitchRow(
                    title = stringResource(R.string.msp_keep_alive_switch_title),
                    subtitle = KeepAliveSummaries.switchSubtitle(state).string(),
                    checked = state == KeepAliveState.UNRESTRICTED,
                    onCheckedChange = onCheckedChange,
                    icon = { Icon(Icons.Outlined.BatterySaver, contentDescription = null) },
                    help = stringResource(R.string.msp_keep_alive_switch_help),
                )
            }

            // 说明必须跟着开关走，而且**必须**说清「关掉 ≠ 已经退出」：
            // 系统只允许应用申请加入白名单，移出是用户在系统页面里的操作。
            // 不写这一句，用户拨回去看到开关弹开（`refresh` 读到还在名单里）
            // 会以为开关坏了。
            item { InfoNote(stringResource(R.string.msp_keep_alive_switch_note)) }

            item { SectionHeader(stringResource(R.string.msp_keep_alive_section_vendor)) }

            item {
                SettingActionButtonRow(
                    icon = Icons.Outlined.Tune,
                    title = KeepAliveSummaries.vendorTitle(vendor).string(),
                    subtitle = KeepAliveSummaries.vendorSubtitle(vendor).string(),
                    action = stringResource(R.string.msp_keep_alive_vendor_action),
                    onAction = onOpenVendorSettings,
                    help = stringResource(R.string.msp_keep_alive_vendor_help),
                )
            }
        }
    }
}
