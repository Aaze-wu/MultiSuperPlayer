package com.multisuperplayer.core.asr

import android.content.Context
import android.net.Uri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import java.io.File

/**
 * 一次云端识别要用到的全部参数。
 *
 * 独立于 [AsrService]：预设只是**默认值**，用户可以在设置页把地址、模型、密钥
 * 各自改掉（几乎每个兼容服务都要求地址写到 `/v1`，也几乎每个人都要换模型），
 * 所以真正发请求时需要的是一份「用户当前的选择」，而不是一份预设。
 */
data class CloudAsrConfig(
    /** 预设 id，只用来在失败文案里报出名字（见 [AsrException.CloudAuth]）。 */
    val serviceId: String,
    /** 用户填的（或预设给的）服务地址。 */
    val baseUrl: String,
    val model: String,
    /** 没填就是 null——**不是**空串。空串会在 [CloudAsrClient] 里被当成「不发这个头」。 */
    val apiKey: String?,
    /** 服务商收不收 `verbose_json` / `timestamp_granularities`。见 [AsrService.supportsSegments]。 */
    val supportsSegments: Boolean,
)

/** 一块待识别的音频区间。`endMs` 是**开区间**（最后一块常常不足 [CloudAsrTranscriber.CHUNK_MS]）。 */
internal data class CloudChunk(val startMs: Long, val endMs: Long)

/**
 * 把总时长切成固定长度的块。
 *
 * 抽成纯函数是为了能单测「最后一块更短」「正好整除」「比一块还短」这三种边界——
 * 切块切错了的表现是**最后几十秒的字幕凭空消失**，不会有任何报错。
 *
 * ## 两个 `require` 都是防死循环的
 *
 * 容器给出的时长是可以撒谎的（有些流式封装会报一个巨大的值）。`start + chunkMs`
 * 一旦溢出成负数，`while (start < totalMs)` 就永远不会结束——那是个**卡死**，
 * 不是个错误提示。所以上界不是「业务上不该超过」而是「再怎么也不能超」：
 * [MAX_TOTAL_MS] 取 24 小时，超过它的文件不是这条路该处理的东西
 * （这一步根本没花过网络，所以失败是安全的）。
 */
internal fun cloudChunks(
    totalMs: Long,
    chunkMs: Long = CloudAsrTranscriber.CHUNK_MS,
): List<CloudChunk> {
    require(chunkMs > 0L) { "chunk size must be positive: $chunkMs" }
    require(totalMs <= MAX_TOTAL_MS) { "媒体时长不可信（$totalMs 毫秒），拒绝切成 ${totalMs / chunkMs} 块" }
    if (totalMs <= 0L) return emptyList()

    val chunks = mutableListOf<CloudChunk>()
    var start = 0L
    while (start < totalMs) {
        // 写成「剩下不足一块就取到结尾」，而不是 `minOf(start + chunk, total)`：
        // 前者不会溢出。
        val end = if (start > totalMs - chunkMs) totalMs else start + chunkMs
        chunks += CloudChunk(startMs = start, endMs = end)
        start = end
    }
    return chunks
}

/** 切片规划的硬上界：24 小时（288 块）。见 [cloudChunks]。 */
private const val MAX_TOTAL_MS: Long = 24L * 60L * 60L * 1000L

/**
 * 云端识别：切片 → 逐块上传 → 拼成一条完整时间轴。
 *
 * ## 为什么是按固定长度切，而不是按「静音处」切
 *
 * 按静音切（VAD）能得到更自然的句子边界，代价是先要在本机把整段音频解一遍——
 * 那正是「本机识别」要做的事，于是云端这条路省不下任何东西，用户白等一遍。
 * 固定 5 分钟一块的代价是**块边界会切断一句话**：听感上是每 5 分钟多一条
 * 半截字幕。用重叠切片能补，但重叠区要去重（同一个人说的话被识别两次，
 * 时间轴还差半个字），本版不做，见 README 的已知限制。
 *
 * ## 与 [AsrTranscriber] 的关系
 *
 * 接口形状刻意保持一致（`transcribe` + `onProgress` + 同一套 [AsrSegment] /
 * [AsrProgress] / [AsrException]），这样上层的 [AsrSubtitleGenerator] 这条线
 * 不用知道音频是发出去的还是本机算的，字幕序列化也只有一份实现。
 */
