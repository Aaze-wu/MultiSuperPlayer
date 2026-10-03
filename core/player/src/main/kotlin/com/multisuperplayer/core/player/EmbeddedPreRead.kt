package com.multisuperplayer.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException

private const val TAG = "EmbeddedPreRead"

/**
 * 单个字幕样本允许占用的字节上限。
 *
 * 存在的理由是**坏文件**：样本长度是从容器里读出来的整数，一个伪造/损坏的文件可以让
 * 它变成几个 GB，而我们按它去 `new byte[]` 就是 OOM。`OutOfMemoryError` 不是 `Exception`，
 * 外层那个 `catch` 拦不住——整个进程会挂掉。超过上限时宁可让这一次预读失败。
 */
private const val MAX_SAMPLE_BYTES = 1 shl 20

/** 缓冲区初始大小。一条台词通常几十到几百字节。 */
private const val DEFAULT_BUFFER_BYTES = 8 * 1024

/**
 * 最后一条 cue 的结束时间缺失时，给它多长。
 *
 * 与 `core:subtitle` 的 `CuePostProcess.finish(tailDurationMs)` 取同一个值（10 秒），
 * 两处都只是「兜底不写成无限」而已。
 */
internal const val EMBEDDED_CUE_TAIL_MS = 10_000L

/**
 * 从播放器那份轨道清单里算出「这一条轨该怎么预读」。
 *
 * [MspTrackInfo] 不在清单里（那一条已经消失了）时返回 null。序号与总数都取**播放器这一侧**
 * 的文本轨序号：它只在「两条轨语言和标签完全一样」时才用来分级，见 [pickPreReadCandidate]。
 */
internal data class EmbeddedPreReadTarget(
    val track: MspTrackInfo,
    val ordinal: Int,
    val textTrackCount: Int,
)

/** 把 [track] 包成预读目标；[track] 不在本清单里时为 null。 */
internal fun List<MspTrackInfo>.preReadTarget(track: MspTrackInfo): EmbeddedPreReadTarget? {
    val texts = filter { it.kind == MspTrackKind.TEXT }
    val ordinal = texts.indexOf(track)
    if (ordinal < 0) return null
    return EmbeddedPreReadTarget(track = track, ordinal = ordinal, textTrackCount = texts.size)
}

/**
 * 当前该不该预读、该预读哪条轨。null = 不预读（= [EmbeddedPreReadState.Off]）。
 *
 * 三条「不适用」的理由都在这里集中判断，别处不再重复：
 * 1. **片源不是本地可随机读取的东西**——网络流不能为了字幕把整个流下载一遍；
 * 2. **没有选中的文本轨**（这条媒体没有字幕，或者用户关掉了）；
 * 3. **选中的是位图字幕**（PGS / VobSub / DVB）——它们解出来是图片，我们的层画不出来，
 *    预读出来也没人能用（位图那一摊是 v0.9 的事）。
 *
 * 抽成 `List<MspTrackInfo>` 上的扩展是为了它**纯**：单测不需要播放器、不需要 Context。
 */
internal fun List<MspTrackInfo>.preReadRequest(uri: String): EmbeddedPreReadTarget? {
    if (!isPreReadableUri(uri)) return null
    val selected = firstSelectedTextTrack() ?: return null
    if (!selected.isTextRenderable()) return null
    return preReadTarget(selected)
}

/**
 * 这个 uri 值不值得预读。
 *
 * 只认**本地**的 scheme。用字符串切分而不是 `android.net.Uri`：`Uri.parse` 在 JVM 单测里
 * 返回 null（本模块开了 `unitTests.isReturnDefaultValues`），那样这个函数就测不了了。
 *
 * 没有 scheme 的裸路径按「不预读」处理：那种写法我们已经不产出了，判 false 是安全的方向
 * （预读失败的代价只是退回流式表）。
 */
internal fun isPreReadableUri(uri: String): Boolean {
    val scheme = uri.substringBefore(':', missingDelimiterValue = "").trim().lowercase()
    return scheme == "content" || scheme == "file" || scheme == "android.resource"
}

