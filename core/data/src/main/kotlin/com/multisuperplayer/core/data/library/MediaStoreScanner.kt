package com.multisuperplayer.core.data.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource

private const val TAG = "MediaStoreScanner"

/**
 * MediaStore 扫描器。
 *
 * ## 为什么按「音频」「视频」两套查询，而不是一次查完
 *
 * Android 13 起音频和视频是**两个独立权限**（`READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO`）。
 * 一次失败就整体失败，会让「只允许音乐、拒绝视频」的用户连歌都看不到。
 * 所以这里按集合分别扫描，各自独立成败，由 [hasAudioPermission] / [hasVideoPermission] 决定。
 *
 * ## 关于「扫到什么」
 *
 * `content://` 查询天然是同步阻塞的，调用方必须在 IO 线程执行——这不是可选项，
 * 主线程查询会被 StrictMode 记一笔，并且在媒体库很大时直接卡住启动。
 */
class MediaStoreScanner(private val context: Context) {

    // ------------------------------------------------------------------- 权限

    @Suppress("DEPRECATION")
    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Android 13 之前只有一个粗粒度的存储权限。 */
    private fun legacyStorageGranted(): Boolean =
        isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasAudioPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isGranted(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            legacyStorageGranted()
        }

    fun hasVideoPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 14 起，「仅选择部分照片和视频」会授予这个权限而不是 READ_MEDIA_VIDEO。
            // 拿到它意味着我们只能看到用户勾选的那些——依然算「有权限」，但要标记为 partial。
            isGranted(Manifest.permission.READ_MEDIA_VIDEO) ||
                isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            legacyStorageGranted()
        }

    /** 部分访问（只看得到用户选中的视频）。 */
    private fun hasPartialVisualAccess(): Boolean = MediaStoreScanRules.isPartialVisualAccess(
        sdkInt = Build.VERSION.SDK_INT,
        hasFullVideoPermission = isGranted(Manifest.permission.READ_MEDIA_VIDEO),
        hasUserSelectedVisualPermission =
            isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
    )

    fun hasAnyPermission(): Boolean = hasAudioPermission() || hasVideoPermission()

    /**
     * 需要向用户申请的权限列表。按当前系统版本给出，避免申请一个不存在的权限
     * （那会让系统的权限对话框直接不弹，表现为「点了按钮没反应」）。
     *
     * 坑：单测里 `Build.VERSION.SDK_INT` 恒为 0，所以判断本身在
     * [MediaStoreScanRules.requiredPermissions] 里、以参数形式接收版本号。
     */
    fun requiredPermissions(): Array<String> =
        MediaStoreScanRules.requiredPermissions(Build.VERSION.SDK_INT)

    // ------------------------------------------------------------------- 扫描

    /** 扫描结果与「是否只拿到部分库」的标记。 */
    data class ScanOutcome(val entries: List<MediaEntry>, val partial: Boolean)

    /**
     * 扫描所有可访问的媒体。**不做权限检查**：调用方（仓库）负责先判断，
     * 这样「没权限」就不会混进「扫到 0 条」里。
     */
    fun scanAll(): ScanOutcome {
        val audio = if (hasAudioPermission()) scanAudio() else emptyList()
        val video = if (hasVideoPermission()) scanVideo() else emptyList()
        val partial = MediaStoreScanRules.isPartialScan(
            partialVisualAccess = hasPartialVisualAccess(),
            hasAudioPermission = hasAudioPermission(),
            videoCount = video.size,
        )
        MspLog.i(TAG) {
            "扫描完成：音频 ${audio.size} 条，视频 ${video.size} 条" +
                if (partial) "（部分权限）" else ""
        }
        return ScanOutcome(entries = audio + video, partial = partial)
    }

    private fun scanAudio(): List<MediaEntry> = query(
        collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        kind = MediaKind.AUDIO,
        idPrefix = "audio",
        columns = buildList {
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.TRACK)
            // ALBUM_ARTIST 是 API 30 才加入的列名，低版本查询会抛
            // IllegalArgumentException（而不是返回 null）。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
            }
        },
    )

    private fun scanVideo(): List<MediaEntry> = query(
        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        kind = MediaKind.VIDEO,
        idPrefix = "video",
        columns = emptyList(),
    )

    private fun query(
        collection: Uri,
        kind: MediaKind,
        idPrefix: String,
        columns: List<String>,
    ): List<MediaEntry> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.TITLE)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 时长与相对路径都是 29 才进 MediaColumns 的。
                add(MediaStore.MediaColumns.DURATION)
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            addAll(columns)
        }.toTypedArray()

        // IS_PENDING = 0：过滤掉「正在写入」的文件。
        // 不过滤的话，正在下载/拷贝到一半的媒体会出现在库里，点开必然播放失败，
        // 而且用户会以为是播放器的问题。
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.IS_PENDING} = 0"
        } else {
            null
        }

        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val entries = ArrayList<MediaEntry>()
        val cursor = context.contentResolver.query(collection, projection, selection, null, sortOrder)
            ?: return entries

        cursor.use {
            val idIndex = it.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val titleIndex = it.getColumnIndex(MediaStore.MediaColumns.TITLE)
            val mimeIndex = it.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIndex = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIndex = it.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
            val durationIndex = it.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val pathIndex = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val artistIndex = if (columns.contains(MediaStore.Audio.Media.ARTIST)) {
                it.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            } else {
                -1
            }
            val albumIndex = if (columns.contains(MediaStore.Audio.Media.ALBUM)) {
                it.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            } else {
                -1
            }
            val trackIndex = if (columns.contains(MediaStore.Audio.Media.TRACK)) {
                it.getColumnIndex(MediaStore.Audio.Media.TRACK)
            } else {
                -1
            }
            val albumArtistIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                columns.contains(MediaStore.Audio.Media.ALBUM_ARTIST)
            ) {
                it.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            } else {
                -1
            }

            while (it.moveToNext()) {
                if (idIndex < 0) break
                val id = it.getLong(idIndex)
                val displayName = nameIndex.takeIf { i -> i >= 0 }?.let { i -> it.getString(i) }
                val title = titleIndex.takeIf { i -> i >= 0 }?.let { i -> it.getString(i) }
                val uri = ContentUris.withAppendedId(collection, id)

                entries += MediaEntry(
                    // 前缀不能省：媒体库表 id 在音频/视频之间理论上不冲突，
                    // 但拼上类型后 resume/收藏这类持久化的 key 就与「来源」解耦了。
                    id = "$idPrefix:$id",
                    // 模型里的 uri 是 String 而不是 android.net.Uri：
                    // core:model 刻意保持零 Android 依赖，因而能在纯 JVM 单元测试里使用。
                    uri = uri.toString(),
                    title = MediaStoreScanRules.deriveTitle(title, displayName),
                    kind = kind,
                    source = MediaSource.MEDIA_STORE,
                    // 过一遍 normalizeTag：MediaStore 在缺元数据时会返回字面量 "<unknown>"
                    // （不是 null），直接用会把系统哨兵值当艺术家名显示给用户。
                    artist = MediaStoreScanRules.normalizeTag(artistIndex.readString(it)),
                    album = MediaStoreScanRules.normalizeTag(albumIndex.readString(it)),
                    albumArtist = MediaStoreScanRules.normalizeTag(albumArtistIndex.readString(it)),
                    durationMs = durationIndex.readLong(it),
                    sizeBytes = sizeIndex.readLong(it),
                    mimeType = mimeIndex.readString(it),
                    displayName = displayName,
                    relativePath = pathIndex.readString(it),
                    dateAddedSeconds = dateIndex.readLong(it),
                    trackNumber = trackIndex.readIntOrNull(it),
                    // artworkUri 直接给媒体自身的内容 uri：
                    // API 29+ 用 ContentResolver.loadThumbnail() 可以从音频里取出内嵌封面、
                    // 从视频里取出一帧。老代码里那个 content://media/external/audio/albumart/<albumId>
                    // 的取法在 Android 10 起已经不可靠，不要再用。
                    artworkUri = uri.toString(),
                )
            }
        }
        return entries
    }

    private fun Int.readString(cursor: android.database.Cursor): String? =
        if (this < 0) null else cursor.getString(this)?.takeIf { it.isNotBlank() }

    private fun Int.readLong(cursor: android.database.Cursor): Long =
        if (this < 0) 0L else cursor.getLong(this)

    private fun Int.readIntOrNull(cursor: android.database.Cursor): Int? =
        if (this < 0 || cursor.isNull(this)) null else cursor.getInt(this)
}
