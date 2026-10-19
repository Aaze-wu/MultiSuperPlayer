package com.multisuperplayer.core.data.history

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.library.SafScanRules
import com.multisuperplayer.core.data.media.MediaUriKind
import com.multisuperplayer.core.data.media.mediaUriKindOf
import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.localFilePath
import java.io.File

private const val TAG = "RecentMediaResolver"

/**
 * 把一条「媒体库里查不到」的续播记录还原成一个能显示的媒体条目。
 *
 * ## 这个类存在的唯一原因
 *
 * 浏览页（内置文件浏览器 / 系统文件选择器）里播放的文件**刻意不进媒体库**
 * （见 `BrowseScreen` 与 `BrowserEntry.toMediaEntry`），可它们的续播记录照样会被
 * 写下来，主键是 `file:<ref>`。而「最近播放」是把续播记录投影到**媒体库条目**
 * 上的（`RecentPlayRules.project`），于是这些记录一条都投影不出来——
 * 从浏览页播过的文件永远不出现在最近播放里，记录却实实在在躺在磁盘上。
 *
 * 所以投影之前先问这里一次：查不到的那条 id 对应的文件**现在还在不在**，
 * 在就当场拼一个条目出来。
 *
 * ## 三条不肯越过的线
 *
 * 1. **不写进媒体库。** 媒体库是「扫描/授权目录」的结果，往里塞一条浏览页的东西
 *    会污染按文件夹分组、让同目录字幕查找走错路（那条路依赖 `relativePath`）。
 * 2. **不删记录。** 现在解析不出来可能只是 U 盘拔了、SAF 授权被回收——补回来之后
 *    它应该自己出现在列表里。
 * 3. **判据是「现在读得到吗」，不是「我记得它在」。** 和 v1.2.4 的
 *    [com.multisuperplayer.core.data.media.MediaUriProbe] 同一个道理：
 *    `File("content://…").exists()` 对任何 content uri 都必然返回 false。
 */
class RecentMediaResolver(private val context: Context) {

    /**
     * [mediaId] 对应的条目；不是浏览页给的 id、或者文件现在读不到时返回 `null`。
     *
     * 会做一次 IO（文件 `stat` 或一次跨进程 `query`），**必须放在 IO 线程上调用**。
     */
    fun resolve(mediaId: String): MediaEntry? {
        // 媒体库的 id（MediaStore 数字 id / `saf:…`）查不到就是真的没了一行，
        // 不能靠「文件可能还在」把它救回来：它的元数据只能来自 MediaStore。
        if (!BrowserEntry.isBrowserMediaId(mediaId)) return null
        val ref = BrowserEntry.refOf(mediaId)
        if (ref.isEmpty()) return null
        return when (mediaUriKindOf(ref)) {
            MediaUriKind.CONTENT -> contentSnapshot(ref)
            MediaUriKind.FILE_SYSTEM -> fileSnapshot(ref)
            MediaUriKind.UNSUPPORTED -> null
        }
    }

    /**
     * 本地路径（含 `file://`）：`stat` 一次，顺手拿到名字/大小/修改时间。
     *
     * 用 `isFile` 而不是 `exists`：路径可能被换成了一个同名目录（U 盘插拔后常见），
     * 那样「存在」也不代表还能播。
     */
    private fun fileSnapshot(ref: String): MediaEntry? {
        val path = localFilePath(ref) ?: return null
        val file = runCatching { File(path) }.getOrNull() ?: return null
        if (!runCatching { file.isFile }.getOrDefault(false)) return null
        return snapshotOf(
            ref = ref,
            name = file.name,
            sizeBytes = file.length(),
            lastModifiedMs = file.lastModified(),
            mimeType = null,
        )
    }

    /**
     * `content://…`：问 provider 一次。
     *
     * 先按「DocumentsProvider 与 MediaStore 都认的三列」问（`_display_name` /
     * `_size` / `mime_type`），这样两种 provider 都能一次问住——**不要**顺手把
     * `last_modified` 加进来：MediaStore 那一列叫 `date_modified` 且单位是秒，
     * 查一个不存在的列会直接抛 `IllegalArgumentException`，把整次查询废掉。
     *
     * provider 不认识这套投影时退一步只确认「还能不能打开」，名字从 uri 上取。
     */
    private fun contentSnapshot(ref: String): MediaEntry? {
        val uri = runCatching { Uri.parse(ref) }.getOrNull() ?: return null
        val snapshot = runCatching { querySnapshot(uri) }
            .getOrElse { error ->
                MspLog.w(TAG, error) { "最近播放：查询失败，改用打开试试：$ref" }
                if (openable(uri)) Snapshot(name = null, sizeBytes = 0L, mimeType = null) else null
            }
            // provider 答得干脆：没有这一行。那文件是真的不在了。
            ?: return null
        val name = snapshot.name?.takeIf { it.isNotBlank() } ?: nameFromUri(uri)
        if (name.isEmpty()) return null
        return snapshotOf(
            ref = ref,
            name = name,
            sizeBytes = snapshot.sizeBytes,
            // 时间戳在这一层拿不到（见上面那段的解释），排「最近添加」时按最旧算。
            lastModifiedMs = 0L,
            mimeType = snapshot.mimeType,
        )
    }

    private fun snapshotOf(
        ref: String,
        name: String,
        sizeBytes: Long,
        lastModifiedMs: Long,
        mimeType: String?,
    ): MediaEntry? = BrowserEntry(
        ref = ref,
        name = name,
        isDirectory = false,
        sizeBytes = sizeBytes,
        lastModifiedMs = lastModifiedMs,
        // `null` 表示「明确不是媒体」（`.lrc` 之类）。真发生过那种事就干脆不显示，
        // 否则「最近播放」里会出现一行点了只会失败的东西。
        kind = SafScanRules.kindOf(name, mimeType),
        mimeType = mimeType,
    ).toMediaEntry()

    /** 抛出异常 = 这个 provider 不吃这套投影；返回 `null` = 确实没有这一行。 */
    private fun querySnapshot(uri: Uri): Snapshot? = resolver
        .query(uri, QUERY_COLUMNS, null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            Snapshot(
                name = cursor.getString(COLUMN_NAME),
                sizeBytes = if (cursor.isNull(COLUMN_SIZE)) 0L else cursor.getLong(COLUMN_SIZE),
                mimeType = cursor.getString(COLUMN_MIME),
            )
        }

    private fun openable(uri: Uri): Boolean = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * 从 uri 上取一个能当标题的名字。
     *
     * document uri 的最后一段是 **documentId**（`primary:Music/a.mp3`），所以还要再
     * 取一次最后一段并解转义；取不到就返回空串，由调用方当作「解析失败」。
     */
    private fun nameFromUri(uri: Uri): String {
        val raw = runCatching { uri.lastPathSegment }.getOrNull().orEmpty()
        return Uri.decode(raw.substringAfterLast('/')).trim()
    }

    private val resolver: ContentResolver get() = context.applicationContext.contentResolver

    private class Snapshot(val name: String?, val sizeBytes: Long, val mimeType: String?)

    private companion object {
        /** 列下标就是这一组的顺序，改的时候两处一起改。 */
        val QUERY_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        const val COLUMN_NAME = 0
        const val COLUMN_SIZE = 1
        const val COLUMN_MIME = 2
    }
}
