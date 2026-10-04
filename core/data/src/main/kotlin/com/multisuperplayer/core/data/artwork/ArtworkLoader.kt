package com.multisuperplayer.core.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Size
import androidx.core.net.toUri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.ArtworkRequest
import com.multisuperplayer.core.model.MediaKind
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 把 [ArtworkRequest] 变成磁盘上的一张 JPEG。
 *
 * ## 两条取图路径
 *
 * - **音频**：内嵌封面。API 29+ 走 `ContentResolver.loadThumbnail()`
 *   （MediaProvider 会从音频文件里把封面掏出来），更低版本走
 *   `MediaMetadataRetriever.embeddedPicture`。
 *   音频不抽帧——从音频的波形里抽一帧没有任何意义。
 * - **视频**：先看有没有内嵌封面（把图片塞进 mp4 的 `covr` atom 是存在的，
 *   而且那张图比任意一帧更可能是作者想放的那张），没有就
 *   `getFrameAtTime` 抽帧，时间点由 [ArtworkRules.frameTimeCandidatesMs] 决定，
 *   抽到近黑帧就换下一个候选点重抽。
 *
 * ## 为什么不复用 `ArtworkPaletteRepository`
 *
 * 那个类也走「loadThumbnail / embeddedPicture」两条路，看起来该合并。
 * 但它要的是 **96×96 的取色种子**，这里要的是 **512 长边 + 抽帧 + 亮度重试
 * + 磁盘缓存**，参数化之后两条路径的分歧比共性多，而它已经被现成测试盖住了。
 * 合并的收益是省二十行，代价是动一处不相关的稳定代码。
 *
 * ## 为什么要自己的磁盘缓存
 *
 * 抽帧是这里最贵的操作（一次 seek + 解码）。没有磁盘缓存的话，
 * **每次冷启动**、每个用过这张封面但被内存缓存淘汰的页面都要重抽一遍，
 * 而列表滚动正是最容易把内存缓存挤爆的场景。
 * 缓存文件里已经是我们想要的尺寸（512 长边 JPEG，几十 KB），
 * 冷读取只是打开一个文件。
 *
 * 所以 Coil 那边的磁盘缓存对封面是**关掉的**（见 `MspApplication`）：
 * 同一份字节缓存两次只会多占一倍空间，而这里自己的缓存还多一条
 * 「已经缩好尺寸」的好处。
 */
