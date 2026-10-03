package com.multisuperplayer.core.asr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 音轨的基本信息。
 *
 * [durationMs] 为 0 表示**容器里没写时长**（部分 MKV/TS 就没有）。这时候进度条
 * 只能是「不确定」形态，而不是 0%——按 0% 算的话进度会先冲到很高再卡住，
 * 看着像卡死。
 */
data class AsrAudioProbe(
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val mime: String,
)

/**
 * 把媒体的音轨解成识别器要的格式：**16 kHz、单声道、浮点**。
 *
 * ## 为什么自己写而不引第三方
 *
 * 项目里没有 FFmpeg（见 README 的许可证说明），`MediaExtractor` + `MediaCodec` 是
 * 平台自带的解码路径，能解的格式就是这台设备能播的格式——恰好和「这个文件能不能播」
 * 完全一致。多引一个解码库反而会出现「能播但识别不了」的不一致。
 *
 * ## 为什么必须重采样和降混
 *
 * 模型是按 16 kHz 单声道训练的。44.1 kHz 直接喂进去不报错，只是识别出一串没有意义的
 * 字；只取左声道则在「旁白混在右声道」的片子上识别出空白。
 */
class PcmExtractor(private val context: Context, private val dispatchers: DispatcherProvider) {

    /**
     * 只读元信息，不解码。
     *
     * 拆成单独一步是为了进度：总时长要在开始解码**之前**拿到，才能显示百分比。
     */
    suspend fun probe(uri: Uri): AsrAudioProbe = withContext(dispatchers.io) { probeBlocking(uri) }

    /**
     * 解出整条音轨，逐块交给 [onChunk]；返回一共吐了多少个 16 kHz 采样点。
     *
     * 返回的采样点数是 [AsrSegmentBuilder.build] 需要的「音频总长」——VAD 报出的
     * 采样点必须夹在这个范围内，否则末尾的字幕会落到片子外面去。
     *
     * [onChunk] 在 IO 线程上同步调用，块长是解码器给多少算多少（几百到几千点）。
     */
    suspend fun extract(uri: Uri, onChunk: (FloatArray) -> Unit): Long =
        withContext(dispatchers.io) {
            // 解码循环是阻塞的，协程取消没法穿透进去，所以把 Job 带进去手动查：
            // 一部长片解码要几十秒，按了取消却什么都不发生，看起来就是「卡死」。
            extractBlocking(uri, onChunk, currentCoroutineContext()[Job])
        }

