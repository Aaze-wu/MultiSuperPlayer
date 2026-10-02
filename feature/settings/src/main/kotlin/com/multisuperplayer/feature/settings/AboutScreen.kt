package com.multisuperplayer.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SystemUpdate
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.device.DeviceSnapshot
import com.multisuperplayer.core.common.info.InfoRow
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.ui.text.string
import org.koin.androidx.compose.koinViewModel

/** 导出日志的 MIME。`.txt` 最方便用户直接查看和粘贴。 */
private const val LOG_MIME_TYPE = "text/plain"

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
) {
    var showUpdateDialog by remember { mutableStateOf(false) }
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
            item { SectionHeader(stringResource(R.string.msp_settings_section_version)) }
            item { InfoRowList(buildInfo.rows()) }

            item { SectionHeader(stringResource(R.string.msp_settings_section_update)) }
            item {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                    },
                    headlineContent = { Text(stringResource(R.string.msp_settings_check_update)) },
                    supportingContent = {
                        Text(stringResource(R.string.msp_settings_update_no_source))
                    },
                    trailingContent = {
                        TextButton(onClick = { showUpdateDialog = true }) {
                            Text(stringResource(R.string.msp_settings_check))
                        }
                    },
                )
            }

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

            item { SectionHeader(stringResource(R.string.msp_settings_section_logs)) }
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

    if (showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(stringResource(R.string.msp_settings_check_update)) },
            text = {
                Text(
                    stringResource(
                        R.string.msp_settings_update_dialog_text,
                        buildInfo.versionText().string(),
                        buildInfo.sourceText().string(),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text(stringResource(R.string.msp_settings_got_it))
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
                        name = "MultiSuperPlayer",
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
