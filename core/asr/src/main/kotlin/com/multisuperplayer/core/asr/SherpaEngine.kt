package com.multisuperplayer.core.asr

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.multisuperplayer.core.common.log.MspLog
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * VAD 报出的一段语音：绝对起点（采样点）+ 这段的样本。
 *
 * 为什么不直接把 `SpeechSegment` 往外传：那是 JNI 对象的引用，用完必须 `pop()`，
 * 一旦上层某条分支忘了 pop，VAD 的内部队列就会一直涨（整条电影下来是几十万个段），
 * 表现为内存缓慢上涨而不是报错。所以在引擎内部 `front()`+`pop()` 一次性做完，
 * 只把「一个起点 + 一个样本数组」这种纯数据交出去。
 */
internal class DetectedSpeech(val startSample: Int, val samples: FloatArray)

/**
 * 「一段样本 → 一句话」这一步的策略。
 *
 * 离线模型和流式模型的**解码形状**不同（见 [AsrEngine]），但上层的编排只关心
 * 「给我一段 16 kHz 的样本，还我一行文字」，所以差别收在这里，编排那边看不到。
 */
internal interface SegmentRecognizer : Closeable {

    fun recognize(samples: FloatArray, sampleRate: Int): String
}

/**
 * 离线识别：整段一次解完。
 *
 * **每段一个全新的 stream**（而不是复用一条 stream 反复 `reset`）：复用要求
 * 每处调用都记得 reset，漏一次就会把上一段的文字拼到这一段的开头——而且只在
 * 「上一段很长」时才明显，测试里很可能碰不到。新建 stream 的开销是几十微秒，
 * 相对每段几十毫秒的解码时间可以忽略。
 */
private class OfflineSegmentRecognizer(private val recognizer: OfflineRecognizer) : SegmentRecognizer {

    override fun recognize(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}

/**
 * 流式识别：送到 `inputFinished()` 之后反复解码到不 ready 为止。
 *
 * 这里调用的是**流式模型**，但切句仍然只用 VAD（见 [AsrVadTuning]）：
 * 流式模型自己也有断点检测（`EndpointConfig`），如果两边都生效，断句位置就由
 * 两者中更激进的那个决定，而那些句子在另一边看来是半截的。
 */
private class OnlineSegmentRecognizer(private val recognizer: OnlineRecognizer) : SegmentRecognizer {

    override fun recognize(samples: FloatArray, sampleRate: Int): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            // 补一段静音再收尾：流式模型的解码器需要「后面的上下文」才能确定最后一个词，
            // 少这一步的表现是每段的最后一个字/词被吞掉——不报错、字幕看着也通顺，
            // 只是每句都少一点，很容易被当成「模型就这样」。
            stream.acceptWaveform(FloatArray((TRAILING_SILENCE_SECONDS * sampleRate).toInt()), sampleRate)
            stream.inputFinished()
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}

/**
 * sherpa-onnx 的封装：VAD（切句）+ 识别器（出字）。
 *
 * ## 生命周期
 *
 * 构造一次很贵（要读 78～190 MB 的模型、建 onnxruntime 的 session），所以
 * **一次生成任务只构造一个**，用完 `close()`；不要按段构造。
 *
 * ## 为什么 VAD 的模型不在 assets 里
 *
 * `silero_vad.onnx` 只有 643 KB，是打进 APK 的。但真的让引擎去 `assets` 里读它，
 * 就得给构造器传一个**非 null** 的 `AssetManager`——而同一份配置里的识别模型
 * （在 `filesDir` 里）会被同一个 AssetManager 先查一遍 assets 再回落到文件系统，
 * 于是「模型到底从哪读的」变成一个依赖上游实现细节的问题。
 *
 * 所以这里走一条完全没歧义的路：把 VAD 模型从 assets 释放到 `filesDir/asr/` 一次，
 * 之后所有路径都是普通文件路径，`AssetManager` 一律传 `null`。
 *
 * ## 线程
 *
 * 构造、`accept`、`nextSegment`、`recognize`、`close` **都必须在同一个后台线程上**，
 * 而且不能在主线程（一秒钟起步）。调用方负责把它放到 `Dispatchers.IO` 上。
 */
internal class SherpaEngine(
    private val vad: Vad,
    private val recognizer: SegmentRecognizer,
) : Closeable {

    /** 喂一块 16 kHz 单声道样本。块长随意，VAD 自己按窗口攒。 */
    fun accept(chunk: FloatArray) {
        if (chunk.isNotEmpty()) {
            vad.acceptWaveform(chunk)
        }
    }

    /**
     * 取一段已经判定的语音；没有就返回 `null`。
     *
     * 一次只取一段（而不是 `List`）：段里的样本是**引用**，而 VAD 的内部缓冲在
     * `pop()` 之后就可能被复用/释放。攒成一整个列表再交出去，等于让上层拿着
     * 一批随时可能变成别的数据的数组。
     */
    fun nextSegment(): DetectedSpeech? {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val samples = segment.samples
            if (samples.isNotEmpty()) {
                return DetectedSpeech(segment.start, samples)
            }
        }
        return null
    }

