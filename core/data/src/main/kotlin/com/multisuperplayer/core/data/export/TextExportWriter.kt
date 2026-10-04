package com.multisuperplayer.core.data.export

import android.content.Context
import android.net.Uri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.withContext

/**
 * 把一段文本写到 SAF 选定的文件。字幕、播放记录、播放列表三种导出都走它。
 *
 * ## 为什么是一个通用写入器，而不是每个功能一个
 *
 * 「开流 → 写 UTF-8 → flush → 关流 → 失败只记日志」这套动作在三处一模一样。
 * 复制三份的代价不是多几行，而是**以后只会修到其中一处**：比如下面那个 `"wt"`，
 * 漏掉它的那一处症状是「重新导出更短的内容时，文件里留着上一版的尾巴」——
 * 一种没人会想到去复现的 bug。
 *
 * ## 为什么不需要任何存储权限
 *
 * SAF 返回的 uri 上已经带着用户在那个位置上的授权（`ACTION_CREATE_DOCUMENT` 的
 * 返回值天然可写），再申请 `WRITE_EXTERNAL_STORAGE` 既过不了商店审核，也毫无必要。
 *
 * ## 为什么用 `"wt"` 而不是 `"w"`
 *
 * 平台 `openOutputStream` 的 `"w"` 在部分 provider（尤其 DocumentsUI 自己）
 * 下**不截断**已有文件。用户第二次导出、而这份内容比上次短的时候，文件尾部会
 * 留着上一版的字节——一份「看起来多出来几行」的导出，内容还全都对得上，
 * 是最难被怀疑的那种坏法。
 *
 * ## BOM：CSV 要，JSON 不要，字幕也不要
 *
 * 这不是细节，是「导出能不能用」的分界线，所以 `withBom` 是**每次调用都要想一遍**
 * 的参数，而不是类级别的开关：
 *
 * - **CSV 必须带 UTF-8 BOM**。Excel / WPS 双击打开一份不带 BOM 的 UTF-8 CSV 时，
 *   会按系统 ANSI（简中即 GBK）解码，中文标题全变乱码。用户看到的是一份「坏掉的
 *   导出」，而我们这边一切正常——所以这个 BOM 是替**别人的解析器**打的补丁。
 * - **JSON 不能带 BOM**。RFC 8259 §8.1 明确要求实现不要加 BOM，而 `kotlinx.serialization`
 *   自己读一份带 BOM 的 JSON 也会在第一个字节上失败。
 * - **字幕不要 BOM**。它是字幕文件的事实标准，带 BOM 的部分播放器会把 BOM
 *   当成第一行的内容，显示出一行乱码。
 *
 * ## 失败返回 false，不抛
 *
 * 导出失败不该崩：内容都还在（字幕在文件里、记录在 DataStore 里、播放列表在磁盘上），
 * 重来一次也不花额外的钱。这一点要让调用方知道，所以返回布尔而不是抛异常。
 */
class TextExportWriter(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {
    private val appContext = context.applicationContext

    suspend fun write(uri: Uri, text: String, withBom: Boolean = false): Boolean =
        withContext(dispatchers.io) {
            runCatching {
                appContext.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(textBytes(text, withBom))
                    output.flush()
                } ?: throw IllegalStateException("openOutputStream 返回 null")
            }.onFailure {
                MspLog.w(TAG, it) { "导出失败：$uri" }
            }.isSuccess
        }

    private companion object {
        const val TAG = "Export"
    }
}

/**
 * 文本 → 字节（是否带 UTF-8 BOM）。
 *
 * 单独拎出来是因为它是这套逻辑里**唯一需要被单测钉住**的一步：
 * [TextExportWriter] 要 `Context`，在 JVM 单测里构造不出来，而「BOM 到底加没加」
 * 又是一个只在别人机器上（Excel 里）才会暴露的差别。
 */
internal fun textBytes(text: String, withBom: Boolean): ByteArray {
    val body = text.toByteArray(Charsets.UTF_8)
    if (!withBom) return body
    // EF BB BF = U+FEFF（BOM）的 UTF-8 编码。
    return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
}