class ArtworkLoader(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    private val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)

    /**
     * 同时最多抽两帧。
     *
     * 列表滚动时可能有十几行同时进入取图，每一行都是一次「seek + 解码」，
     * 不限流的话会瞬间吃掉几百 MB 内存（一张 4K 帧就是 33MB）。
     * 限成 2 之后多出来的请求排队，用户看到的是「封面一张张出来」，
     * 而不是「列表滑动卡顿」——排队的那些行本来也还没进入可视区域。
     *
     * 用 `Semaphore` 而不是一个队列：借出/归还是协程原生的，
     * 协程被取消时会自动归还，不会有「异常路径忘了释放、从此再也借不到」的坑。
     */
    private val permits = Semaphore(MAX_CONCURRENT)

    /**
     * 取封面，返回磁盘上的 JPEG 文件；取不到时返回 `null`。
     *
     * 返回 `null` 是**正常结果**（没有内嵌封面的音频、损坏的文件、
     * 网络来源），调用方据此回退到类型图标，不是错误分支。
     *
     * 取消是安全的：磁盘写入是「临时文件 + 改名」，见 [writeAtomically]。
     */
    suspend fun load(request: ArtworkRequest): File? = withContext(dispatchers.io) {
        val cacheFile = File(cacheDir, "${ArtworkRules.cacheKeyOf(request.uri, request.kind)}.jpg")
        if (cacheFile.isUsable()) return@withContext cacheFile

        permits.withPermit {
            // 双重检查：等锁的这段时间里，同一行的另一次请求（或者另一个页面）
            // 可能已经把文件写好了。少了这一句，同一张封面会被并发抽两遍。
            if (cacheFile.isUsable()) return@withPermit cacheFile

            val bitmap = render(request) ?: return@withPermit null
            try {
                writeAtomically(bitmap, cacheFile)
            } finally {
                // 位图交出去之后就没用了。不回收的话，一次滚动能留下几十张
                // 512×512（各 1MB）的位图等 GC，而 GC 不是立刻的。
                bitmap.recycle()
            }
        }
    }

    /** 已经从磁盘缓存里拿到可用的图。判据在 [ArtworkRules.cacheFileIsUsable]。 */
    private fun File.isUsable(): Boolean = ArtworkRules.cacheFileIsUsable(this)

    // ---- 取图 ----------------------------------------------------------------

    /**
     * 抽/掏出一张位图。失败一律返回 `null`。
     *
     * `runCatching` 在这里捕获的是 `Throwable`，**包括 `OutOfMemoryError`**
     * ——这是有意的：一次抽帧要分配一张全尺寸位图，4K 视频就是 33MB，
     * 在低端机上是真会 OOM 的。OOM 的正确反应是「这一行没有封面」，
     * 不是让整个应用崩掉。位图大小本身也已经用采样压过（见 [decodeBitmap]
     * 与 [frameAt]），这一层是兜底。
     */
    private fun render(request: ArtworkRequest): Bitmap? = runCatching {
        when (request.kind) {
            MediaKind.VIDEO -> videoArtwork(request.uri)
            else -> audioArtwork(request.uri)
        }
    }.getOrElse { error ->
        // 记一行日志再吞掉。失败路径如果不留痕迹，「封面功能好像没生效」
        // 就会变成一个连日志都查不出的问题。
        MspLog.d(TAG) { "封面取图失败：${request.kind} ${request.uri}" }
        MspLog.d(TAG) { "原因：${error::class.java.simpleName}: ${error.message}" }
        null
    }

    private fun audioArtwork(uri: String): Bitmap? {
        // API 29 起 MediaProvider 能直接从音频里取内嵌封面。它比
        // embeddedPicture 好的一点是：不要求我们先把整个文件读进内存。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.startsWith(CONTENT_SCHEME)) {
            runCatching {
                appContext.contentResolver.loadThumbnail(
                    uri.toUri(),
                    Size(ArtworkRules.MAX_EDGE_PX, ArtworkRules.MAX_EDGE_PX),
                    null,
                )
            }.getOrNull()?.let { return scaled(it) }
        }
        return withRetriever(uri) { retriever ->
            val picture = retriever.embeddedPicture
            // 不能用 `isNullOrEmpty()`：ByteArray 没有这个扩展（那是 CharSequence/
            // Collection/Array 才有的），只有非空类型上的 `isEmpty()`。
            if (picture == null || picture.size == 0) return@withRetriever null
            decodeBitmap(picture)?.let { scaled(it) }
        }
    }

    private fun videoArtwork(uri: String): Bitmap? = withRetriever(uri) { retriever ->
        val picture = retriever.embeddedPicture
        if (picture != null && picture.size > 0) {
            decodeBitmap(picture)?.let { return@withRetriever scaled(it) }
        }
        pickFrame(retriever)?.let { scaled(it) }
    }

    /**
     * 按候选时间点依次抽帧，返回第一个「不暗」的；全都暗就返回最后一个。
     *
     * 全都暗时**仍然返回那张暗图**：一张黑封面至少还是张封面，
     * 而不画等于回退到类型图标——用户会以为这个功能没生效。
     * 片头黑场之后总有内容，用户至少能从「这是哪一片」上认出它。
     */
    private fun pickFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L

        var darkestFallback: Bitmap? = null
        for (atMs in ArtworkRules.frameTimeCandidatesMs(durationMs)) {
            val frame = frameAt(retriever, atMs) ?: continue
            if (!isTooDark(frame)) {
                // 找到了能用的：把之前留作兜底的那张放掉，别留着占内存。
                darkestFallback?.recycle()
                return frame
            }
            darkestFallback?.recycle()
            darkestFallback = frame
        }
        return darkestFallback
    }

    private fun frameAt(retriever: MediaMetadataRetriever, atMs: Long): Bitmap? {
        val atUs = atMs * 1000L
        // API 27 起可以让系统直接把帧缩到我们要的尺寸。4K 视频的一整帧是 33MB，
        // 先拿全尺寸再自己缩的话，这 33MB 是要真真切切分配出来的。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val target = frameTarget(retriever)
            if (target != null) {
                runCatching {
                    retriever.getScaledFrameAtTime(
                        atUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        target[0],
                        target[1],
                    )
                }.getOrNull()?.let { return it }
            }
        }
        // 低版本 / 拿不到视频尺寸 / 缩放抽帧失败：退回全尺寸那一版。
        return runCatching {
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull()
    }

    /** 按视频自己的宽高算出「长边不超过 512」的目标尺寸。 */
    private fun frameTarget(retriever: MediaMetadataRetriever): IntArray? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: return null
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val target = ArtworkRules.fitWithin(width, height)
        if (target[0] <= 0 || target[1] <= 0) return null
        return target
    }

    /**
     * 把裸字节解成位图，先按 [ArtworkRules.sampleSize] 采样。
     *
     * `inJustDecodeBounds` 那一趟只读文件头、不分配像素，是唯一能在
     * 「已经解码完才发现图太大」之前知道尺寸的办法。
     */
    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ArtworkRules.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** 等比缩到长边 512；已经够小就原样返回（不白拷一份，也不回收它）。 */
    private fun scaled(source: Bitmap): Bitmap {
        val target = ArtworkRules.fitWithin(source.width, source.height)
        if (target[0] <= 0 || target[1] <= 0) return source
        if (target[0] == source.width && target[1] == source.height) return source
        val scaled = Bitmap.createScaledBitmap(source, target[0], target[1], true)
        // createScaledBitmap 在同一尺寸时会返回**同一个对象**，
        // 那时回收它等于回收返回值本身。
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun isTooDark(bitmap: Bitmap): Boolean = runCatching {
        val sample = Bitmap.createScaledBitmap(
            bitmap,
            ArtworkRules.LUMINANCE_SAMPLE,
            ArtworkRules.LUMINANCE_SAMPLE,
            false,
        )
        val pixels = IntArray(ArtworkRules.LUMINANCE_SAMPLE * ArtworkRules.LUMINANCE_SAMPLE)
        sample.getPixels(
            pixels,
            0,
            ArtworkRules.LUMINANCE_SAMPLE,
            0,
            0,
            ArtworkRules.LUMINANCE_SAMPLE,
            ArtworkRules.LUMINANCE_SAMPLE,
        )
        if (sample !== bitmap) sample.recycle()
        ArtworkRules.isTooDark(pixels)
    }.getOrDefault(false)
    // 采样失败时故意**不**判定为「暗」：那会白抽一帧，而多抽的那一帧
    // 有可能比手上这张更差。判定成「不暗」至少能保证用的是第一候选点的画面。

    /**
     * 用一次 `MediaMetadataRetriever` 的会话。
     *
     * `release()` 放在 `finally` 且吞掉异常：它在部分机型上会抛
     * `IllegalStateException`（已经自动释放过），而那不该盖掉真正的错误。
     *
     * 不用 `use {}`：`MediaMetadataRetriever` 到 API 29 才实现 `AutoCloseable`，
     * 而这个项目的 minSdk 是 26。
     */
    private inline fun <T> withRetriever(uri: String, block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.startsWith(CONTENT_SCHEME)) {
                retriever.setDataSource(appContext, uri.toUri())
            } else {
                // 文件系统绝对路径（浏览页给的 ref 就是这种）。用 String 重载，
                // 不需要先拼一个 `file://` 出来——拼错了反而多一个失败点。
                retriever.setDataSource(uri)
            }
            block(retriever)
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---- 落盘 ----------------------------------------------------------------

    /**
     * 先写 `.tmp` 再改名。
     *
     * 少了这一步，一个被取消（用户滑走 / 切页面）或中途失败的写入会留下
     * **写了一半的 jpg**。而「文件存在且长度大于 0」这个检查挡不住它，
     * 于是这次失败会被永久固化：以后每次读到这张坏图都解码失败一次，
     * 抽帧永远重试、永远失败。
     *
     * 同目录内的 `renameTo` 在 Android 上是原子的，所以要么是完整的图，
     * 要么什么都没有。改名的判据在 [ArtworkRules.promoteAtomically]。
     */
    private fun writeAtomically(bitmap: Bitmap, target: File): File? {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return null
        val temp = File(cacheDir, "${target.name}$TEMP_SUFFIX")
        val written = runCatching {
            FileOutputStream(temp).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, ArtworkRules.JPEG_QUALITY, out)
            }
        }.getOrDefault(false)
        if (!written) {
            // `compress` 抛了，临时文件里可能是半张图（也可能根本没建出来）。
            // 它也过不了 promoteAtomically 的那道闸，这里先删掉是为了不依赖它。
            temp.delete()
            return null
        }
        return if (ArtworkRules.promoteAtomically(temp, target)) target else null
    }

    private companion object {
        const val TAG = "ArtworkLoader"
        const val CACHE_DIR_NAME = "artwork"
        const val TEMP_SUFFIX = ".tmp"
        const val CONTENT_SCHEME = "content://"
        const val MAX_CONCURRENT = 2
    }
}
