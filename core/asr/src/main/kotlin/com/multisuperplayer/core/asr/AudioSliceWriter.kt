package com.multisuperplayer.core.asr

import android.content.Context
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
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/**
 * WAV（RIFF）容器头。**纯函数**，所以能脱离 Android 单测。
 *
 * ## 为什么自己拼 WAV 而不用编码器
 *
 * 两条路都不依赖第三方库，但只有这条不依赖**设备**：
 * - `MediaCodec` 编 AAC 需要设备有 AAC 编码器（绝大多数有，但这是运行时事实，
 *   不是我能保证的事实），而且编出来的文件还要额外解释给服务商；
 * - 手写 44 字节头是纯字节拼接，没有编码器、没有采样率限制、没有设备差异。
 *
 * 代价是体积：5 分钟 16 kHz 单声道 16 bit ≈ 9.6 MB。有损编码能把这一块压到
 * 1/10，但那是「省流量」换「多一个设备相关失败点」，而 9.6 MB 在多数服务商的
 * 单次上限之内。
 *
 * ## 为什么必须是 16 bit 而不是浮点
 *
 * 浮点那份是同一段音频的 19.2 MB：**体积翻倍、识别效果没有区别**。
 * 模型侧本来就是 16 bit 的世界，多出来的精度在传输里被丢掉。
 */
internal object WavHeader {

    /** 头部长度。数据区**必须**从第 44 字节开始，没有可选项。 */
    const val SIZE: Int = 44

    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1

    /**
     * 生成 44 字节头。
     *
     * 所有整数都是**小端**：WAV 是 RIFF 家族，网络字节序那一套不适用，
     * 写成大端的结果是播放器把 16 kHz 读成 4 MHz 之类的乱值。
     */
    fun pcm16(dataBytes: Long, sampleRate: Int): ByteArray {
        require(sampleRate > 0) { "sample rate must be positive: $sampleRate" }
        require(dataBytes >= 0) { "data size must not be negative: $dataBytes" }
        val channels = CHANNELS
        val bits = BITS_PER_SAMPLE
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        // RIFF 的块大小 = 整个文件 - 前 8 字节（"RIFF" + 这个字段本身）。
        val riffSize = 36L + dataBytes

        val header = ByteArray(SIZE)
        putAscii(header, 0, "RIFF")
        putIntLe(header, 4, riffSize)
        putAscii(header, 8, "WAVE")
        putAscii(header, 12, "fmt ")
        putIntLe(header, 16, 16L) // fmt 块长度，PCM 固定 16
        putShortLe(header, 20, 1) // 1 = 未压缩 PCM
        putShortLe(header, 22, channels)
        putIntLe(header, 24, sampleRate.toLong())
        putIntLe(header, 28, byteRate.toLong())
        putShortLe(header, 32, blockAlign)
        putShortLe(header, 34, bits)
        putAscii(header, 36, "data")
        putIntLe(header, 40, dataBytes)
        return header
    }

    private fun putAscii(target: ByteArray, offset: Int, value: String) {
        for (index in value.indices) target[offset + index] = value[index].code.toByte()
    }

    private fun putIntLe(target: ByteArray, offset: Int, value: Long) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun putShortLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }
}

/**
 * 把媒体里 `[startMs, endMs)` 这一段音频导出成 16 kHz 单声道 16 bit 的 WAV 文件，
 * 供上传给云端识别使用。
 *
 * ## 为什么不能用 [PcmExtractor]
 *
 * [PcmExtractor] 是**整条**音轨、**流式**交给识别引擎的：它的消费者是本地模型，
 * 数据不用落地；而且它不认时间区间——上传需要的恰恰是「只要这一段」和「落地成一个文件」。
 *
 * ## 解码循环与 [PcmExtractor] 共用同一套判据
 *
 * 字节布局（[isFloatPcm] / [intOrNull] / [downmixToMonoFloat]）和重采样
 * （[StreamingLinearResampler]）都是 `internal` 共享的，唯一理由是：同一台手机上，
 * 「本机识别」和「云端切片」对同一个音轨**必须**得出同样的样本。
 * 各自抄一份的后果不是崩溃，而是两条路的识别质量悄悄不一样。
 *
 * ## 切片边界是采样点精确的
 *
 * 头部按「丢到区间起点」处理，可能多出**不到一个源帧**（44.1 kHz 下约 0.02 ms，
 * 听不出来）——这是 `MediaExtractor.seekTo` 只能定位到同步帧的必然代价。
 * 尾部则是**采样点精确**的：按 16 kHz 的目标样本数截断，所以每一块的时长都是
 * 5 分钟整（除最后一块），块偏移量可以直接用 `index * 300_000` 算，
 * 不需要回头去读每一块的实际时长。
 */