    private fun probeBlocking(uri: Uri): AsrAudioProbe {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, uri)
            val format = extractor.getTrackFormat(selectAudioTrack(extractor))
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            return AsrAudioProbe(
                durationMs = if (durationUs > 0L) durationUs / 1000L else 0L,
                sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
                channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1,
                mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
            )
        } catch (e: IOException) {
            throw AsrException.Decode(e)
        } finally {
            extractor.release()
        }
    }

    private fun extractBlocking(uri: Uri, onChunk: (FloatArray) -> Unit, job: Job?): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            setDataSource(extractor, uri)
            val trackIndex = selectAudioTrack(extractor)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.isEmpty()) {
                // 没有 mime 就没法选解码器。这种文件在播放器里也放不出来，
                // 但报「解码失败」比报「识别失败」更接近事实。
                throw AsrException.Decode(IOException("音轨没有标明格式"))
            }
            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: IOException) {
                // 设备上没有这个格式的解码器（比如某些老机器没有 FLAC/Opus）。
                // 这里抛出去的 message 在异常里，会显示成「音频解码失败：Failed to ...」。
                throw AsrException.Decode(e)
            }
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val resampler = StreamingLinearResampler(
                fromRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: ASR_SAMPLE_RATE,
                toRate = ASR_SAMPLE_RATE,
            )
            // 声道数和样本格式都按**解码器输出**为准：容器里写的声道数和解出来的帧
            // 可能不一致（5.1 的片子被设备解成 2 声道很常见），按字节布局算，
            // 只有输出格式是对的。这里的值只是「还没收到输出格式时的兜底」。
            var outputIsFloat = false
            var outputChannels = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var outputFormatRead = false

            val bufferInfo = MediaCodec.BufferInfo()
            var totalSamples = 0L
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                // 一部长片解码要几十秒，取消必须能中途生效。
                if (job?.isActive == false) throw CancellationException("字幕生成已取消")

                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val size = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputIsFloat = codec.outputFormat.isFloatPcm()
                        outputChannels = codec.outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                        outputFormatRead = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        if (!outputFormatRead) {
                            // 理论上格式变化通知一定先到，但真机上不能赌这个顺序：
                            // 第一次拿到输出缓冲时补读一次，避免用错字节布局。
                            outputIsFloat = codec.outputFormat.isFloatPcm()
                            outputChannels = codec.outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                            outputFormatRead = true
                        }
                        if (bufferInfo.size > 0) {
                            val raw = codec.getOutputBuffer(outputIndex)?.let { buffer ->
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                ByteArray(bufferInfo.size).also { buffer.get(it) }
                            }
                            if (raw != null) {
                                val mono = downmixToMonoFloat(raw, raw.size, outputChannels, outputIsFloat)
                                val resampled = resampler.process(mono)
                                if (resampled.isNotEmpty()) {
                                    onChunk(resampled)
                                    totalSamples += resampled.size
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            MspLog.d(TAG) {
                val format = if (outputIsFloat) "浮点" else "16 bit"
                "解码完成：$totalSamples 个 16 kHz 采样点" +
                    "（约 ${totalSamples / ASR_SAMPLE_RATE} 秒，输出 $outputChannels 声道 $format）"
            }
            return totalSamples
        } catch (e: AsrException) {
            throw e
        } catch (e: CancellationException) {
            // 取消不是「解码失败」，必须原样抛出去，否则协程没法正常结束。
            throw e
        } catch (e: IOException) {
            throw AsrException.Decode(e)
        } catch (e: MediaCodec.CodecException) {
            throw AsrException.Decode(e)
        } finally {
            codec?.let { c ->
                runCatching { c.stop() }.onFailure { MspLog.w(TAG, it) { "停止解码器失败" } }
                runCatching { c.release() }.onFailure { MspLog.w(TAG, it) { "释放解码器失败" } }
            }
            extractor.release()
        }
    }

    /** [MediaExtractor.setDataSource] 的三个重载里，只有这个能同时吃 `file://` 和 `content://`。 */
    private fun setDataSource(extractor: MediaExtractor, uri: Uri) {
        extractor.setDataSource(context, uri, null)
    }

    /** 挑第一条音频轨并选中它；一条都没有就抛 [AsrException.NoAudioTrack]。 */
    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                extractor.selectTrack(index)
                return index
            }
        }
        throw AsrException.NoAudioTrack
    }
}

private const val TAG = "AsrPcm"

/**
 * `dequeue*Buffer` 的超时。10 ms 足够让解码器把活干完，又不会让取消等太久。
 *
 * `internal` 而不是 `private`：[AudioSliceWriter] 也跑同一个解码循环，
 * 两处必须用**同一个**超时——各写一个数字的结果是一处调了、另一处没调，
 * 而症状只是「切片比整段慢一点」，没人看得出来。
 */
internal const val DEQUEUE_TIMEOUT_US = 10_000L

internal fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

/**
 * 输出是不是浮点 PCM。
 *
 * 同一个 4 字节可能是 16 bit 立体声的一帧，也可能是浮点单声道的一个样本，
 * 猜错的结果不是「声音小一点」而是彻底噪声。**没有** `pcm-encoding` 字段时
 * 按 16 bit 处理（绝大多数解码器如此）。
 *
 * `internal` 而同上面的理由：[AudioSliceWriter] 必须用**同一套**判据，
 * 否则同一台手机上「整段识别」和「云端切片」会对同一个音轨得到不同的字节布局。
 */
internal fun MediaFormat.isFloatPcm(): Boolean =
    intOrNull(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
