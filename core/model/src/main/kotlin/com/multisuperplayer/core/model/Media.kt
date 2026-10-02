package com.multisuperplayer.core.model

/** 媒体大类。UI 用它决定进播放页还是直接后台播放。 */
enum class MediaKind {
    AUDIO,
    VIDEO,

    /** 后缀/MIME 无法判定时的兜底，播放时由内核自行探测。 */
    UNKNOWN,
    ;

    val isVideo: Boolean get() = this == VIDEO
}

/** 媒体来源，决定「重新扫描」时该不该保留这条记录。 */
enum class MediaSource {
    /** MediaStore 扫描到的本机文件。 */
    MEDIA_STORE,

    /** 用户通过 SAF 授权的文件夹（DocumentFile 树）。 */
    SAF_TREE,

    /** 网络资源（HTTP/HLS/RTSP/SMB…）。 */
    REMOTE,

    /** 其他应用分享 / "打开方式" 进来的临时条目。 */
    SHARED,

    /**
     * 用户在**内置文件浏览器**里直接打开的文件（`file://` 路径）。
     *
     * 和 [MEDIA_STORE] / [SAF_TREE] 的关键区别是「没有索引、也没被授权」：
     * 这条媒体只是这一次播放的对象，不该出现在媒体库里，也不该被
     * 「重新扫描」当成待保留的记录。它需要「所有文件访问」权限才能拿到
     * （见 `core:data` 的 `StorageAccess`），因为路径访问在分区存储下
     * 只有这一条合法途径。
     */
    FILE_SYSTEM,
}

/**
 * 播放器领域的媒体条目。
 *
 * 刻意与 `androidx.media3.common.MediaItem`、`android.provider.MediaStore.MediaColumns`
 * 解耦：扫描器负责把系统数据映射成它，播放器负责把它映射成 Media3 的 MediaItem。
 */
data class MediaEntry(
    /** 稳定标识：本机文件用 `MediaStore ID`，SAF 用 document uri，网络用规范化 URL。 */
    val id: String,
    /** 可直接交给 Media3 的 uri 字符串。 */
    val uri: String,
    val title: String,
    val kind: MediaKind,
    val source: MediaSource = MediaSource.MEDIA_STORE,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String? = null,
    val displayName: String? = null,
    /** 相对路径，用于「按文件夹分组」。 */
    val relativePath: String? = null,
    val dateAddedSeconds: Long = 0L,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    /** 封面图 uri（MediaStore 的 albumArt / 内嵌封面提取结果）。 */
    val artworkUri: String? = null,
) {
    val hasArtwork: Boolean get() = !artworkUri.isNullOrBlank()

    /** 列表里显示用的副标题：`艺术家 · 专辑`，缺项时优雅降级。 */
    val subtitle: String
        get() = listOfNotNull(
            artist?.takeIf { it.isNotBlank() },
            album?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
}
