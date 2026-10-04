package com.multisuperplayer.core.data.export

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import android.provider.OpenableColumns
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 读一个文本文件的结果。
 *
 * 失败刻意分成三种，因为**用户能做的事不一样**：
 * - [Failed.NoPermission]：重新选一次这个文件就会重新授权（SAF 的读权限是跟
 *   Uri 走的），所以那句话要引导「再选一次」；
 * - [Failed.NotFound]：文件被删了/移动了，只能换一个；
 * - [Failed.Other]：剩下的，把原因原样带出去——没有原因的话这类问题只能靠
 *   「再试一次」赌一把，而用户和我们都不知道在赌什么。
 *
 * 和 [TextExportWriter] 一样，这一层**不抛异常**：失败走返回值，
 * 每种失败都有对应的那句话。
 */
sealed interface TextImportResult {

    /**
     * 读到了。
     *
     * [displayName] 是 provider 报的文件名（可能没有）。它有两个用处：
     * 当作「文件里没有列表名」时的兜底名字，以及**告诉用户读的是哪个文件**。
     */
    data class Read(val text: String, val displayName: String?) : TextImportResult

    sealed interface Failed : TextImportResult {
        data object NoPermission : Failed
        data object NotFound : Failed
        data class Other(val reason: String) : Failed
    }
}

/**
 * 把用户选中的文件读成一段文本。
 *
 * ## 为什么和 [TextExportWriter] 是**两个**类，而不是一个读写合体
 *
 * 它们的失败分类完全不同（写只有「写不进去」，读有权限/找不到/其它三种），
 * 而且读这边多了两件写那边没有的事：剥 BOM、问文件名。合成一个类只会让
 * 「这个方法的失败意味着什么」变成一句说不清的话。
 *
 * ## BOM 一定要剥
 *
 * 我们自己导出的 CSV **带** UTF-8 BOM（见 [PlaybackExportFormat.needsBom]）：
 * 它是给 Excel 认编码的。但那段 BOM 会留在文件开头，读到第一行时表头就变成
 * `"\uFEFF标题"`——于是「导入自己刚导出的文件」会报「缺失『标题』列」，
 * 一个只在「自己导自己读」这条路上出现的失败。
 *
 * 顺带说明：UTF-16 之类的文件读出来会是乱码然后被判成「不是本应用导出的文件」。
 * 不专门支持它们——本应用从来没导出过那种编码。
 *
 * 读到的字节**固定按 UTF-8** 解码（我们导出时就是 UTF-8）。不跟随系统默认编码：
 * 那会让同一个文件在不同设备上读出不同的结果（`InputStreamReader` 不给编码时
 * 用的是平台默认编码）。
 */
class TextImportReader(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    /** 读整个文件。任何失败都走 [TextImportResult.Failed]。 */
    suspend fun read(uri: Uri): TextImportResult = withContext(dispatchers.io) {
        try {
            val text = appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.reader(Charsets.UTF_8).use { it.readText() }
            } ?: return@withContext TextImportResult.Failed.Other("openInputStream 返回 null")
            TextImportResult.Read(text = text.removePrefix(BOM), displayName = displayNameOf(uri))
        } catch (error: SecurityException) {
            MspLog.w(TAG, error) { "没有读取权限：$uri" }
            TextImportResult.Failed.NoPermission
        } catch (error: FileNotFoundException) {
            MspLog.w(TAG, error) { "文件不存在：$uri" }
            TextImportResult.Failed.NotFound
        } catch (error: IOException) {
            MspLog.w(TAG, error) { "读取失败：$uri" }
            TextImportResult.Failed.Other(error.message ?: error::class.java.simpleName)
        }
    }

    /**
     * CSV 的列名/类型别名表（三种已发布语言的写法都在里面）。
     *
     * 由资源层装出来而不是写死在解析器里：加了语言只改 `string-array`，
     * 而解析器那边的判断一个字都不用动（`PlaybackImportTest` 的资源用例
     * 会检查每个语言的列名都在别名数组里）。
     */
    fun csvAliases(resources: Resources): CsvAliases =
        CsvAliases.of { id -> resources.getStringArray(id).toList() }

    /**
     * 问 provider 要文件名。
     *
     * 失败了返回 null 而不是抛：文件名只用来做**兜底**（文件里没有列表名时用它，
     * 以及告诉用户读的是哪个文件），拿不到也不影响导入的内容。
     * 有些 provider 干脆不实现 [OpenableColumns]（返回 null 光标），这是正常的。
     */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.onFailure { error ->
        MspLog.w(TAG, error) { "读取文件名失败：$uri" }
    }.getOrNull()

    private companion object {
        const val TAG = "Import"

        /** U+FEFF。 */
        const val BOM = "\uFEFF"
    }
}