/**
 * 日志 / 排错用的一句话。
 *
 * `Ready` 里可能有几千条 cue，直接 `"$state"` 会打出一条几兆的日志（而且它就在
 * 每次切轨都会走的那条路径上）。
 */
internal fun EmbeddedPreReadState.describe(): String = when (this) {
    EmbeddedPreReadState.Off -> "Off（不适用）"
    EmbeddedPreReadState.Reading -> "Reading"
    // 打**时间范围**而不只是条数：这份表时刻错了的时候（实测踩过一次「每条 cue 都
    // 多了一倍」，见 `cuePacketSampleOf`），条数、顺序、单调性全都正常，只有范围能露馅。
    // 表非空是 `Ready` 的约定，所以 `first()`/`last()` 是安全的。
    is EmbeddedPreReadState.Ready ->
        "Ready（${cues.size} 行，${cues.first().startMs}ms → ${cues.last().endMs}ms）"
    is EmbeddedPreReadState.Failed -> "Failed（$reason）"
}

/** 一条内嵌 cue 的原始形态（微秒）。
 * 单独一层是为了让「样本 → cue 表」这段逻辑能脱离 Media3 单测：Media3 的
 * `CuesWithTiming` 走的是 `CueDecoder`（内部是 `android.os.Parcel`），在 JVM 上跑不了。
 */
internal data class EmbeddedCueSample(
    val startTimeUs: Long,
    val durationUs: Long,
    val texts: List<String>,
)

/**
 * 把一条 [CuesWithTiming] 折算成我们的样本。
 *
 * ## 三个时间字段的关系（这里最容易搞错）
 *
 * - [sampleTimeUs] 是 `sampleMetadata` 给过来的**样本时间**；
 * - cue 的绝对开始时间按下面三条取，和 Media3 自己的解码路径
 *   （`SubtitleTranscodingTrackOutput.outputSample`）逐条对齐：
 *   1. `startTimeUs == C.TIME_UNSET` ⇒ 这条 cue 自己没有时间信息（整个样本就是一条台词），
 *      用样本时间；
 *   2. `subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE` ⇒ cue 的时间**相对样本**，
 *      加上样本时间。我们的预读链路走的正是这条（Media3 把字幕转码成 cue 包时会把
 *      `subsampleOffsetUs` 重置成这个值，并把绝对时间写进 `sampleMetadata`）；
 *   3. 其余 ⇒ cue 的时间是绝对的，再加上 `subsampleOffsetUs`。
 *
 * 全是空文本的 cue（位图字幕解出来就是这样）返回 null。
 */
internal fun embeddedCueSampleOf(
    cues: List<Cue>,
    startTimeUs: Long,
    durationUs: Long,
    sampleTimeUs: Long,
    subsampleOffsetUs: Long,
): EmbeddedCueSample? {
    val texts = cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }
    if (texts.isEmpty()) return null
    val start = when {
        startTimeUs == C.TIME_UNSET -> sampleTimeUs
        subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE -> sampleTimeUs + startTimeUs
        else -> startTimeUs + subsampleOffsetUs
    }
    return EmbeddedCueSample(startTimeUs = start, durationUs = durationUs, texts = texts)
}

/**
 * cue 包（被 Media3 转码过的字幕轨）的一个样本 → 整轨表上的一条记录。
 *
 * ## 为什么起点**只**由样本时刻决定
 *
 * cue 包里**没有起点**：`CueEncoder.encode(cues, durationUs)` 只编码 cue 和时长，起点是
 * 隐含在样本时刻里的。原因是解封装器那一侧已经把起点折了进去——
 * `SubtitleTranscodingTrackOutput.format()` 会给下游格式设
 * `subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE`，于是 `outputSample()` 里的
 * `outputSampleTimeUs = timeUs + cuesWithTiming.startTimeUs`，交给我们的样本时刻
 * **就是**绝对时刻。所以这里取 [C.TIME_UNSET]，让 [embeddedCueSampleOf] 走
 * 「用样本时刻」那一支。
 *
 * 单独成函数是为了能在 JVM 上单测：`CueDecoder` 内部是 `android.os.Parcel`，
 * 真机以外跑不了，而这个约定**配错过一次**——把 `decode` 的起点参数填成样本时刻，
 * 整表时刻就变成两倍（46.88 秒播出「第 23 秒的台词」），而条数、顺序、单调性全正常。
 * 也就是说：这类错误只能靠「范围」而不是「形状」发现。
 */
