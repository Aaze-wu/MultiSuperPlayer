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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.device.DeviceSnapshot
import com.multisuperplayer.core.common.info.InfoRow
import com.multisuperplayer.core.common.log.LogSummary
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
                title = { Text("关于") },
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
            item { SectionHeader("版本") }
            item { InfoRowList(buildInfo.rows()) }

            item { SectionHeader("更新") }
            item {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = {
                        Icon(Icons.Outlined.SystemUpdate, contentDescription = null)
                    },
                    headlineContent = { Text("检查更新") },
                    supportingContent = {
                        Text("还没有接入更新源：本版本没有发布渠道，这个包是本机自己构建的。")
                    },
                    trailingContent = {
                        TextButton(onClick = { showUpdateDialog = true }) { Text("检查") }
                    },
                )
            }

            item { SectionHeader("设备") }
            item {
                val rows = device?.rows()
                if (rows == null) {
                    // 读设备信息失败时不留一片空白：空白看起来像「这一节坏了」，而
                    // 说清楚「读不到」至少让人知道版本小节里的信息还是可信的。
                    InfoRowList(listOf(InfoRow("设备", DeviceSnapshot.UNKNOWN)))
                } else {
                    InfoRowList(rows)
                }
            }

            item { SectionHeader("开源许可") }
            item {
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Article, contentDescription = null) },
                    headlineContent = { Text("第三方组件与许可证") },
                    supportingContent = { Text("本应用整体以 GPL-3.0 分发") },
                    trailingContent = {
                        TextButton(onClick = { showLicenseDialog = true }) { Text("查看") }
                    },
                )
            }

            item { SectionHeader("日志") }
            item {
                InfoRowList(
                    listOf(
                        InfoRow("日志文件", SettingsSummaries.logFiles(logSummary)),
                        InfoRow("覆盖日期", SettingsSummaries.logRange(logSummary)),
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
                        Text("导出日志")
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
            title = { Text("检查更新") },
            text = {
                Text(
                    "还没有接入更新源，所以无法检查。\n\n" +
                        "当前版本 ${buildInfo.versionText()}，" +
                        "构建来源 ${buildInfo.sourceText()}。\n\n" +
                        "以后接上发布渠道后，这里会显示是否有新版本以及更新说明。",
                )
            },
            confirmButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("知道了") }
            },
        )
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text("第三方组件与许可证") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    LicenseEntry(
                        name = "MultiSuperPlayer",
                        license = "GPL-3.0",
                        detail = "本应用自身（仓库根目录的 LICENSE 文件）",
                    )
                    LicenseEntry(
                        name = "NextLib（nextlib-media3ext）",
                        license = "GPL-3.0",
                        detail = "内置 FFmpeg 软件解码；它的 FFmpeg 构建启用了 GPL 组件",
                    )
                    LicenseEntry(
                        name = "AndroidX Media3",
                        license = "Apache-2.0",
                        detail = "播放内核与媒体会话",
                    )
                    LicenseEntry(
                        name = "Kotlin / Jetpack Compose / Koin",
                        license = "Apache-2.0",
                        detail = "语言、界面与依赖注入",
                    )
                    Text(
                        text = "链入 NextLib 后，整个应用按 GPL-3.0 分发：对外发布安装包时" +
                            "必须同时提供完整源码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenseDialog = false }) { Text("关闭") }
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
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    text = row.value,
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
            "导出的是一个纯文本文件，包含本机保留的全部日志和上方的版本/设备信息。" +
                "出了问题把它发出来就能定位。",
        )

        LogExportState.Running -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                text = "正在写入…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is LogExportState.Saved -> {
            Text(
                text = "已保存：${export.fileName}（含 ${export.includedFiles} 个日志文件）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (export.failedFiles.isNotEmpty()) {
                // 跳过的那几个必须点出来：报告看起来「完整」，缺的恰好可能是崩掉的那天。
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "有 ${export.failedFiles.size} 个文件读不出来，已跳过：" +
                        export.failedFiles.joinToString("、"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        is LogExportState.Failed -> Text(
            text = "导出失败：${export.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// 日志概览与日期范围这两行的文本在 SettingsSummaries 里（纯函数、有单测）。
// 之前它们是这个文件里的 private 函数，测不到——而「一个日志文件都没有」和
// 「只剩一天」恰恰是最容易显示成半句话的两种情况。