class CloudAsrTranscriber internal constructor(
    private val context: Context,
    private val extractor: PcmExtractor,
    private val slicer: AudioSliceWriter,
    private val client: CloudAsrClient,
) {

    /**
     * 识别一段媒体，返回**整条时间轴**（已按块偏移平移，单位毫秒）。
     *
     * @param onProgress 每块结束后回调一次。[AsrProgress.processedMs] 是
     *   **已在音频上走到的位置**（= 这一块的结尾），不是已上传的字节。
     *   一块的「上传 + 识别」最长可能几分钟不产生回调（见 [CHUNK_MS] 的说明），
     *   上层如果需要一个不停的心跳，得自己按时间画，不能等这个回调。
     * @throws AsrException.UnknownDuration 容器没写时长，切不了块。
     * @throws AsrException.Silent 从头到尾没识别出一句人话。
     * @throws AsrException.Cloud* 云端各阶段的失败，见 [AsrException] 的对照表。
     */
    suspend fun transcribe(
        uri: Uri,
        config: CloudAsrConfig,
        onProgress: (AsrProgress) -> Unit = {},
    ): List<AsrSegment> {
        val durationMs = extractor.probe(uri).durationMs
        if (durationMs <= 0L) {
            // 有些容器（裸 ADTS AAC、部分流式封装）不写时长。这里**不能**退化成
            // 「当成一整块发出去」：一部两小时片子会先生成 230 MB 的临时 WAV。
            MspLog.w(TAG) { "云端识别需要总时长来切片，但探测结果是 $durationMs 毫秒" }
            throw AsrException.UnknownDuration
        }

        val chunks = cloudChunks(durationMs)
        val url = transcriptionsUrl(config.baseUrl)
        val scratch = prepareScratch()
        MspLog.i(TAG) {
            "云端识别开始：共 $durationMs 毫秒 → ${chunks.size} 块，服务商 ${config.serviceId}，模型 ${config.model}"
        }

        val segments = mutableListOf<AsrSegment>()
        chunks.forEachIndexed { index, chunk ->
            val slice = File(scratch, "chunk_$index.wav")
            try {
                val samples = slicer.write(uri, chunk.startMs, chunk.endMs, slice)
                if (samples <= 0L) {
                    // 容器声明的时长可能比真实音轨长。对这一块发一个空 WAV 只会
                    // 换回一个 400，还要花一次配额——所以跳过，不当作错误：
                    // 这一段的字幕本来就是空的。
                    MspLog.w(TAG) { "第 ${index + 1} 块解码出 0 个采样，跳过上传" }
                } else {
                    val body = client.transcribe(
                        request = CloudAsrRequest(
                            url = url,
                            apiKey = config.apiKey,
                            model = config.model,
                            serviceId = config.serviceId,
                            wantsTimestamps = config.supportsSegments,
                            audio = slice,
                        ),
                        onSent = { sent, total ->
                            // 只在**发完**时留一行：按 512 KB 报进度会在 9.6 MB 上
                            // 刷出 19 行日志，真正有用的那一行会被埋掉。
                            if (total > 0L && sent >= total) {
                                MspLog.i(TAG) { "第 ${index + 1} 块已发送 $sent 字节" }
                            }
                        },
                    )
                    segments += parseTranscriptionSegments(body, chunk.startMs, chunk.endMs)
                }
            } finally {
                // `finally` 里只能做**一个 syscall**（unlink）：取消时协程已经死了，
                // 任何 suspend 调用都会立刻抛出来，反而把清理和异常的语义搅乱。
                slice.delete()
            }
            onProgress(
                AsrProgress(
                    processedMs = chunk.endMs,
                    totalMs = durationMs,
                    segmentCount = segments.size,
                ),
            )
        }

        if (segments.isEmpty()) throw AsrException.Silent
        MspLog.i(TAG) { "云端识别结束：${chunks.size} 块 → ${segments.size} 条字幕" }
        return segments
    }

    /** 建（或复用）临时目录，顺手清掉上次没跑完留下的切片。 */
    private fun prepareScratch(): File {
        val dir = File(context.cacheDir, SCRATCH_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            // 建不出来不代表要立刻失败：写切片时会有一个更准确的异常（磁盘满/权限）。
            MspLog.w(TAG) { "临时目录建不出来：$dir" }
        }
        val stale = dir.listFiles()
        if (!stale.isNullOrEmpty()) {
            // 上一次进程被杀（或崩）会留下每块 9.6 MB 的 WAV，不清就是永久占着
            // cacheDir。日志留着是因为「为什么 cache 这么大」值得有个线索。
            MspLog.w(TAG) { "清掉上次留下的 ${stale.size} 个临时切片" }
            stale.forEach { it.delete() }
        }
        return dir
    }

    companion object {
        /**
         * 一块的长度：5 分钟。
         *
         * 换算成实际大小：16000 Hz × 2 字节 × 300 秒 = **9.6 MB**（单声道 16 bit）。
         * 选这个数是为了躲开 10 MB 这一档的常见请求上限——同一个端点上，
         * 一个超限的块会让**整次识别**失败，而不是「这块失败换下一块」。
         *
         * 为什么不更长/更短：更长就顶到上限；更短则每块都要重付一次模型加载的
         * 固定开销（实测约 1~2 秒），块数翻倍就是白等几秒。
         */
        const val CHUNK_MS: Long = 300_000L

        private const val SCRATCH_DIR = "asr_cloud"

        fun create(context: Context, dispatchers: DispatcherProvider): CloudAsrTranscriber =
            CloudAsrTranscriber(
                context = context,
                extractor = PcmExtractor(context, dispatchers),
                slicer = AudioSliceWriter(context, dispatchers),
                client = CloudAsrClient(dispatchers),
            )
    }
}

private const val TAG = "AsrCloud"
