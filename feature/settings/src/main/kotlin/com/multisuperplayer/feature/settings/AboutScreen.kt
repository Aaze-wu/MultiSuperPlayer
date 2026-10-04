package com.multisuperplayer.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.device.DeviceSnapshot
import com.multisuperplayer.core.common.info.InfoRow
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/** 导出日志的 MIME。`.txt` 最方便用户直接查看和粘贴。 */
private const val LOG_MIME_TYPE = "text/plain"

/** 应用名。品牌名三语相同，所以不建资源（和 `app_name` 一致）。 */
private const val APP_NAME = "MultiSuperPlayer"

/**
 * 项目主页与问题反馈。
 *
 * 写死常量而不是从构建配置推：这两个地址在调试包里也应该能用，
 * 而它们与仓库地址（`git remote`）没有任何运行时关系。"
 */
private const val PROJECT_HOME_URL = "https://github.com/Aaze-wu/MultiSuperPlayer"
private const val PROJECT_ISSUES_URL = "https://github.com/Aaze-wu/MultiSuperPlayer/issues"

/**
 * 关于。从设置入口页推上来。
 *
 * 这一页故意**只用系统自带的图标和 Material 组件**，不引入任何新依赖：
 * 「关于」页是发布前最后会被人打开、也最容易被改坏的一页，
 * 让它和项目的依赖图保持零耦合，日后换 UI 库时才不会卡在这里。
 */
@Composable
fun AboutRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = koinViewModel(),
) {
    val logSummary by viewModel.logSummary.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()
    val linkFailure by viewModel.linkFailure.collectAsStateWithLifecycle()

    // SAF 的「保存到…」。用系统文件选择器而不是自己写外部私有目录：
    // Android 11 起 `Android/data` 在文件管理器里不可见，写进去用户根本找不到，
    // 于是「导出成功」变成一句空话。顺带也就不需要任何存储权限。
    val saverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(LOG_MIME_TYPE),
    ) { uri ->
        // uri == null 就是用户在系统选择器里按了返回：这不是失败，什么都不用说。
        if (uri != null) viewModel.exportTo(uri)
    }

    AboutScreen(
        buildInfo = viewModel.buildInfo,
        device = viewModel.device,
        logSummary = logSummary,
        export = export,
        onBack = onBack,
        onExportLogs = { saverLauncher.launch(viewModel.suggestedFileName()) },
        linkFailure = linkFailure,
        onOpenLink = viewModel::openLink,
        onDismissLinkFailure = viewModel::dismissLinkFailure,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    buildInfo: AppBuildInfo,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    device: DeviceSnapshot? = null,
    logSummary: LogSummary = LogSummary.Empty,
    export: LogExportState = LogExportState.Idle,
    onExportLogs: () -> Unit = {},
    linkFailure: MspText? = null,
    onOpenLink: (String) -> Unit = {},
    onDismissLinkFailure: () -> Unit = {},
) {
    var showWhatsNew by remember { mutableStateOf(false) }
    var showLicenseDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.msp_settings_about)) },
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
            item { AboutHeader(buildInfo) }

            item { SectionHeader(stringResource(R.string.msp_settings_section_device)) }
            item {
                val rows = device?.rows()
                if (rows == null) {
                    // 读设备信息失败时不留一片空白：空白看起来像「这一节坏了」，而
                    // 说清楚「读不到」至少让人知道版本小节里的信息还是可信的。
                    InfoRowList(
                        listOf(
                            InfoRow(
                                MspText.Res(R.string.msp_settings_section_device),
                                MspText.Res(R.string.msp_settings_device_unavailable),
                            ),
                        ),
                    )
                } else {
                    InfoRowList(rows)
                }
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_links)) }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.Language,
                    title = stringResource(R.string.msp_settings_about_homepage),
                    subtitle = stringResource(R.string.msp_settings_about_homepage_desc),
                    onClick = { onOpenLink(PROJECT_HOME_URL) },
                )
            }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.msp_settings_about_feedback),
                    subtitle = stringResource(R.string.msp_settings_about_feedback_desc),
                    onClick = { onOpenLink(PROJECT_ISSUES_URL) },
                )
            }
            item {
                SettingActionRow(
                    icon = Icons.Outlined.Article,
                    title = stringResource(R.string.msp_settings_about_whats_new),
                    subtitle = stringResource(R.string.msp_settings_about_whats_new_desc),
                    onClick = { showWhatsNew = true },
                )
            }
            // 失败提示挂在链接小节正下方，而不是页面底部：它说的是上面那三行里
            // 「哪一下没成」，离得远就成了另一件事。
            linkFailure?.let { failure ->
                item { LinkFailureNote(failure = failure, onDismiss = onDismissLinkFailure) }
            }

            item { SectionHeader(stringResource(R.string.msp_settings_section_license)) }
            item {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Article, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.msp_settings_license_row_title)) },
                    supportingContent = { Text(stringResource(R.string.msp_settings_license_row_desc)) },
                    trailingContent = {
                        TextButton(onClick = { showLicenseDialog = true }) {
                            Text(stringResource(R.string.msp_settings_view))
                        }
                    },
                )
            }
            item {
                InfoRowList(
                    listOf(
                        InfoRow(
                            MspText.Res(R.string.msp_settings_log_files),
                            SettingsSummaries.logFiles(logSummary),
                        ),
                        InfoRow(
                            MspText.Res(R.string.msp_settings_log_range),
                            SettingsSummaries.logRange(logSummary),
                        ),
                    ),
                )
            }
            item {
                // 概览为空也**不置灰**：置灰的按钮需要一句解释，而「还没有日志文件时
                // 导出一份说明文件」本身是无害且诚实的。真正的坑是反过来——
                // 有文件却因为某个判断失误被置灰（用户拿不到唯一的求助手段）。
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = onExportLogs,
                        enabled = export != LogExportState.Running,
                    ) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.msp_settings_export_logs))
                    }
                    Spacer(Modifier.height(8.dp))
                    ExportStatus(export)
                }
            }
        }
    }

    if (showWhatsNew) {
        AlertDialog(
            onDismissRequest = { showWhatsNew = false },
            title = { Text(stringResource(R.string.msp_settings_about_whats_new)) },
            text = {
                // 正文十来行，小屏横屏会顶出对话框：内容区必须能滚，
                // 否则底部的「关闭」会被推到屏幕外，用户只能按返回键。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.msp_settings_about_whats_new_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showWhatsNew = false }) {
                    Text(stringResource(R.string.msp_settings_close))
                }
            },
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text(stringResource(R.string.msp_settings_license_row_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LicenseEntry(
                        name = APP_NAME,
                        license = "GPL-3.0",
                        detail = stringResource(R.string.msp_settings_license_app_detail),
                    )
                    LicenseEntry(
                        name = "NextLib（nextlib-media3ext）",
                        license = "GPL-3.0",
                        detail = stringResource(R.string.msp_settings_license_nextlib_detail),
                    )
                    LicenseEntry(
                        name = "AndroidX Media3",
                        license = "Apache-2.0",
                        detail = stringResource(R.string.msp_settings_license_media3_detail),
                    )
                    LicenseEntry(
                        name = "Kotlin / Jetpack Compose / Koin",
                        license = "Apache-2.0",
                        detail = stringResource(R.string.msp_settings_license_toolchain_detail),
                    )
                    Text(
                        text = stringResource(R.string.msp_settings_license_gpl_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) {
                    Text(stringResource(R.string.msp_settings_close))
                }
            },
        )
    }
}