    /** 音频喂完了。调用之后仍要 `nextSegment()` 直到 `null`，否则最后一段会丢。 */
    fun finish() {
        vad.flush()
    }

    /** 识别一段样本。样本必须是 16 kHz 单声道。 */
    fun recognize(samples: FloatArray): String = recognizer.recognize(samples, ASR_SAMPLE_RATE)

    /**
     * 释放原生资源。
     *
     * 这里刻意**不抛异常**：本方法总是在 `finally` 里被调用，一旦抛出就会把
     * 真正的失败原因顶掉（用户会看到「释放失败」，而真正的问题是模型坏了下到一半）。
     * 释放失败也没有补救动作可做，记一行日志就够。
     */
    override fun close() {
        runCatching { recognizer.close() }.onFailure { MspLog.w(TAG, it) { "释放识别器失败" } }
        runCatching { vad.release() }.onFailure { MspLog.w(TAG, it) { "释放 VAD 失败" } }
    }
}

/**
 * 按模型清单构造 [SherpaEngine]。
 *
 * 单独一个类而不是 `SherpaEngine` 的伴生函数：构造函数要 `Context`（读 assets 里的
 * VAD 模型），而这个 Context 是**应用级**的，不该出现在引擎实例里被一路传下去。
 */
internal class SherpaEngineFactory(private val context: Context) {

    /**
     * 抽出两个文件路径（VAD 模型、识别模型）构造引擎。
     *
     * 调用方要保证 [model] 的所有文件都已在 [locator] 里就位（大小对得上）——
     * 这里只负责把路径填进配置，不做「文件在不在」的判断；判断是
     * [AsrModelLocator.statusOf] 的职责，重复一遍只会多一处可能不一致的真相。
     */
    fun create(model: AsrModelInfo, locator: AsrModelLocator): SherpaEngine {
        loadNativeLibraries()
        val vad = buildVad(releaseVadModel(AsrModelLocator.rootOf(context.filesDir)))
        // VAD 已经建好了（占了原生内存），识别器再失败也必须把它放掉，
        // 否则「模型坏了 → 用户重试 → 换模型重试」几轮下来，原生内存就一直涨。
        return try {
            SherpaEngine(vad, buildRecognizer(model, locator))
        } catch (e: Throwable) {
            runCatching { vad.release() }.onFailure { MspLog.w(TAG, it) { "构造失败后释放 VAD 失败" } }
            throw e
        }
    }

