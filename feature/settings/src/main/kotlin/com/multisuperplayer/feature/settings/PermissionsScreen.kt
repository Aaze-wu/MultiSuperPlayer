package com.multisuperplayer.feature.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.data.permissions.PermissionKind
import com.multisuperplayer.core.data.permissions.PermissionRules
import com.multisuperplayer.core.data.permissions.PermissionSnapshot
import com.multisuperplayer.core.data.permissions.PermissionState
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/**
 * 「权限」页。
 *
 * ## 它管什么
 *
 * 只列**用户能改的**四项：媒体库读取、「所有文件访问」、通知、蓝牙（见
 * `PermissionSummaries.changeable`）。应用声明过的其余权限（`INTERNET` 之类）在末尾
 * 有一段折叠说明——它们安装时就有、应用自己关不掉，列成可点的行只会让人以为点了有用。
 *
 * ## 为什么这一页不自己判断版本
 *
 * 「这一项在这个系统上存在吗」「现在该申请哪些权限名」全都问 `AppPermissions`。
 * 界面上再写一遍版本分支，就等于把「Android 14 要额外申请 `READ_MEDIA_VISUAL_USER_SELECTED`」
 * 这条规则抄了两份，而抄错的那一份（申请一个当前版本不存在的权限）**系统连框都不弹**，
 * 用户看到的是「点了没反应」。
 *
 * ## 系统授权框不由这一页弹
 *
 * 弹框的 launcher 挂在**应用根上**（`MspApp`）。两个原因：
 * - 需求③ 的首次启动申请要在用户**从未进过这一页**的时候就弹出授权框，
 *   所以 launcher 不能长在这一页里；
 * - `PermissionsViewModel` 是 Activity 作用域的，这一页和根上的**是同一个实例**，
 *   于是同一个待办通道（`PermissionsViewModel.pending`）只会有一个消费者。
 *   两边各长一个 launcher 的话，同一时刻真的可能弹出两个框（后一个把前一个挤掉，
 *   而两边都以为已经申请过了）。
 *
 * 这一页只做两件事：把状态画出来、把用户送到能改它的地方。
 */
@Composable
fun PermissionsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PermissionsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 从系统设置页回来时重新问一次。这也覆盖了「刚弹完系统授权框」那一次：
    // 系统框会让 Activity 走一趟 onPause/onResume，而真正的新状态在用户点完之后才有。
    // 这里与 `PermissionsViewModel.pending` 的清除是两件独立的事，谁先谁后都不影响
    // 结论（读权限是幂等的）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh(context.findActivity())
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PermissionsScreen(
        state = state,
        onBack = onBack,
        onRequest = viewModel::request,
        onOpenSettings = { kind -> context.startActivity(viewModel.settingsIntent(kind)) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    state: PermissionSnapshot,
    onBack: () -> Unit,
    onRequest: (PermissionKind) -> Unit,
    onOpenSettings: (PermissionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_permissions)) },
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
            item { SectionHeader(stringResource(R.string.msp_permissions_section_changeable)) }

            items(items = PermissionSummaries.changeable, key = { it.name }) { kind ->
                PermissionRow(
                    kind = kind,
                    state = state[kind],
                    onRequest = onRequest,
                    onOpenSettings = onOpenSettings,
                )
            }

            item { SectionHeader(stringResource(R.string.msp_permissions_other_header)) }
            item { InfoNote(stringResource(R.string.msp_permissions_other_note)) }

            items(items = PermissionSummaries.otherPermissions, key = { it }) { permission ->
                // 副标题放**清单里的原始权限名**，不放同一句解释重复六遍：
                // 这一栏存在的意义就是「这一页没骗你，应用确实声明了这些」，
                // 而原始名字正是那句话的证据。
                SettingActionRow(
                    icon = Icons.Outlined.Lock,
                    title = PermissionSummaries.otherLabel(permission).string(),
                    subtitle = permission,
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

/**
 * 一项权限：状态在左、动作在右。
 *
 * **动作必须和按钮上写的字保持一致**：字来自 `PermissionSummaries.action`（它问
 * `PermissionRules.canAskInPlace`），这里的分支也问同一个函数。两处各写一套的话，
 * 「所有文件访问」会出现「按钮写着『申请』、点下去却跳去设置页」这种自相矛盾的组合。
 */
@Composable
private fun PermissionRow(
    kind: PermissionKind,
    state: PermissionState,
    onRequest: (PermissionKind) -> Unit,
    onOpenSettings: (PermissionKind) -> Unit,
) {
    val title = PermissionSummaries.title(kind).string()
    SettingActionButtonRow(
        icon = iconOf(kind),
        title = title,
        subtitle = PermissionSummaries.state(kind, state).string(),
        action = PermissionSummaries.action(kind, state)?.string(),
        onAction = {
            if (PermissionRules.canAskInPlace(kind, state)) {
                onRequest(kind)
            } else {
                onOpenSettings(kind)
            }
        },
        help = PermissionSummaries.description(kind).string(),
    )
}

/**
 * 每一行的图标。
 *
 * 「所有文件访问」用 [Icons.Outlined.Lock]：它和「媒体库读取」都是关于文件的，
 * 两个都用文件夹形状会让人扫视时分不开。锁表达的是「这是一次需要专门去系统里开的授权」。
 */
private fun iconOf(kind: PermissionKind): ImageVector = when (kind) {
    PermissionKind.MEDIA -> Icons.Outlined.FolderOpen
    PermissionKind.ALL_FILES -> Icons.Outlined.Lock
    PermissionKind.NOTIFICATION -> Icons.Outlined.Notifications
    PermissionKind.BLUETOOTH -> Icons.Outlined.Bluetooth
}
