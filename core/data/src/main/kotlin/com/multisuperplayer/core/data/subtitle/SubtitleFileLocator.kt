package com.multisuperplayer.core.data.subtitle

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.multisuperplayer.core.common.log.MspLog

private const val TAG = "SubtitleLocator"

/** 一行候选字幕文件的原始信息（还没读内容、还没解析）。 */
data class SubtitleFileRow(
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
)

/**
 * 一次目录扫描的结果。
 *
 * 之所以拆成三个分支而不是「一个列表 + 一个计数」，是因为**两种「字幕数为 0」
 * 给用户的建议完全相反**：
 *
 * - [NoSubtitles]：目录里能看到别的文件，只是没有字幕 → 「请把字幕文件放到同一目录」；
 * - [Invisible]：这个目录在 MediaStore 里**一条记录都看不到**（连媒体自己都没有）
 *   → 这次查询根本没通，多半是权限被拒 → 「请检查存储权限」。
 *
 * 合并成一个空列表的话，用户会拿着「这个目录没有字幕」去反复检查文件名，
 * 而真正的原因在权限上。
 */
sealed interface DirectoryScan {
    /** 目录里查到了这些字幕后缀的文件。 */
    data class Found(val subtitles: List<SubtitleFileRow>) : DirectoryScan

    /** 目录可见，但没有字幕文件。[visibleFiles] 是该目录 MediaStore 可见的文件数。 */
    data class NoSubtitles(val visibleFiles: Int) : DirectoryScan

    /** 目录在 MediaStore 里完全不可见：查询没通，而不是没有文件。 */
    data object Invisible : DirectoryScan
}

/**
 * 在媒体文件所在目录里找外挂字幕。
 *
 * ## 为什么可以只查一个目录
 *
 * 外挂字幕必须和视频放在同一目录才会被任何播放器自动发现，这是约定俗成的做法，
 * 所以「按 `RELATIVE_PATH` 精确匹配」既够用又快——不需要遍历整个存储。
 * 目录信息来自 `MediaEntry.relativePath`，而它只在 Android 10+ 才有；
 * 更早的系统拿不到路径，那边只能走文件选择器。
 *
 * ## 这里**不吞异常**
 *
 * 查询可能因权限被拒抛 `SecurityException`。异常原样抛出，由 [SubtitleRepository]
 * 翻译成带原因的状态——理由同上：吞掉它就等于把「权限问题」伪装成「没有字幕」。
 */
class SubtitleFileLocator(private val context: Context) {

    /**
     * 扫描 [relativePath] 目录（`MediaEntry.relativePath` 的原始值，形如 `Movies/`）。
     *
     * @throws SecurityException 没有读取共享存储的权限。
     */
    fun scanDirectory(relativePath: String): DirectoryScan {
        // RELATIVE_PATH 是 Android 10 才引入的列。更早的系统上 `entry.relativePath`
        // 拿不到值，本不该走到这里；真走到了就直接说「看不见」，而不是让 SQLite
        // 报一个 `no such column` 把整个播放页拖崩。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            MspLog.d(TAG) { "系统版本低于 Android 10，无法按目录查询外挂字幕" }
            return DirectoryScan.Invisible
        }

        val collection = filesCollection()
        val (pathClause, pathArgs) = pathFilter(relativePath)
        val pendingClause = "$IS_PENDING = 0"

        val extensions = SubtitleFileNaming.discoverableExtensions
        val nameClause = extensions.joinToString(" OR ") { "$DISPLAY_NAME LIKE ?" }
        val subtitleSelection = listOf(nameClause, pathClause, pendingClause)
            .joinToString(" AND ") { "($it)" }
        val subtitleArgs = (extensions.map { "%.$it" } + pathArgs).toTypedArray()

        val subtitles = queryRows(collection, subtitleSelection, subtitleArgs)
        if (subtitles.isNotEmpty()) return DirectoryScan.Found(subtitles)

        // 字幕查询为空时再问一句「这个目录里 MediaStore 到底能看见什么」。
        // 多一次很轻的 COUNT 查询，换来把「没有字幕」和「看不到文件」分开。
        val visibleClause = listOf(pathClause, pendingClause).joinToString(" AND ") { "($it)" }
        val visible = countRows(collection, visibleClause, pathArgs.toTypedArray())
        MspLog.d(TAG) { "目录「$relativePath」未发现字幕；该目录 MediaStore 可见文件数 = $visible" }

        return if (visible == 0) DirectoryScan.Invisible else DirectoryScan.NoSubtitles(visible)
    }

    /**
     * 目录过滤条件。
     *
     * MediaStore 存目录时**带结尾斜杠**（`"Movies/"`），但个别机型存得不规范，
     * 所以两个变体都匹配。空值代表「共享存储根目录」，有两种存法（`""` 与 `"/"`）。
     */
    private fun pathFilter(relativePath: String): Pair<String, List<String>> {
        val normalized = relativePath.trim().trimEnd('/')
        return if (normalized.isEmpty()) {
            "($RELATIVE_PATH IS NULL OR $RELATIVE_PATH = '' OR $RELATIVE_PATH = '/')" to emptyList()
        } else {
            "($RELATIVE_PATH = ? OR $RELATIVE_PATH = ?)" to listOf("$normalized/", normalized)
        }
    }

    private fun queryRows(collection: Uri, selection: String, args: Array<String>): List<SubtitleFileRow> {
        val projection = arrayOf(ID, DISPLAY_NAME, SIZE)
        val rows = mutableListOf<SubtitleFileRow>()
        context.contentResolver.query(collection, projection, selection, args, "$DISPLAY_NAME ASC")
            ?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndexOrThrow(SIZE)
                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameIndex) ?: continue
                    rows += SubtitleFileRow(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex)).toString(),
                        fileName = fileName,
                        sizeBytes = cursor.getLong(sizeIndex),
                    )
                }
            }
        return rows
    }

    private fun countRows(collection: Uri, selection: String, args: Array<String>): Int =
        context.contentResolver.query(collection, arrayOf(ID), selection, args, null)?.use { it.count } ?: 0

    /**
     * `MediaStore.Files` 是音视频/图片/文档的并集，外挂字幕只能从这里查到
     * （它不属于 Audio/Video/Image 任何一张表）。
     *
     * 卷名用字面量 `"external"` 而不是 `MediaStore.VOLUME_EXTERNAL`：后者是
     * Android 10 才有的常量，而这里要兼容 minSdk 26；两者取值相同。
     */
    private fun filesCollection(): Uri = MediaStore.Files.getContentUri(VOLUME_EXTERNAL)

    private companion object {
        const val VOLUME_EXTERNAL = "external"

        const val ID = MediaStore.MediaColumns._ID
        const val DISPLAY_NAME = MediaStore.MediaColumns.DISPLAY_NAME
        const val SIZE = MediaStore.MediaColumns.SIZE
        const val RELATIVE_PATH = MediaStore.MediaColumns.RELATIVE_PATH
        const val IS_PENDING = MediaStore.MediaColumns.IS_PENDING
    }
}
