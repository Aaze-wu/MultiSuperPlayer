package com.multisuperplayer.core.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind

/**
 * [MediaEntry] → Media3 [MediaItem] 的唯一转换点。
 *
 * 单点转换的理由：`MediaItem` 一旦被塞进 `ExoPlayer` 队列，后续所有操作（通知栏、
 * MediaSession、投屏）都靠它携带的元数据。转换散在各处时，最典型的症状是
 * 「通知栏里标题是 null」或「MediaSession 的 mediaId 和数据库对不上」，
 * 而这两处都很难从 UI 反查回转换代码。
 */
object MediaItemMapper {

    fun toMediaItem(entry: MediaEntry): MediaItem {
        val builder = MediaItem.Builder()
            // mediaId 必须是我们自己的稳定 id，不能用 uri：
            // 重扫后同一个文件的 uri 可能变（SAF 授权刷新），mediaId 变了会让
            // 「记住播放位置」「当前播放项高亮」全部失效。
            .setMediaId(entry.id)
            .setUri(entry.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title.takeIf { it.isNotBlank() })
                    .setArtist(entry.artist)
                    .setAlbumTitle(entry.album)
                    .setArtworkUri(entry.artworkUri?.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .apply {
                        // 明确告诉 Media3 这是可播放条目，否则 MediaSession 生成的
                        // 通知栏在某些车机上会被当成「不可播放」而显示成灰色。
                        setIsPlayable(true)
                        setIsBrowsable(false)
                    }
                    .build(),
            )

        // 后缀无法判定时把 MIME 透传给内核，让 ExoPlayer 少一次嗅探试错。
        entry.mimeType?.takeIf { it.isNotBlank() }?.let(builder::setMimeType)

        return builder.build()
    }

    /**
     * 音量/时长等「内核才知道」的字段不在这里处理——它们属于播放状态，
     * 由 [MspPlaybackState] 承载。
     */
    fun toMediaItems(entries: List<MediaEntry>): List<MediaItem> = entries.map(::toMediaItem)
}

/** 播放列表用：把一批条目拆成「音频在前、视频在后」的稳定顺序。 */
fun List<MediaEntry>.sortedForQueue(): List<MediaEntry> =
    sortedWith(compareBy({ it.kind == MediaKind.VIDEO }, { it.title }))
