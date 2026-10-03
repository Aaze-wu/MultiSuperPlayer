package com.multisuperplayer.core.asr

import android.content.Context
import android.net.Uri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * 生成进度。
 *
 * [processedMs] / [totalMs] 是**音频上的位置**，不是「第几个文件」——用户关心的是
 * 「这两个小时的片子还剩多久」，不是「引擎内部走到哪一步了」。
 */
data class AsrProgress(
    val processedMs: Long,
    val totalMs: Long,
    /** 已经攒下多少条字幕。它是「有在动」最直观的证据。 */
    val segmentCount: Int,
) {

    /**
     * 进度是不是确定的。
     *
     * 部分容器（有些 MKV/TS）不写时长，这时候**必须**显示「不确定」形态的进度条：
     * 按 0% 算会出现「先冲到 80% 再卡住」，看着像死了；按 100% 算则是撒谎。
     */
    val isDeterminate: Boolean get() = totalMs > 0L

    val fraction: Float
        get() = if (totalMs <= 0L) {
            0f
        } else {
            (processedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * 整条媒体跑一遍，生成字幕。
 *
 * ## 为什么是「边解码边识别」的一趟，而不是「先导出 wav 再识别」
 *
 * 两小时的片子是 16 kHz 单声道 32 bit 浮点的 460 MB，写到磁盘上再读回来是
 * 白白多出近 1 GB 的 IO（还不算用户手机上可能只剩几百 MB 的情况）。一趟做完的话
 * 内存里同时只有一块解码输出，磁盘上什么都不留。
 *
 * 代价是**必须先解出整条音轨**才能知道「总共多长」（[AsrSegmentBuilder] 要用它把
 * 末尾的字幕夹在片子里面），所以进度条的总长只能信容器的元信息。
 *
 * ## 为什么整段只用一个引擎实例
 *
 * 引擎构造要读 78～190 MB 的模型并建 onnxruntime 的 session，一次几秒。
 * 按段构造的话，「一小时的片子有 900 段」就是 900 次几秒。
 */
class AsrTranscriber internal constructor(
    private val extractor: PcmExtractor,
    private val engineFactory: SherpaEngineFactory,
    private val locator: AsrModelLocator,
    private val dispatchers: DispatcherProvider,
) {

    companion object {

        /**
         * 生产环境用的入口。
         *
         * 构造函数是 internal 的：参数里的 [SherpaEngineFactory] 是模块内部的东西，
         * 不应该（也没必要）漏到界面层去。这里把依赖装配一次，界面层只要认这个函数。
         */
        fun create(context: Context, dispatchers: DispatcherProvider): AsrTranscriber = AsrTranscriber(
            extractor = PcmExtractor(context, dispatchers),
            engineFactory = SherpaEngineFactory(context),
            locator = AsrModelLocator(AsrModelLocator.rootOf(context.filesDir)),
            dispatchers = dispatchers,
        )
    }

    /**
     * 识别 [uri] 的音轨，返回按时间排好的字幕段。
     *
     * 没有识别到任何人声时抛 [AsrException.Silent] 而不是返回空列表：一个空结果
     * 对用户来说就是「失败」，让调用方每次都记得判空、忘了就显示一片空白，
     * 不如在源头就把它定义成失败。
     */
    suspend fun transcribe(
        uri: Uri,
        model: AsrModelInfo,
        onProgress: (AsrProgress) -> Unit = {},
    ): List<AsrSegment> = withContext(dispatchers.default) {
        // 先判模型在不在，再开始解码：不然用户会先等半分钟的解码，然后才被告知
        // 「模型没下好」——而这件事在一开始就知道。
        if (locator.statusOf(model) !is AsrModelStatus.Ready) {
            throw AsrException.ModelMissing(model)
        }

        val probe = extractor.probe(uri)
        val engine = engineFactory.create(model, locator)
        val detected = mutableListOf<AsrDetected>()
        try {
            var processedSamples = 0L
            var lastReportedMs = 0L

            /** 把引擎里已经判定的段全取出来识别掉。每块之后都要调，不能攒到最后。 */
            fun drain() {
                while (true) {
                    val speech = engine.nextSegment() ?: break
                    val text = AsrTextNormalizer.normalize(engine.recognize(speech.samples))
                    // 每段的**区间**只有这一行会写出来。切句出问题时（并句、丢开头）
                    // 别的日志只能告诉你「一共几条」，看不出「本该 6 条却只有 2 条」，
                    // 也看不出那 2 条横跨了 11 秒。记在 DEBUG 上：release 的 INFO
                    // 门限会挡掉它，不会给两小时的片子刷出上千行。
                    MspLog.d(TAG) {
                        "VAD 段：${speech.startSample} 起 ${speech.samples.size} 采样" +
                            " → 「${text.take(40)}」"
                    }
                    // 纯噪声段（VAD 判成语音但识不出字）直接丢掉：留一条空字幕
                    // 在屏幕上会让用户以为生成坏了。丢在这里而不是最后，
                    // 是为了让后面的外扩/分界逻辑看到的是真正相邻的两句。
                    if (AsrTextNormalizer.isMeaningful(text)) {
                        detected += AsrDetected(
                            range = AsrRange(speech.startSample, speech.startSample + speech.samples.size),
                            text = text,
                        )
                    }
                }
            }

            val totalSamples = extractor.extract(uri) { chunk ->
                processedSamples += chunk.size
                engine.accept(chunk)
                drain()
                val processedMs = processedSamples * 1000L / ASR_SAMPLE_RATE
                // 每块都回调的话，两小时的片子是几万次状态更新。界面用 StateFlow
                // 能合并掉，但没必要为它制造几万个临时对象。
                if (processedMs - lastReportedMs >= PROGRESS_STEP_MS) {
                    lastReportedMs = processedMs
                    onProgress(AsrProgress(processedMs, probe.durationMs, detected.size))
                }
            }

            // 音频喂完了还要把最后一段捞出来：VAD 手里可能正攒着一段没判定完的。
            engine.finish()
            drain()

            val segments = AsrSegmentBuilder.build(detected, totalSamples.clampToInt(), ASR_SAMPLE_RATE)
            if (segments.isEmpty()) {
                throw AsrException.Silent
            }
            onProgress(AsrProgress(probe.durationMs, probe.durationMs, segments.size))
            MspLog.i(TAG) {
                "生成完成：${segments.size} 条，音频 $totalSamples 点" +
                    "（${totalSamples * 1000L / ASR_SAMPLE_RATE / 1000L} 秒）"
            }
            segments
        } catch (e: AsrException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 原生层的失败是个大杂烩（UnsatisfiedLinkError / RuntimeException /
            // 内存不足），它们共同的含义只有一个：引擎这一侧坏了。
            // 不翻译的话用户看到的是英文堆栈。
            throw AsrException.Engine(e)
        } finally {
            engine.close()
        }
    }
}

private const val TAG = "AsrTranscribe"

/** 进度回调的最小间隔（音频毫秒）。0.25 秒一次已经很连贯了。 */
private const val PROGRESS_STEP_MS = 250L

/**
 * 采样点总数转 `Int`。
 *
 * 16 kHz 下 `Int` 能表示到 37 小时，正常媒体都到不了；但**默默溢出会变成负数**，
 * 而负数在 [AsrSegmentBuilder] 里等于「音频长为 0」，结果是「这条片子没有人声」——
 * 一个把超长文件说成静音的错。所以在边界上夹一次。
 */
private fun Long.clampToInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
