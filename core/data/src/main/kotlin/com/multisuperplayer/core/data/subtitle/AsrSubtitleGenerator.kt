package com.multisuperplayer.core.data.subtitle

import android.net.Uri
import com.multisuperplayer.core.asr.AsrException
import com.multisuperplayer.core.asr.AsrProgress
import com.multisuperplayer.core.asr.AsrSegment
import com.multisuperplayer.core.asr.AsrTranscriber
import com.multisuperplayer.core.asr.CloudAsrTranscriber
import com.multisuperplayer.core.data.settings.AsrJob
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleOrigin
import com.multisuperplayer.core.model.SubtitleTrack
import com.multisuperplayer.core.translate.SubtitleExportFormat
import com.multisuperplayer.core.translate.SubtitleExportMode
import com.multisuperplayer.core.translate.buildExportedSubtitle

/**
 * 生成的字幕在这套流程里的身份：合成 uri + 条数。
 *
 * 用 [uri] 而不是「第几条候选」来指认刚生成的那一条：候选列表是每次扫描现算的，
 * 用下标指认会在「扫描过程中多出/少了一条」时选错人。
 */
data class GeneratedSubtitleRef(
    /** 与 [SubtitleSource.uri] 是同一个值（合成 uri），也是 [SubtitleTrack.id]。 */
    val uri: String,
    val cueCount: Int,
)

/**
 * 语音识别 → 字幕文件。
 *
 * ## 为什么要单独一层，而不是让界面层直接调 [AsrTranscriber]
 *
 * 识别只产出时间轴 + 文本，而播放页要的是**一条能被当成外挂字幕加载的字幕**。
 * 中间那步（序列化成字幕文件、写进私有目录、拿到它的 uri）如果放在界面层，
 * 就会出现「识别一次、界面自己拼一遍字幕格式」的代码——字幕格式一改，导出与
 * 生成两处就会不一致。所以这层负责：识别 → 序列化 → 落盘，只把结果交出去。
 *
 * ## 为什么序列化复用导出的那套函数
 *
 * [buildExportedSubtitle] 已经在被「导出译文」用着，而生成的字幕**必须**能被
 * 自己的解析器读回来。再写一个 SRT 生成器的直接后果是两份实现慢慢不一致
 * （时间格式、多行、空行的处理），而症状是「导出没问题、生成的字幕读不回来」。
 * 没有译文时那套函数会回退原文（见其注释），恰好就是这里要的形态。
 *
 * ## 为什么失败要单独抛 [AsrException.StorageFailed]
 *
 * 「识别出来了但没存下」和「识别失败」对用户的下一步动作完全不同：前者该去清
 * 存储空间（识别已经付过算力的代价，重跑一遍还是存不下），后者才是重试识别。
 * 混成一句话会让用户反复重跑几分钟的识别。
 *
 * ## 一部片子只有一条生成字幕，换引擎重跑会覆盖上一条
 *
 * 文件名是「媒体哈希」，与路线无关（[GeneratedSubtitleStore.uriFor]）。两路各存
 * 一份会在字幕面板里出现两条名字一模一样的候选——而它们的内容只有时间轴切分
 * 不一样，用户没法从界面上分出哪条是哪条。而生成的字幕本来就是**可以再跑一遍**的
 * 东西（不是用户一个字一个字敲出来的），覆盖它不丢用户劳动。
 */
class AsrSubtitleGenerator(
    private val transcriber: AsrTranscriber,
    private val cloud: CloudAsrTranscriber,
    private val store: GeneratedSubtitleStore,
) {

    /**
     * 认识 [job] 说的那条路，把结果落盘。返回新字幕的身份，供调用方在重新扫描后指认它。
     *
     * 识别中途失败不会动旧文件：上一版生成的字幕仍然可用（重跑失败不该让用户
     * 连原来那份也失去）。云端那块也是：第 3 块传失败时前两块的结果**不会**被写出去
     * （要等到全部块都拿到才会存），所以失败后盘上还是上一次那份完整的字幕，
     * 而不是一份只有前 7 分钟的残缺字幕。
     */
    suspend fun generate(
        mediaUri: String,
        job: AsrJob,
        onProgress: (AsrProgress) -> Unit = {},
    ): GeneratedSubtitleRef {
        val generatedUri = store.uriFor(mediaUri)
        val media = Uri.parse(mediaUri)
        val segments = when (job) {
            is AsrJob.OnDevice -> transcriber.transcribe(media, job.model, onProgress)
            is AsrJob.Cloud -> cloud.transcribe(media, job.config, onProgress)
        }
        val text = srtTextOf(generatedUri, segments)
        if (!store.save(generatedUri, text)) {
            throw AsrException.StorageFailed
        }
        return GeneratedSubtitleRef(uri = generatedUri, cueCount = segments.size)
    }
}

/**
 * 字幕段 → SRT 文本。纯函数，可直接单测。
 *
 * [trackId] 传合成 uri：它就是这条字幕在别处的 [SubtitleTrack.id]（扫描回来时
 * `SubtitleRepository` 用的是同一个值）。SRT 本身不存 id，但让两处取同一个值，
 * 以后加「记住用户选了哪条字幕」时不必再解释「为什么这里和那里不一样」。
 */
internal fun srtTextOf(trackId: String, segments: List<AsrSegment>): String = buildExportedSubtitle(
    document = SubtitleDocument(
        track = SubtitleTrack(
            id = trackId,
            origin = SubtitleOrigin.GENERATED_ASR,
            format = SubtitleFormat.SRT,
            cueCount = segments.size,
        ),
        cues = segments.mapIndexed { position, segment ->
            SubtitleCue(
                index = position + 1,
                startMs = segment.startMs,
                endMs = segment.endMs,
                text = segment.text,
            )
        },
    ),
    // 没有译文：导出器会逐行回退到原文（不是留空洞）。
    translations = emptyMap(),
    format = SubtitleExportFormat.SRT,
    mode = SubtitleExportMode.TRANSLATION_ONLY,
)
