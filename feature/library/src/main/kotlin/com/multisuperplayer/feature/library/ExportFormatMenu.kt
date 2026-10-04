package com.multisuperplayer.feature.library

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.multisuperplayer.core.data.export.PlaybackExportFormat
import com.multisuperplayer.core.ui.text.string

/**
 * 「导出成哪种格式」菜单。
 *
 * ## 为什么必须先问这一句
 *
 * SAF 的 `CreateDocument` 只能带**一个文件名**，带不了「CSV 还是 JSON」。所以
 * 导出天然是两步：先在这里定格式（点击那一刻就知道），再由系统选择器拿 uri。
 * 想省掉这一步的话只剩一条路：把扩展名当格式（看用户把文件存成 `.json` 就导
 * JSON）——那是猜，而用户改名只是为了「我要叫这个名」。
 *
 * ## 为什么两个入口共用它
 *
 * 这一页有两个导出图标（导当前 / 导全部），最近播放页还有第三处。三份副本意味着
 * 以后改文案、加格式只会改到其中一两处，而「屏幕 A 有 JSON、屏幕 B 没有」这种
 * 差异没人会去复现。菜单本身没有任何状态：开不开由调用方决定（它才知道谁是
 * 锚点），选完就回一个 [PlaybackExportFormat]。
 */
@Composable
internal fun ExportFormatMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPick: (PlaybackExportFormat) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // 枚举驱动而不是手写两项：以后加格式（说明 XLSX 不做，但 TXT 之类有可能）
        // 这里不必改，也不会漏一个。
        PlaybackExportFormat.entries.forEach { format ->
            DropdownMenuItem(
                // 「导出为 CSV」而不是只写「CSV」：孤零零一个格式名看不出是按它之后
                // 会发生什么，而这一项点下去紧接着就是系统保存框，选错的人要一路
                // 选到保存才会发现。
                text = { Text(stringResource(R.string.msp_export_as, format.label.string())) },
                onClick = { onPick(format) },
            )
        }
    }
}
