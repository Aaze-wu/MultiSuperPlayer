package com.multisuperplayer.core.data.browser

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.library.SafScanRules
import com.multisuperplayer.core.model.BrowserEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "BrowserSaf"

/**
 * 用 SAF（`DocumentsProvider`）列目录的来源：零特殊权限。
 *
 * ## 两种 ref 形态，靠 `document` 路径段区分
 *
 * 用户通过系统选择器授权时拿到的是一棵**树的根**，形式是 tree uri：
 *
 * ```
 * content://com.android.externalstorage.documents/tree/primary%3AMusic
 * ```
 *
 * 而它下面每一层的 ref 是一个 **document uri**（多一段 `/document/`）：
 *
 * ```
 * content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbum
 * ```
 *
 * 两者都带 `tree` 段，所以 `buildChildDocumentsUriUsingTree` 对两者都成立；
 * 区别只在「当前这一层的 documentId 是哪个」：树根要用 `getTreeDocumentId`，
 * 子目录要用 `getDocumentId`。
 *
 * 这个区分不能省——把 tree uri 交给 `getDocumentId` 会抛
 * `IllegalArgumentException`（它取的是 `document/` 后面那段，树根没有），
 * 于是「根目录点进去就崩」。
 *
 * ## 为什么返回的 uri 必须带 tree
 *
 * 子项的 ref 用 `buildDocumentUriUsingTree` 拼（而不是
 * `buildDocumentUri`）：只有带 tree 段的 document uri 才能在下一层继续
 * `buildChildDocumentsUriUsingTree`，也才能被 `openInputStream` 读。
 * 少了树名，`DocumentsContract` 找不到是哪个 provider 的哪棵树。
 */
internal class SafDocumentSource(private val context: Context) : DirectorySource {

    override val kind: BrowserSourceKind = BrowserSourceKind.SAF

    override suspend fun list(ref: String): BrowserListing = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(ref) }.getOrNull()
            ?: return@withContext BrowserListing.Unreadable

        // 没有 tree 段就不是文档树里的位置（比如别的应用直接塞进来的 document uri），
        // 拼不出 children uri，只能算问不通。
        val treeDocumentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: run {
                MspLog.d(TAG) { "uri 没有 tree 段，无法列目录：$ref" }
                return@withContext BrowserListing.Unreadable
            }

        val documentId = currentDocumentId(uri, treeDocumentId)
            ?: return@withContext BrowserListing.Unreadable

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
        }.getOrNull() ?: return@withContext BrowserListing.Unreadable

        val entries = try {
            queryChildren(childrenUri, uri)
        } catch (error: Exception) {
            // SecurityException（授权被回收 / 用户卸载了那个 provider）是这里最常见的一种，
            // 但它和「provider 崩了」的处理一样：这次问不通。旧代码里把这类异常
            // 吞成空列表，界面就会显示「这个目录是空的」，把唯一有用的线索藏掉了。
            MspLog.w(TAG, error) { "列出 SAF 目录失败（documentId=$documentId）" }
            return@withContext BrowserListing.Unreadable
        } ?: return@withContext BrowserListing.Unreadable

        BrowserListing.Ready(entries)
    }

    /**
     * 当前这一层的 documentId。
     *
     * 见类注释：树根取 `getTreeDocumentId`，子目录取 `getDocumentId`。
     */
    private fun currentDocumentId(uri: Uri, treeDocumentId: String): String? =
        if (DocumentsContract.isDocumentUri(context, uri)) {
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        } else {
            treeDocumentId
        }

    /** 查询失败时返回 `null`（区别于「查到了，但是空的」）。 */
    private fun queryChildren(childrenUri: Uri, treeUri: Uri): List<BrowserEntry>? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val entries = mutableListOf<BrowserEntry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            // SIZE / LAST_MODIFIED 是**可选**列：云盘 provider 经常不给。
            // 用 getColumnIndex（不带 OrThrow）并容忍 -1，否则一个不返回大小的
            // provider 会让整个目录列不出来——而它本来只是「少一个数字」。
            val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val timeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                // documentId 和 displayName 缺一个就没法用：前者是 ref 的来源，
                // 后者是用户唯一能看到的标识。跳过（而不是用空串兜底，
                // 那会在列表里留一行点不开的空条目）。
                val childDocumentId = cursor.getString(idIndex) ?: continue
                val name = cursor.getString(nameIndex) ?: continue

                val mimeType = cursor.getString(mimeIndex)
                val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                val sizeBytes = if (sizeIndex < 0 || cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                val lastModifiedMs = if (timeIndex < 0 || cursor.isNull(timeIndex)) 0L else cursor.getLong(timeIndex)

                entries += BrowserEntry(
                    ref = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId).toString(),
                    name = name,
                    isDirectory = isDirectory,
                    sizeBytes = if (isDirectory) 0L else sizeBytes.coerceAtLeast(0L),
                    lastModifiedMs = lastModifiedMs.coerceAtLeast(0L),
                    // MIME 在这里是有信息的（provider 通常给得比后缀准），
                    // 所以它先于后缀兜底生效——但仍由 SafScanRules 统一裁决，
                    // 保证三个来源的「什么算媒体」答案一致。
                    kind = if (isDirectory) null else SafScanRules.kindOf(name, mimeType),
                    mimeType = mimeType,
                )
            }
        } ?: return null
        return entries
    }

    private val resolver: ContentResolver get() = context.applicationContext.contentResolver
}