    private fun buildVad(modelFile: File): Vad = try {
        Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = modelFile.absolutePath,
                    threshold = AsrVadTuning.THRESHOLD,
                    minSilenceDuration = AsrVadTuning.MIN_SILENCE_SECONDS,
                    minSpeechDuration = AsrVadTuning.MIN_SPEECH_SECONDS,
                    windowSize = AsrVadTuning.WINDOW_SIZE,
                    maxSpeechDuration = AsrVadTuning.MAX_SPEECH_SECONDS,
                ),
                sampleRate = ASR_SAMPLE_RATE,
                numThreads = INFERENCE_THREADS,
                provider = PROVIDER,
                debug = false,
            ),
        )
    } catch (e: Throwable) {
        throw AsrException.Engine(e)
    }

    private fun buildRecognizer(model: AsrModelInfo, locator: AsrModelLocator): SegmentRecognizer = try {
        val featureConfig = FeatureConfig(
            sampleRate = ASR_SAMPLE_RATE,
            featureDim = FEATURE_DIM,
            dither = 0f,
        )
        val tokens = locator.fileOf(model, model.file(AsrFileRole.TOKENS)).absolutePath
        when (model.engine) {
            AsrEngine.OFFLINE -> OfflineSegmentRecognizer(
                OfflineRecognizer(
                    assetManager = null,
                    config = OfflineRecognizerConfig(
                        featConfig = featureConfig,
                        modelConfig = OfflineModelConfig(
                            paraformer = OfflineParaformerModelConfig(
                                model = locator.fileOf(model, model.file(AsrFileRole.MODEL)).absolutePath,
                            ),
                            tokens = tokens,
                            numThreads = INFERENCE_THREADS,
                            provider = PROVIDER,
                            debug = false,
                            modelType = "paraformer",
                        ),
                    ),
                ),
            )

            AsrEngine.STREAMING -> OnlineSegmentRecognizer(
                OnlineRecognizer(
                    assetManager = null,
                    config = OnlineRecognizerConfig(
                        featConfig = featureConfig,
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = locator.fileOf(model, model.file(AsrFileRole.ENCODER)).absolutePath,
                                decoder = locator.fileOf(model, model.file(AsrFileRole.DECODER)).absolutePath,
                                joiner = locator.fileOf(model, model.file(AsrFileRole.JOINER)).absolutePath,
                            ),
                            tokens = tokens,
                            numThreads = INFERENCE_THREADS,
                            provider = PROVIDER,
                            debug = false,
                        ),
                    ),
                ),
            )
        }
    } catch (e: Throwable) {
        throw AsrException.Engine(e)
    }

    /**
     * 把 assets 里的 VAD 模型释放到私有目录，返回释放后的文件。
     *
     * 已经存在就直接用（不做哈希校验，理由同 [AsrModelLocator.statusOf]：这个文件
     * 是我们自己从 APK 里拷出来的，不是从网上下的，没有「下了一半」的中间态）。
     * 拷贝走 `.part` + rename，和模型下载器同一套规矩：直接写目标文件的话，
     * 拷贝过程中进程被杀会留下一个「存在但截断」的 onnx，引擎加载它时崩在原生层，
     * 堆栈里一个字都读不出来。
     */
    private fun releaseVadModel(rootDir: File): File {
        val target = File(rootDir, VAD_MODEL_ASSET)
        if (target.length() > 0L) {
            return target
        }
        if (!rootDir.exists() && !rootDir.mkdirs()) {
            throw AsrModelException.Write(IOException("无法创建目录 $rootDir"))
        }
        val part = File(rootDir, "$VAD_MODEL_ASSET$PART_SUFFIX")
        part.delete()
        try {
            context.assets.open(VAD_MODEL_ASSET).use { input ->
                part.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
        } catch (e: IOException) {
            part.delete()
            throw AsrModelException.Write(e)
        }
        if (part.length() <= 0L) {
            part.delete()
            throw AsrModelException.Write(IOException("$VAD_MODEL_ASSET 在 APK 里是空的"))
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw AsrModelException.Write(IOException("无法写入 ${target.absolutePath}"))
        }
        MspLog.d(TAG) { "已释放 VAD 模型：${target.absolutePath}（${target.length()} 字节）" }
        return target
    }
}

/** 加载两个原生库，只做一次。 */
private fun loadNativeLibraries() {
    try {
        nativeLibrariesLoaded
    } catch (e: Throwable) {
        // 必须是 Throwable：链接失败抛的是 UnsatisfiedLinkError（Error 而不是 Exception），
        // 而它恰好是最需要翻译成用户能看懂的一句话的那种失败
        // （release 包少了 proguard keep 规则时就是这个错误）。
        throw AsrException.Engine(e)
    }
}

private val nativeLibrariesLoaded: Unit by lazy {
    // 顺序不能换：sherpa-onnx-jni 是在 onnxruntime 之上编的，
    // 反过来会让符号解析失败（`cannot locate symbol "OrtGetApiBase"`）。
    System.loadLibrary("onnxruntime")
    System.loadLibrary("sherpa-onnx-jni")
}

private const val TAG = "AsrEngine"

/** onnxruntime 的线程数。sherpa 的默认值就是 2，没有实测依据之前不往上调。 */
private const val INFERENCE_THREADS = 2

/** 梅尔谱的维度。中文/英文模型都是 80，这不是可调项。 */
private const val FEATURE_DIM = 80

private const val PROVIDER = "cpu"

/** 流式模型收尾前补的静音长度（秒），取自 sherpa 官方示例。 */
private const val TRAILING_SILENCE_SECONDS = 0.66f

private const val PART_SUFFIX = ".part"

private const val COPY_BUFFER_BYTES = 64 * 1024
