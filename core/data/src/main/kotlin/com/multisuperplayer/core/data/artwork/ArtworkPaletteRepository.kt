package com.multisuperplayer.core.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.core.net.toUri
import androidx.palette.graphics.Palette
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.withContext

/**
 * 从专辑封面里取一个「种子色」，再交给 [ArtworkColorRules] 推导整套强调色。
 *
 * ## 为什么不做缓存
 *
 * 调用频率是「换一首歌一次」，而不是每帧一次。一次完整提取在缩到 48px
 * 之后是毫秒级，而 `LruCache` **不允许存 null 值**——「算过了，这张封面
 * 没有可用的颜色」这个结果没法直接缓存，只能塞一个哨兵对象或者再维护一个
 * 否定集合，两种做法都会让这段代码长出比它解决的问题更多的东西。
 * 调用方（PlayerViewModel）用 `distinctUntilChanged` 按 uri 去重，效果一样
 * 而且没有第二份状态。
 */
class ArtworkPaletteRepository(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    /**
     * @param artworkUri 媒体条目的封面 uri；为 null / 空表示没有封面。
     * @return 推导出的四色；没有封面、读不出来、或封面本身没有色相时返回 null。
     *         调用方应当把它当作「回退到用户自己选的强调色」的信号，而不是错误。
     */
    suspend fun colorsFor(artworkUri: String?): ArtworkColors? {
        val raw = artworkUri?.takeIf { it.isNotBlank() } ?: return null
        return withContext(dispatchers.io) {
            val seed = runCatching { seedColorOf(raw.toUri()) }
                .onFailure { MspLog.d(TAG) { "封面取色失败（$raw）：${it.message}" } }
                .getOrNull()
                ?: return@withContext null
            ArtworkColorRules.colorsFor(seed)
        }
    }

    /** @return 封面里最有代表性的颜色；取不到则返回 null。 */
    private fun seedColorOf(uri: Uri): Int? {
        val bitmap = loadBitmap(uri) ?: return null
        val palette = try {
            Palette.from(bitmap)
                // 默认过滤条件会把「接近黑 / 接近白 / 中低明度的红橙」这一大票
                // 颜色排除掉，而排除的判据和这里自己的判据不一致。先清空，
                // 统一由 ArtworkColorRules 负责筛选，规则只有一处。
                .clearFilters()
                // 不需要在这里缩小位图：Palette 1.0.0 自己会把位图缩到
                // 112x112 再量化，而 `resizeBitmapSize` / `resizeBitmapMaxSize`
                // 这两个公开方法从 1.0.0 起就是空实现，只是在等 API 清理。
                .generate()
        } finally {
            // 不再需要像素，早点交还给 GC（列表滚动时封面可能连续换很多张）。
            bitmap.recycle()
        }

        // 顺序参考系统自己的媒体颜色化：先要有彩度的，再退到大面积代表色。
        val swatch = palette.vibrantSwatch
            ?: palette.darkVibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch
            ?: return null

        return swatch.rgb
    }

    /**
     * API 29 起可以走 `loadThumbnail`（系统会自己找最佳尺寸）；
     * 更低版本只能从文件里读内嵌封面。
     */
    private fun loadBitmap(uri: Uri): Bitmap? {
        val resolver = appContext.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                val embedded = retriever.embeddedPicture ?: return null
                BitmapFactory.decodeByteArray(embedded, 0, embedded.size)
            } finally {
                // MediaMetadataRetriever 到 API 29 才实现 AutoCloseable，
                // 在 minSdk 26 上用 `use {}` 编不过，只能显式 release。
                runCatching { retriever.release() }
            }
        }
    }

    private companion object {
        const val TAG = "ArtworkPalette"

        /** 送给 MediaMetadataRetriever 的老版本路径用；Palette 还会再缩一次。 */
        const val THUMBNAIL_SIZE = 96
    }
}