internal fun cuePacketSampleOf(cues: CuesWithTiming, sampleTimeUs: Long): EmbeddedCueSample? =
    embeddedCueSampleOf(
        cues = cues.cues,
        // 不是「懒得填」，是包里没有这个信息。
        startTimeUs = C.TIME_UNSET,
        durationUs = cues.durationUs,
        // 只为了让 [embeddedCueSampleOf] 的规则读起来自洽：转码后的字幕轨格式上
        // 就是 `OFFSET_SAMPLE_RELATIVE`（这里那个分支根本走不到，起点走第一条规则）。
        subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE,
        sampleTimeUs = sampleTimeUs,
    )

/**
 * 样本序列 → 整轨台词表。
 *
 * 与流式那条路（[EmbeddedSubtitleState.receive]）的区别：**结束时间是真的**。容器里的每条
 * 字幕样本自带时长（Matroska 会把时长写进字幕样本的结束时间码，Media3 再把它带进
 * `CuesWithTiming.durationUs`），所以这里不需要「等下一批到来才回填」。
 *
 * 结束时间缺失时按顺序兜底：下一条的开始时间；没有下一条就用 [tailDurationMs]，
 * 绝不写成 `Long.MAX_VALUE`——那正是流式表让速率换算在「调快」方向上失效的根源。
 *
 * 一条 cue 里多行用 `\n` 拼接：我们的字幕层是「一条 cue 可以有多行」
 * （`cueLinesFor` 按 `\n` 切），Media3 却按「一个 `Cue` 一行」给。
 */
internal fun collectEmbeddedCues(
    samples: List<EmbeddedCueSample>,
    tailDurationMs: Long = EMBEDDED_CUE_TAIL_MS,
): List<SubtitleCue> {
    if (samples.isEmpty()) return emptyList()
    val ordered = samples.sortedBy { it.startTimeUs }
    val cues = ArrayList<SubtitleCue>(ordered.size)
    for ((position, sample) in ordered.withIndex()) {
        val text = sample.texts.joinToString("\n").trim()
        if (text.isEmpty()) continue
        val startMs = sample.startTimeUs / 1000L
        val endMs = when {
            sample.durationUs > 0L -> startMs + sample.durationUs / 1000L
            else -> ordered.getOrNull(position + 1)
                ?.let { it.startTimeUs / 1000L }
                ?.takeIf { it > startMs }
                ?: (startMs + tailDurationMs)
        }
        // 至少 1ms：`endMs == startMs` 的 cue 在 `SubtitleDocument.cueAt` 里永远不成立，
        // 等于白读一行。
        cues += SubtitleCue(
            index = cues.size,
            startMs = startMs,
            endMs = maxOf(endMs, startMs + 1L),
            text = text,
        )
    }
    return cues
}

/** 一条内嵌文本轨的「身份」。匹配播放器选中的那条轨时用它。 */
internal data class EmbeddedTrackKey(val language: String?, val label: String?)

/** 文件里那条轨的身份。[Format.language] 走和 [MspTrackInfo] 同一套归一化。 */
internal fun embeddedTrackKeyOf(format: Format): EmbeddedTrackKey =
    EmbeddedTrackKey(
        language = normalizeLanguageTag(format.language),
        label = format.label?.trim()?.takeIf { it.isNotEmpty() },
    )