class AudioSliceWriter(private val context: Context, private val dispatchers: DispatcherProvider) {

    /**
     * 导出 `[startMs, endMs)` 到 [target]（会被覆盖），返回写进去的 16 kHz 样本数。
     *
     * 返回 0 表示这一段里**一个样本都没有**（区间落在文件末尾之外，或音轨为空）。
     * 调用方该跳过这一块而不是当成失败：真正的失败会抛异常。
     */
    suspend fun write(uri: Uri, startMs: Long, endMs: Long, target: File): Long =
        withContext(dispatchers.io) {
            // 解码循环是阻塞的，协程取消穿不进去，和 PcmExtractor 一样把 Job 带进去手查。
            writeBlocking(uri, startMs, endMs, target, currentCoroutineContext()[Job])
        }

    private fun writeBlocking(uri: Uri, startMs: Long, endMs: Long, target: File, job: Job?): Long {
        require(endMs > startMs) { "slice range must be non-empty: $startMs..$endMs" }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var out: RandomAccessFile? = null
        try {
            // 三个重载里只有这个同时吃 file:// 和 content://。
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.isEmpty()) throw AsrException.Decode(IOException("音轨没有标明格式"))
            val sourceRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: ASR_SAMPLE_RATE

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: IOException) {
                throw AsrException.Decode(e)
            }
            codec.configure(inputFormat, null, null, 0)

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            // 目标样本数由**毫秒**算出来，而不是由「解码了多少」累加而来：
            // 这样每块的时长是钉死的，块偏移量的算术才不会随设备漂移。
            val wantedSamples = (endMs - startMs) * ASR_SAMPLE_RATE / 1000L

            // SEEK_TO_PREVIOUS_SYNC 之前的样本交给下面的 skipping 丢掉。用更精确的
            // SEEK_TO_CLOSEST_SYNC 会在没有中间同步帧的容器上落到**后面**，
            // 那种结果是开头丢字——听起来像「识别不准」，查起来毫无线索。
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.start()

            out = RandomAccessFile(target, "rw")
            // 头部要等数据写完了才知道长度（最后一块可能短），所以先占位再回填。
            // 顺带把上一次的残留截掉：同一个文件是复用覆盖的。
            out.setLength(WavHeader.SIZE.toLong())
            out.seek(WavHeader.SIZE.toLong())

            val resampler = StreamingLinearResampler(fromRate = sourceRate, toRate = ASR_SAMPLE_RATE)
            var outputIsFloat = false
            var outputChannels = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 1
            var outputFormatRead = false
            val bufferInfo = MediaCodec.BufferInfo()
            var written = 0L
            var inputDone = false
            var outputDone = false
            var skipping = true

