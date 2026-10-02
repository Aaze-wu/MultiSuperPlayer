package com.multisuperplayer.core.data.subtitle

import android.content.Context
import android.net.Uri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.withContext

/**
 * 把导出的字幕文本写到 SAF 选定的文件。
 *
 * 三个刻意的选择：
 *
 * - **不申请任何存储权限**：SAF 返回的 uri 上已经带着用户在这个位置上的授权，
 *   再申请 `WRITE_EXTERNAL_STORAGE` 既过不了商店审核，也没必要；
 * - **UTF-8 不带 BOM**：字幕文件的事实标准。带 BOM 时部分播放器会把 BOM
 *   当成第一行的内容，显示成一行乱码；
 * - **失败只记日志并返回 false**：导出失败不该崩。译文已经在缓存里了，
 *   重来一次不花额外的钱——这一点要让调用方知道，所以返回布尔而不是抛。
 */
class SubtitleExportWriter(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext

    suspend fun write(uri: Uri, text: String): Boolean = withContext(dispatchers.io) {
        runCatching {
            // "wt" 而不是 "w"：SAF 的某些 provider 在 "w" 下不截断，
            // 重新导出更短的内容时会留下上一版文件的尾巴。
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
            } ?: throw IllegalStateException("openOutputStream 返回 null")
        }.onFailure {
            MspLog.w(TAG, it) { "导出字幕失败：$uri" }
        }.isSuccess
    }

    private companion object {
        const val TAG = "SubtitleExport"
    }
}