/** 播放器那条轨的身份。 */
internal fun embeddedTrackKeyOf(track: MspTrackInfo): EmbeddedTrackKey =
    EmbeddedTrackKey(
        // 再归一化一次：`MspTrackInfo.language` **约定**是归一化过的（`ExoPlayerController`
        // 那边做的），但这里不能依赖约定——多归一化一次是幂等的（`zh` → `zh`），
        // 少归一化一次则是「同一条轨在两边算出来的键不一样」，表现成预读永远报
        // 「找不到轨」，而字幕明明就在那儿。
        language = normalizeLanguageTag(track.language),
        label = track.label?.trim()?.takeIf { it.isNotEmpty() },
    )

/**
 * 在文件里的文本轨中找出「播放器当前选中的那一条」，返回 [candidates] 的下标。
 *
 * ## 为什么匹配键只有语言 + 标签
 *
 * 同一条轨在两边的 MIME **一定不一样**：播放器看到的是 Media3 重写后的
 * `application/x-media3-cues`（真实格式挪到了 `codecs`），我们直接驱动解封装器时看到的是
 * 容器里写的原始 MIME。所以 MIME 一律不参与匹配；`language` / `label` 是被原样保留的两样
 * （`SubtitleTranscodingTrackOutput` 用的是 `buildUpon()`）。
 *
 * ## 为什么撞车时要用序号，而且要「条数一致」才敢用
 *
 * 两条都叫「Chinese」、都没标签的文件很常见。此时只能靠「文件里第几条文本轨」来分，
 * 但播放器那份清单会**丢掉它不支持的轨**（`syncTracks` 里 `!group.isSupported` 直接 continue），
 * 两边条数一旦不同，序号就是错的——**宁可失败也不能猜**：猜错的后果是悄悄显示另一条轨的
 * 台词，而用户完全看不出来。所以 [ordinalsConsistent] 为 false 时，撞车 = 失败。
 */
internal fun pickPreReadCandidate(
    candidates: List<EmbeddedTrackKey>,
    target: EmbeddedTrackKey,
    targetOrdinal: Int,
    ordinalsConsistent: Boolean,
): Int? {
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return 0
    if (!ordinalsConsistent) return null
    return targetOrdinal.takeIf { it in candidates.indices }
}

/**
 * 内嵌文本字幕的**整轨预读**。
 *
 * ## 它做什么
 *
 * 自己驱动 Media3 的解封装器把**整条**字幕轨读出来，得到一张完整的台词表，
 * 供 [EmbeddedPreReadState.Ready] 发布；字幕速率要按表换算位置（而不是按「此刻该显示什么」），
 * 才有正确的整表可查。
 *
 * ## 为什么必须自己驱动解封装器
 *
 * Media3 面向播放的 `onCues` 回调语义是「**此刻**该显示什么」，攒不出未来的行；而它也**没有**
 * 任何「给我整条字幕轨」的公开入口。所以只能自己走 `Extractor.read()` 这一层：
 *
 * ```
 * DefaultDataSource → DefaultExtractorInput → Extractor.read() 循环
 *                                          ↘ 我们自己的 ExtractorOutput / TrackOutput
 * ```
 *
 * 走 `DefaultExtractorsFactory`（默认 `textTrackTranscodingEnabled = true`）时，Media3 会在
 * 解封装器**内部**用 `SubtitleTranscodingTrackOutput` 把字幕样本转码成 cue 包，于是我们的
 * `TrackOutput` 收到的是 `application/x-media3-cues` + `CueEncoder` 编码过的字节，
 * 用公开的 [CueDecoder] 解回来即可。好处是「预读看到的」和「播放看到的」完全是同一条链路，
 * 时间戳与时长也已经是 Media3 归一化过的。
 *
 * 万一某条轨没有被转码（解封装器不认得这个字幕格式，或者 Media3 以后改了默认值），
 * 我们的 `TrackOutput` 会收到原始样本，那时退回 [SubtitleParser] 自己解析——
 * 这条路 Media3 也替我们铺好了：`MatroskaExtractor` 会给每条字幕样本**拼上完整的时间码头**
 * （`00:00:00,000 --> 00:00:00,000`，结束时间码被换成真实时长），所以每条样本都是自包含文档。
 *
 * ## 代价
 *
 * 字幕样本在容器里是和音视频交错存放的，所以「读完整条字幕轨」= 顺序扫一遍整个文件。
 * 这是**后台**做的（播放不会等它），而且读的是同一份文件、顺带把页缓存热了；但一个几十 GB
 * 的文件仍然要读很久。所以它必须可取消：换条目、换轨、`release()` 都要能立刻停下
 * （取消杠杆见 [Extractor.read] 循环里的 `ensureActive`——`DefaultExtractorInput` 会在
 * 每次上游读取时检查线程中断标志）。
 */