            while (!outputDone && written < wantedSamples) {
                if (job?.isActive == false) throw CancellationException("云端字幕生成已取消")

                if (!inputDone) {
                    if (extractor.sampleTime >= 0 && extractor.sampleTime > endUs) {
                        // 已经读过了需要的区间。**必须**在这里掐掉输入：不掐的话
                        // 一整部两小时的片子会被切成 24 块各自解码到底，
                        // 也就是把一次解码变成 24 次——慢到看起来像卡死。
                        val stopIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (stopIndex >= 0) {
                            codec.queueInputBuffer(
                                stopIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    } else {
                        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            val size =
                                if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
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
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputIsFloat = codec.outputFormat.isFloatPcm()
                        outputChannels =
                            codec.outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                        outputFormatRead = true
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        if (!outputFormatRead) {
                            outputIsFloat = codec.outputFormat.isFloatPcm()
                            outputChannels =
                                codec.outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                            outputFormatRead = true
                        }
                        var reachedEnd = false
                        if (bufferInfo.size > 0) {
                            val raw = codec.getOutputBuffer(outputIndex)?.let { buffer ->
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                ByteArray(bufferInfo.size).also { buffer.get(it) }
                            }
                            if (raw != null) {
                                val mono = downmixToMonoFloat(raw, raw.size, outputChannels, outputIsFloat)
                                val drop = if (skipping) {
                                    framesBefore(mono.size, bufferInfo.presentationTimeUs, startUs, sourceRate)
                                } else {
                                    0
                                }
                                if (drop < mono.size) {
                                    skipping = false
                                    // 只切一次：之后每一块都是完整在区间内的。
                                    val usable = if (drop <= 0) mono else mono.copyOfRange(drop, mono.size)
                                    val resampled = resampler.process(usable)
                                    if (resampled.isNotEmpty()) {
                                        val room = (wantedSamples - written).toInt()
                                        val use = if (resampled.size <= room) {
                                            resampled.size
                                        } else {
                                            room
                                        }
                                        out.write(pcm16Bytes(resampled, use))
                                        written += use
                                        reachedEnd = written >= wantedSamples
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (reachedEnd ||
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        ) {
                            outputDone = true
                        }
                    }
                }
            }

            // 回填头部：此时才知道数据区的真实长度（最后一块常常短于 5 分钟）。
            out.seek(0L)
            out.write(WavHeader.pcm16(dataBytes = written * 2, sampleRate = ASR_SAMPLE_RATE))
            // 上一次用同一个文件、这一次更短时，多余的尾巴必须截掉，否则
            // 上传的音频尾部会带上上次的残留。
            out.setLength(WavHeader.SIZE + written * 2)

            MspLog.d(TAG) {
                "切片导出完成：${startMs}–${endMs} ms → $written 个 16 kHz 样本" +
                    "（${written * 2} 字节 PCM）"
            }
            return written
        } catch (e: AsrException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw AsrException.Decode(e)
        } catch (e: MediaCodec.CodecException) {
            throw AsrException.Decode(e)
        } finally {
            runCatching { out?.close() }.onFailure { MspLog.w(TAG, it) { "关闭切片文件失败" } }
            codec?.let { c ->
                runCatching { c.stop() }.onFailure { MspLog.w(TAG, it) { "停止解码器失败" } }
                runCatching { c.release() }.onFailure { MspLog.w(TAG, it) { "释放解码器失败" } }
            }
            extractor.release()
        }
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

    private companion object {
        const val TAG = "AsrSlice"
    }
}

/**
 * 这一块缓冲区里有多少帧落在 [startUs] 之前。
 *
 * 读的是 `MediaExtractor.seekTo` 的后果：它只能定位到同步帧，所以第一块解码输出
 * 通常**早于**区间起点，必须把前面那段丢掉。丢多了会吃掉开头几个字，丢少了会
 * 让所有时间轴整体前移——两种都看不出来，只会觉得「识别得不太准」。
 *
 * [frameCount] 用**解码后**的帧数：`downmixToMonoFloat` 的输出已经是「一帧一个值」，
 * 与声道数无关，所以这里不再乘声道数。
 */
internal fun framesBefore(
    frameCount: Int,
    bufferStartUs: Long,
    startUs: Long,
    sampleRate: Int,
): Int {
    if (frameCount <= 0) return 0
    val offsetUs = startUs - bufferStartUs
    if (offsetUs <= 0L) return 0
    val frames = (offsetUs * sampleRate) / 1_000_000L
    return if (frames > frameCount) frameCount else frames.toInt()
}

/**
 * 浮点样本 → 16 bit 小端字节。
 *
 * 返回的数组**长度正好是 `length * 2`**。这里刻意不提供「复用同一个缓冲区」的版本：
 * `RandomAccessFile.write(byte[])` 写的是**整个数组**，复用缓冲区的写法（返回
 * 共享数组 + 在别处记住长度）一旦有一处忘了传长度，就会把上一次的残留字节写进
 * 音频里——它不报错、不崩，只是那一段声音变成噪声，而且只在恰好跨缓冲区边界时出现。
 * 一次解码循环多分配几万个几 KB 的数组，相对于解码本身可以忽略。
 *
 * 正向乘 32767：`+1.0` 正好落在 `Short.MAX_VALUE`，不需要额外的反向缩放因子
 * （乘 32768 会让 `+1.0` 溢出成 `-32768`，也就是把最大音量变成一次爆音）。
 */
internal fun pcm16Bytes(samples: FloatArray, length: Int = samples.size): ByteArray {
    require(length in 0..samples.size) { "length $length out of range for ${samples.size}" }
    val out = ByteArray(length * 2)
    for (index in 0 until length) {
        val value = (samples[index] * 32767f).roundToInt().coerceIn(-32768, 32767)
        out[index * 2] = value.toByte()
        out[index * 2 + 1] = (value shr 8).toByte()
    }
    return out
}
