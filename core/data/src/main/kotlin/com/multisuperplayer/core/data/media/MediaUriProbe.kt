package com.multisuperplayer.core.data.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.localFilePath
import java.io.File

private const val TAG = "MediaUriProbe"

private const val CONTENT_SCHEME = "content://"

/** [mediaUriKindOf] 的三种结论：这条 uri 该问谁。 */
internal enum class MediaUriKind {

    /** `content://…`：必须问内容提供者，**不能**当路径交给 `File`。 */
    CONTENT,

    /** 本机路径（裸路径或 `file://`）。 */
    FILE_SYSTEM,

    /** 判断不了（空串、`http://` 这类根本没有本地文件的）。一律当作「不在」。 */
    UNSUPPORTED,
}

/**
 * 这条 uri 该问谁。
 *
 * 单独抠出来是因为它是 v1.2.4 那条 bug 的**唯一判据**，而它只能靠单测钉住：
 * 判错的症状是「文件明明在，界面上却写着文件已不在」，不抛异常也不崩。
 * 判据本身是纯字符串规则（[localFilePath] 也是纯 Kotlin），所以在 JVM 上能跑；
 * 真正的 `query` / `stat` 才需要 Android（见 [MediaUriProbe]）。
 */
internal fun mediaUriKindOf(uri: String): MediaUriKind = when {
    uri.isEmpty() -> MediaUriKind.UNSUPPORTED
    uri.startsWith(CONTENT_SCHEME, ignoreCase = true) -> MediaUriKind.CONTENT
    localFilePath(uri) != null -> MediaUriKind.FILE_SYSTEM
    else -> MediaUriKind.UNSUPPORTED
}

/**
 * 「这个条目现在还能不能读到」。
 *
 * ## 为什么不能只用 `java.io.File`
 *
 * 播放列表里的条目带着写入时的 `PlaylistItem.uri`，它有两种形态：
 *
 * - 媒体库条目 ⇒ `content://media/…`；
 * - 浏览页条目 ⇒ **绝对路径**（文件系统入口）或 **SAF 的 document uri**
 *   （`content://…/tree/…/document/…`，系统文件选择器入口，见
 *   `BrowserEntry.sourceOfRef`）。
 *
 * 第二种里的 SAF 那一半是 v1.2.4 的 bug 本体：`File("content://…").exists()`
 * 对任何 content uri 都**必然**返回 false，于是「在系统文件选择器添加的文件夹里
 * 挑的文件，一加进播放列表就显示『文件已不在』」——文件好端端地在那儿，
 * 判据用错了 API。
 *
 * 所以按 scheme 分两条路：文件路径 `stat`，content uri 交给 provider 问。
 * 两者都以「现在读得到吗」为答案，而不是「我记得它存在吗」。
 *
 * ## 为什么先 query 再退化成打开
 *
 * 一次带 `COLUMN_DOCUMENT_ID` 投影的 `query` 是 DocumentsProvider 最便宜的
 * 问法（不打开文件、不读内容，只看那一行还在不在）。但它要求对方**真的是**
 * DocumentsProvider：别的 content uri（MediaStore 的、别的应用递进来的）会以
 * `IllegalArgumentException` 告诉我们「不认识这个投影」，那时退一步用
 * `openAssetFileDescriptor` 判断「能不能打开」。
 *
 * 两条路都会失败的情况（授权被收回的 `SecurityException`、provider 崩了）
 * 一律返回 false：判断不了的条目当「还在」会让用户点进去才撞上错误。
 */
class MediaUriProbe(private val context: Context) {

    /**
     * [uri] 指向的文件现在还在不在。
     *
     * 会做一次 IO（`stat` / 一次跨进程 `query`），**必须放在 IO 线程上调用**
     * （见 `PlaylistsViewModel` 里的 `flowOn(dispatchers.io)`）。
     */
    fun exists(uri: String): Boolean = when (mediaUriKindOf(uri)) {
        MediaUriKind.CONTENT -> contentExists(uri)
        // `localFilePath` 而不是直接当路径用：它顺手把 `file://` 的百分号转义解回
        // 真实路径（v1.2.2 的教训，见 `LocalPathUri`），对裸路径则原样返回。
        MediaUriKind.FILE_SYSTEM -> localFilePath(uri)?.let(::fileExists) ?: false
        MediaUriKind.UNSUPPORTED -> false
    }

    private fun fileExists(path: String): Boolean =
        runCatching { File(path).exists() }.getOrDefault(false)

    private fun contentExists(uri: String): Boolean {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return false
        val queried = runCatching { documentRowExists(parsed) }
        return queried.getOrElse { error ->
            MspLog.w(TAG, error) { "查询失败，改用打开试试：$uri" }
            openable(parsed)
        }
    }

    /** provider 里还有这一行。 */
    private fun documentRowExists(uri: Uri): Boolean = resolver
        .query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
        ?.use { it.moveToFirst() }
        ?: false

    private fun openable(uri: Uri): Boolean =
        runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }
            .getOrDefault(false)

    private val resolver: ContentResolver get() = context.applicationContext.contentResolver
}