// --------------------------------------------------------------------- 小件

/**
 * 页头：应用图标 + 名称 + 版本号（+ 预发行标记）。
 *
 * 版本信息从原来的「版本」小节搬到这里，那一整节也就不再存在：它原本有三行，
 * 其中「构建来源（git commit）」和「构建时间」对普通用户没有任何意义——
 * 它们是给「把日志发给我们看」用的，而那份抬头由 `AboutViewModel.reportHeader()`
 * 单独拼出来，不依赖这一页显示了几行。
 */
@Composable
private fun AboutHeader(buildInfo: AppBuildInfo) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 用系统自带的 PlayArrow 配一个圆形底色，而不是引用 `ic_launcher`：
        // 图标属于 app 模块的资源，feature 模块在编译期根本引不到它。
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(text = APP_NAME, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildInfo.versionText().string(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (buildInfo.isPreview) {
            Spacer(Modifier.height(10.dp))
            Text(
                // 「测试版」和「预览版」说的不是同一件事：前者是「可以用了，帮忙看看」，
                // 后者是「随时会变，别当回事」。`beta` 现在是对外发的那一档，
                // 一律写成「预览版」等于把一个正常可用的公开版本说成实验品。
                text = stringResource(
                    if (buildInfo.isBetaChannel) {
                        R.string.msp_settings_about_beta
                    } else {
                        R.string.msp_settings_about_preview
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.msp_settings_about_preview_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 打不开链接时的行内提示。
 *
 * **不做成弹窗**：失败信息里唯一有用的是那个地址，弹窗一关就没了，
 * 而用户下一步多半是「手动把地址抄到浏览器里」。留在页面上可以直接长按复制。
 */
@Composable
private fun LinkFailureNote(failure: MspText, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = failure.string(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            // weight：长地址要换行，不能把「知道了」按钮挤出屏幕。
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.msp_settings_got_it))
        }
    }
}

/** 键值对表格。左列固定宽度，让「版本/设备/屏幕」几行的值在同一个 x 上起头。 */
@Composable
private fun InfoRowList(rows: List<InfoRow>) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = row.label.string(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    text = row.value.string(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun LicenseEntry(name: String, license: String, detail: String) {
    Column {
        Text(text = "$name — $license", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportStatus(export: LogExportState) {
    when (export) {
        LogExportState.Idle -> InfoNote(
            stringResource(R.string.msp_settings_export_hint_idle),
        )

        LogExportState.Running -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.msp_settings_export_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is LogExportState.Saved -> {
            Text(
                text = stringResource(
                    R.string.msp_settings_export_saved,
                    export.fileName,
                    export.includedFiles,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (export.failedFiles.isNotEmpty()) {
                // 跳过的那几个必须点出来：报告看起来「完整」，缺的恰好可能是崩掉的那天。
                Spacer(Modifier.height(4.dp))
                val separator = stringResource(R.string.msp_settings_list_separator)
                Text(
                    text = stringResource(
                        R.string.msp_settings_export_skipped,
                        export.failedFiles.size,
                        export.failedFiles.joinToString(separator),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is LogExportState.Failed -> Text(
            text = stringResource(R.string.msp_settings_export_failed, export.message.string()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// 日志概览与日期范围这两行的文本在 SettingsSummaries 里（纯函数、有单测）。
// 之前它们是这个文件里的 private 函数，测不到——而「一个日志文件都没有」和
// 「只剩一天」恰恰是最容易显示成半句话的两种情况。
