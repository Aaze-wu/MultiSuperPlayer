package com.multisuperplayer.core.data.subtitle

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.library.SafScanRules

private const val TAG = "SubtitleLocatorSaf"

/**
 * 在 SAF 目录里找外挂字幕。
 *
 * ## 为什么不能复用 [SubtitleFileLocator]
 *
 * 那条路是拿 `MediaEntry.relativePath` 去查 `MediaStore.Files` 的 `RELATIVE_PATH`。
 * 对 SAF 条目这条路**必然失败**，而且失败得很安静（返回「这个目录没有字幕」）：
 *
 * - SAF 扫描出来的 `relativePath` 是我们从 documentId 里推的（`Movies/`），
 *   MediaStore 的 `RELATIVE_PATH` 也是这个形状，看起来能比，其实只能比中
 *   「恰好也在共享存储里、而且被 MediaStore 索引过」的那部分；
 * - 用户授权 SAF 的典型动机恰恰是**MediaStore 看不见的地方**：SD 卡上被限制的
 *   目录、云盘 provider、别的应用暴露出来的目录。这些地方查询直接返回 0 行。
 *
 * 所以这里换一个问法：拿媒体文件自己的 document uri，推出它所在目录的
 * documentId，再用 `buildChildDocumentsUriUsingTree` 列出**同一个目录**的兄弟文件。
 * 这和用户的心理模型一致（「字幕和视频放一起」），也不依赖 MediaStore 索引。
 *
 * ## 字幕文件的 uri 怎么来
 *
 * 目录里的每个子项都带上自己的 `DOCUMENT_ID`，配上原 uri 里的 tree，
 * 用 `buildDocumentUriUsingTree` 拼出**可直接读**的 document uri——
 * 注意不是 tree uri（tree uri 给 `openInputStream` 会失败）。
 *
 * ## 和 MediaStore 版共用一套结论类型
 *
 * 返回的还是 [DirectoryScan]。三个分支的含义完全一样，于是
 * [SubtitleRepository] 的两条来源可以共用同一段「结论 → 界面提示」的翻译，
 * 不会出现「SAF 路径少处理一种情况」这种事。
 */
class SafSubtitleLocator(private val context: Context) {

    /**
     * 扫 [mediaDocumentUri] 所在的目录（`MediaEntry.uri`，SAF 条目）。
     *
     * 不抛异常：SAF 目录本来就随时可能因为授权被回收而问不通（用户卸载了那个
     * 应用、撤销了权限、云盘掉线），那属于**正常状态**而不是 bug，
     * 一律落成 [DirectoryScan.Invisible]。
     */
    fun scan(mediaDocumentUri: String): DirectoryScan {
        val mediaUri = runCatching { Uri.parse(mediaDocumentUri) }.getOrNull()
            ?: return DirectoryScan.Invisible

        // tree 段必须存在，否则拼不出 children uri。非 ExternalStorageProvider
        // 之外还有一类 uri 压根没有 tree（比如应用自己传进来的 document uri）。
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(mediaUri) }
            .getOrNull()
            ?: run {
                MspLog.d(TAG) { "这个 uri 没有 tree 段，无法列目录：$mediaDocumentUri" }
                return DirectoryScan.Invisible
            }

        // 目录的 documentId 从**媒体自己**的 documentId 推——不能用
        // getTreeDocumentId，那是用户授权时选的那一层，可能高出好几级
        // （授权了整个 SD 卡，视频在 SD/Movies 下面）。
        val documentId = runCatching { DocumentsContract.getDocumentId(mediaUri) }.getOrNull()
            ?: return DirectoryScan.Invisible
        val parentDocumentId = SafScanRules.parentDocumentIdOf(documentId)
            ?: return DirectoryScan.Invisible

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(mediaUri, parentDocumentId)
        }.getOrNull() ?: return DirectoryScan.Invisible

        val rows = try {
            queryChildren(childrenUri, mediaUri)
        } catch (error: Exception) {
            // SecurityException（授权被回收）是最常见的一种，但它和
            // 「provider 崩了」在这里的处理是一样的：这次问不通。
            MspLog.w(TAG, error) { "列出目录失败（documentId=$parentDocumentId），按看不见处理" }
            return DirectoryScan.Invisible
        }

        val subtitles = rows.filter { it.isSubtitle }
        if (subtitles.isNotEmpty()) {
            return DirectoryScan.Found(subtitles.map { SubtitleFileRow(it.uri, it.name, it.sizeBytes) })
        }

        val visible = rows.count { !it.isDirectory }
        MspLog.d(TAG) { "目录（documentId=$parentDocumentId）未发现字幕；可见文件数 = $visible" }
        // 和 MediaStore 版一致：一个文件都看不见说明这次查询没通，
        // 「目录是空的」和「看不见」对用户是两件事。
        return if (visible == 0) DirectoryScan.Invisible else DirectoryScan.NoSubtitles(visible)
    }

    private fun queryChildren(childrenUri: Uri, mediaUri: Uri): List<ChildRow> {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val extensions = SubtitleFileNaming.discoverableExtensions
        val rows = mutableListOf<ChildRow>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val childDocumentId = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue
                val mimeType = cursor.getString(mimeIndex)
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                rows += ChildRow(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(mediaUri, childDocumentId).toString(),
                    sizeBytes = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                    isDirectory = isDirectory,
                    isSubtitle = !isDirectory && SafScanRules.extensionOf(name) in extensions,
                )
            }
        }
        return rows
    }

    private val resolver: ContentResolver get() = context.applicationContext.contentResolver

    private class ChildRow(
        val name: String,
        val uri: String,
        val sizeBytes: Long,
        val isDirectory: Boolean,
        val isSubtitle: Boolean,
    )
}