@OptIn(UnstableApi::class)
internal class EmbeddedSubtitlePreReader(
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 读整条轨。**不抛异常**：任何失败都变成 [EmbeddedPreReadState.Failed]。
     *
     * 唯一的例外是取消（[CancellationException] 照原样抛出），它是控制流而不是失败，
     * 吞掉会让 `job.cancel()` 之后的 `join()` 变成「取消成功但协程还在跑」。
     */
    suspend fun read(uri: String, target: EmbeddedPreReadTarget): EmbeddedPreReadState =
        withContext(ioDispatcher) {
            val source = DefaultDataSource.Factory(appContext).createDataSource()
            try {
                collect(source, uri, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MspLog.w(TAG, error) { "整轨预读失败（$uri）：${error.message}" }
                EmbeddedPreReadState.Failed(EmbeddedPreReadReason.READ_ERROR)
            } finally {
                // 失败路径上 `DataSource.close()` 自己也可能抛（比如网络源的收尾），
                // 那不该把已经算好的结果冲掉。
                runCatching { source.close() }
            }
        }

    private suspend fun collect(
        source: DataSource,
        uri: String,
        target: EmbeddedPreReadTarget,
    ): EmbeddedPreReadState {
        val parsed = Uri.parse(uri)
        // `DataSource.open` 返回 `long`（Media3 那边的 `C.LENGTH_UNSET` 是 `int`，
        // 混着比较在 Kotlin 里直接编译不过）。
        val lengthUnset = C.LENGTH_UNSET.toLong()
        val opened = source.open(DataSpec.Builder().setUri(parsed).build())
        var length = if (opened == lengthUnset) lengthUnset else opened
        var input: ExtractorInput = DefaultExtractorInput(source, 0L, length)
        val output = PreReadExtractorOutput(target)
        val extractor = sniff(DefaultExtractorsFactory().createExtractors(parsed, emptyMap<String, List<String>>()), input)
            ?: return EmbeddedPreReadState.Failed(EmbeddedPreReadReason.CONTAINER_UNSUPPORTED)
        extractor.init(output)

        val position = PositionHolder()
        while (true) {
            // 取消点。必须放在这里（而不是别处）：已经读过的部分要能立刻丢掉，
            // 不能等这一遍文件扫完。
            currentCoroutineContext().ensureActive()
            when (extractor.read(input, position)) {
                Extractor.RESULT_END_OF_INPUT -> return output.result()
                Extractor.RESULT_SEEK -> {
                    // 解封装器要求「跳到这个位置重新开一个输入」。**不能**假设一个
                    // `ExtractorInput` 能一路读到文件尾：很多格式的索引在文件末尾，
                    // 解封装器会先跳到那里读完索引再跳回来。
                    val next = position.position
                    source.close()
                    val reopened = source.open(DataSpec.Builder().setUri(parsed).setPosition(next).build())
                    // `open` 回到的是「从 `next` 起还剩多少」，而 `DefaultExtractorInput`
                    // 要的是**整段**的长度，所以要把起点加回去。
                    length = if (reopened == lengthUnset) lengthUnset else next + reopened
                    input = DefaultExtractorInput(source, next, length)
                }
            }
        }
    }

    /**
     * 依次问每个解封装器「这是你的格式吗」，**成功之后把读取位置复位**。
     *
     * 复位的责任在我这一侧（[Extractor.sniff] 的 KDoc 说得很清楚：返回 true 时读取位置
     * 可能已经被改过），而且复位必须对**没认出来**的那些也做——否则下一个解封装器
     * 看到的是上一个啃掉一截之后的字节。
     *
     * 认不出来（或者文件短到 `sniff` 直接抛 EOF）返回 null：那是
     * [EmbeddedPreReadReason.CONTAINER_UNSUPPORTED]，与「读的过程中出错」不是一回事。
     */
    private fun sniff(extractors: Array<Extractor>, input: ExtractorInput): Extractor? {
        for (extractor in extractors) {
            val matched = try {
                extractor.sniff(input)
            } catch (error: IOException) {
                null
            }
            if (matched != null && matched) return extractor
            // sniff 抛异常时读取位置是未知的，复位一次；复位本身也可能抛
            // （位置已经在文件尾之外时），那没有可救的余地。
            try {
                input.resetPeekPosition()
            } catch (error: IOException) {
                return null
            }
        }
        return null
    }
}

/**
 * 我们自己的 [ExtractorOutput]：把流里的文本轨收集起来，最后挑出要的那一条。
 *
 * 只对**键匹配目标**的那条轨累积 cue 字节（其余轨的样本照样要从 `ExtractorInput` 里读走、
 * 只是不解析）——一个文件里三条字幕轨时省掉三分之二的解析。
 */
private class PreReadExtractorOutput(
    private val target: EmbeddedPreReadTarget,
) : ExtractorOutput {

    private val targetKey = embeddedTrackKeyOf(target.track)
    private val order = ArrayList<PreReadTrackOutput>()
    private val byId = LinkedHashMap<Int, PreReadTrackOutput>()
    private var textOrdinal = 0

    /**
     * 同一个 id 会被问多次（[ExtractorOutput.track] 的契约就是「同一个 id 返回同一个
     * [TrackOutput]」）。缓存下来，否则同一张表会被拆到好几个对象里，谁也拼不完整。
     */
    override fun track(id: Int, type: Int): TrackOutput {
        byId[id]?.let { return it }
        val ordinal = if (type == C.TRACK_TYPE_TEXT) textOrdinal++ else -1
        val track = PreReadTrackOutput(targetKey = targetKey, ordinal = ordinal)
        byId[id] = track
        order += track
        return track
    }

    override fun endTracks() = Unit

    /** 索引对我们没用（我们反正要顺序读完），但必须实现。 */
    override fun seekMap(seekMap: SeekMap) = Unit

    /** 挑出目标那条轨并算出它的台词表。 */
    fun result(): EmbeddedPreReadState {
        val textTracks = order.filter { it.ordinal >= 0 }
        val matched = textTracks.filter { it.key == targetKey }
        val index = pickPreReadCandidate(
            candidates = matched.mapNotNull { it.key },
            target = targetKey,
            targetOrdinal = target.ordinal,
            ordinalsConsistent = textTracks.size == target.textTrackCount,
        )
        val chosen = index?.let { matched[it] }
            ?: return EmbeddedPreReadState.Failed(EmbeddedPreReadReason.TRACK_NOT_FOUND)
        val cues = collectEmbeddedCues(chosen.samples)
        if (cues.isEmpty()) {
            MspLog.d(TAG) {
                "预读找到轨道但一条台词都没解出来（mime=${chosen.mimeType ?: "?"}，" +
                    "样本 ${chosen.sampleCount} 条）"
            }
            return EmbeddedPreReadState.Failed(EmbeddedPreReadReason.NO_CUES)
        }
        return EmbeddedPreReadState.Ready(cues)
    }
}

/**
 * 我们自己的 [TrackOutput]：把一条文本轨的所有 cue 攒下来。
 *
 * ## 只缓冲需要的字节
 *
 * 不匹配目标的那条轨直接**丢弃**样本数据——但注意是「读走再丢」而不是「不读」：
 * [TrackOutput.sampleData] 负责把字节从 `ExtractorInput` 里消费掉，不读就等于把解封装器的
 * 读取位置搞乱（后面全部串行）。所以两条路都读，只是不往缓冲区里放、不解析。
 *
 * ## 字节账怎么算
 *
 * 与 Media3 的 `SubtitleTranscodingTrackOutput` 逐行同构：`sampleMetadata` 里的 `offset` 是
 * 「这个样本之后还有多少字节」——补充数据和加密数据会跟在样本后面、并且已经先经过
 * `sampleData`。所以样本自己开始的位置是 `end - offset - size`，算出来对不上
 * （负数、或者落在已消费区间之前）就只能把缓冲区整个丢掉重来，硬凑会把字节错位成
 * 一条看起来正常、内容却是错的字幕。
 */
private class PreReadTrackOutput(
    private val targetKey: EmbeddedTrackKey,
    val ordinal: Int,
) : TrackOutput {

    /** 这条轨是不是我们要的那条（[format] 里定下来）。 */
    private var wanted = false

    /** 这条轨的身份；[format] 之前是 null。 */
    var key: EmbeddedTrackKey? = null
        private set

    var mimeType: String? = null
        private set

    /** 一共收到多少条样本（排错用）。 */
    var sampleCount = 0
        private set

    val samples = ArrayList<EmbeddedCueSample>()

    /** 解 cue 包用（Media3 转码过的字幕轨走这条）。 */
    private var cueDecoder: CueDecoder? = null

    /** 解原始样本用（没被转码的字幕轨走这条）。 */
    private var subtitleParser: SubtitleParser? = null

    private var subsampleOffsetUs = 0L
    private var scratch = ByteArray(0)

    /** 已消费起点 / 已累积终点。 */
    private var start = 0
    private var end = 0

    override fun format(format: Format) {
        key = embeddedTrackKeyOf(format)
        mimeType = format.sampleMimeType
        wanted = key == targetKey
        subsampleOffsetUs = format.subsampleOffsetUs
        cueDecoder = null
        subtitleParser = null
        if (!wanted) return
        // cue 包：Media3 已经替我们解过一次（`SubtitleTranscodingTrackOutput`），
        // 而且它把真实时长也编码进去了，用公开的 `CueDecoder` 解回来就行。
        if (mimeType?.lowercase() == MEDIA3_CUES_MIME) {
            cueDecoder = CueDecoder()
            return
        }
        // 原始样本：自己解析。`DefaultSubtitleParserFactory` 对不认识的 MIME 会**抛异常**
        // （`create` / `getCueReplacementBehavior` 都是直接 throw），所以必须先问
        // `supportsFormat`——问出来 false 就一条 cue 也读不出来，最后报 NO_CUES。
        val factory = DefaultSubtitleParserFactory()
        subtitleParser = if (factory.supportsFormat(format)) factory.create(format) else null
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (length <= 0) return maxOf(length, 0)
        ensureCapacity(length)
        var read = 0
        while (read < length) {
            // `DataReader.read` 只保证「至少 1 字节」，不保证填满；`TrackOutput` 的契约却是
            // 「要么读满、要么抛」。所以这里必须循环读满，否则样本起点会算错。
            val count = input.read(scratch, if (wanted) end + read else read, length - read)
            if (count == C.RESULT_END_OF_INPUT) {
                if (read == 0 && allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            if (count <= 0) throw EOFException()
            read += count
        }
        if (wanted) end += length
        return length
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (length <= 0) return
        ensureCapacity(length)
        if (wanted) {
            data.readBytes(scratch, end, length)
            end += length
        } else {
            data.skipBytes(length)
        }
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        // 不匹配目标的轨（视频、音频、别的字幕轨）**在这里就返回**：它们的字节照样被
        // 消费掉了（`sampleData` 负责），但样本账从来没开始记——`start`/`end` 只在
        // `wanted` 时才推进（见 `ensureCapacity`）。
        //
        // 这一段检查原来不分轨地执行，于是不带目标的那几条轨每个样本都算出一个负数
        // 并打一行「字节账对不上」。真机实测：一次预读刷出 **4085 行**这种日志，
        // 而它是**失实的**——账根本不是「对不上」，是压根没记（`$start` 恒为 0）。
        // 「刷屏 + 说假话」的日志比没有日志更糟：它会把真正的读取问题埋掉。
        if (!wanted) return
        if (size <= 0) {
            start = 0
            end = 0
            return
        }
        val sampleStart = end - offset - size
        if (sampleStart < start) {
            // 账对不上：把缓冲区丢掉，别拿错位的字节去解析。
            MspLog.d(TAG) {
                "字幕样本字节账对不上（样本起点=$sampleStart 已消费=$start 缓冲末=$end " +
                    "长度=$size 尾随偏移=$offset）"
            }
            start = 0
            end = 0
            return
        }
        sampleCount++
        decode(sampleStart, size, timeUs)
        start = sampleStart + size
        if (start >= end) {
            start = 0
            end = 0
        }
    }

    private fun decode(offset: Int, size: Int, timeUs: Long) {
        val decoder = cueDecoder
        if (decoder != null) {
            val decoded = try {
                // 第一个参数是「这条 cue 包**自己**的起点」。包里没有这个信息
                // （`CueEncoder.encode` 只编码 cue 与时长——见它的 Javadoc），所以传
                // `C.TIME_UNSET`：「这个样本的时刻就是起点，别再叠一个偏移上去」。
                //
                // 🔴 这里曾经传 `timeUs`，看起来更"老实"，实际把同一段时间加了两次：
                // `SubtitleTranscodingTrackOutput.outputSample()` 早就把 cue 的起点折进了
                // 样本时刻（`outputSampleTimeUs = timeUs + cuesWithTiming.startTimeUs`，
                // 正是因为它给下游格式设了 `subsampleOffsetUs = OFFSET_SAMPLE_RELATIVE`），
                // 于是解码出来的 `CuesWithTiming.startTimeUs` 就等于 `timeUs`，再按
                // 「相对样本」那条规则加一遍就成了 `2 × timeUs`。
                //
                // 实测症状：一分钟的片子，播放到 46.88 秒时画面上写着「第 23 秒的台词」。
                // 这种错误**没有任何一处会报错**——表的条数、顺序、单调性全都正常，
                // 只有时刻整体翻倍。所以 [EmbeddedPreReadState.describe] 现在也把时间
                // 范围打出来（只报「30 行」的话，这一版差点带着它发出去）。
                decoder.decode(C.TIME_UNSET, scratch, offset, size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                null
            }
            if (decoded == null) {
                MspLog.d(TAG) { "忽略一个解不开的 cue 包样本" }
                return
            }
            val sample = cuePacketSampleOf(decoded, timeUs) ?: return
            samples += sample
            return
        }
        val parser = subtitleParser ?: return
        try {
            parser.parse(scratch, offset, size, SubtitleParser.OutputOptions.allCues()) { cues ->
                accept(cues, timeUs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            // 单个样本解析失败**不该**让整条轨失败：真片子里坏样本很常见（截断、
            // 非标准前缀、时长缺失）。忽略它继续读，成败最后按「一共读出几条」判断。
            MspLog.d(TAG) { "忽略一个解析失败的字幕样本（${error.message}）" }
        }
    }

    private fun accept(cues: CuesWithTiming, timeUs: Long) {
        val sample = embeddedCueSampleOf(
            cues = cues.cues,
            startTimeUs = cues.startTimeUs,
            durationUs = cues.durationUs,
            sampleTimeUs = timeUs,
            subsampleOffsetUs = subsampleOffsetUs,
        ) ?: return
        samples += sample
    }

    /**
     * 保证 [scratch] 装得下。
     *
     * 上限见 [MAX_SAMPLE_BYTES]。注意这里是**抛**而不是「截断」：截断会让这条轨悄悄少几条
     * 台词，而「表看起来是完整的、其实缺了一段」是最难发现的一类错误。
     */
    private fun ensureCapacity(length: Int) {
        val needed = (if (wanted) end else 0) + length
        if (needed <= scratch.size) return
        if (needed > MAX_SAMPLE_BYTES) throw IOException("字幕样本过大（$needed 字节）")
        scratch = scratch.copyOf(maxOf(DEFAULT_BUFFER_BYTES, needed))
    }
}
