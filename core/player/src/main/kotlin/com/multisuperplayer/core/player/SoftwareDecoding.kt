package com.multisuperplayer.core.player

import androidx.media3.common.PlaybackException
import com.multisuperplayer.core.common.log.MspLog
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

private const val TAG = "SoftwareDecoding"

/**
 * 「这个安装包里到底有没有 FFmpeg 软件解码」。
 *
 * ## 为什么需要单独一个接口，而不是直接调 `FfmpegLibrary.isAvailable()`
 *
 * 因为它**不是一个可以永远回答 true 的问题**。原生库是按 CPU 架构分包打进 APK 的
 * （本项目只打 `arm64-v8a` + `x86_64`），所以下面两种情况会得到 false：
 *
 * - 装在一个只支持 32 位 ABI 的老设备上；
 * - 我们自己把某个 ABI 从 `abiFilters` 里去掉，忘了别处还依赖它。
 *
 * 这两种情况下如果代码若无其事地走「启用软件解码」分支，症状会是
 * **一个 `UnsatisfiedLinkError` 崩溃**，或者更糟——设置里那个开关亮着但什么都没发生。
 * 所以「能不能用」必须先问清楚，再决定开关显示成什么样。
 *
 * 抽成接口是为了让 `:feature:settings` 不必依赖 nextlib 就能渲染这一行。
 */
interface SoftwareDecoderSupport {

    /** 本安装包在**当前设备**上能不能加载 FFmpeg 解码器。 */
    val available: Boolean

    /**
     * FFmpeg 版本号，例如 `"9.0.1"`；[available] 为 false 时是 null。
     *
     * 显示出来是为了让「这个包到底有没有带 FFmpeg」变成用户能自己核实的事实，
     * 而不是一句「我们的播放器支持全格式」的宣传语。
     */
    val version: String?

    companion object {
        /**
         * 一个永远回答「没有」的实现，给单测和「不需要软件解码」的场景用。
         *
         * 有它之后 [ExoPlayerController] 的构造参数可以不必是可空的——
         * 「没有 FFmpeg」是一种**正常状态**，不该用 null 表示。
         */
        val Unavailable: SoftwareDecoderSupport = object : SoftwareDecoderSupport {
            override val available: Boolean = false
            override val version: String? = null
        }
    }
}

/**
 * 真正去问 nextlib。
 *
 * `isAvailable()` / `getVersion()` 都会走到 JNI，架构不匹配时抛的是
 * `UnsatisfiedLinkError`（`Error` 而不是 `Exception`，`catch (e: Exception)` 抓不到）。
 * 所以这里全部包 `runCatching`（它连 `Error` 一起抓），并且**只在这里**允许这样兜底：
 * 探测本身失败不是一个需要用户处理的问题，答案就是「不可用」。
 *
 * 用 `by lazy` 记住结论：`System.loadLibrary` 只值得做一次，而且重复失败会拖慢
 * 每一次状态刷新（[ExoPlayerController.publish] 会读它）。
 */
internal class NextlibSoftwareDecoderSupport : SoftwareDecoderSupport {

    override val available: Boolean by lazy {
        runCatching { FfmpegLibrary.isAvailable() }
            .onFailure { error -> MspLog.w(TAG, error) { "FFmpeg 解码器不可用" } }
            .getOrDefault(false)
    }

    override val version: String? by lazy {
        if (!available) {
            null
        } else {
            runCatching { FfmpegLibrary.getVersion() }
                .getOrNull()
                // 读不出名字不算致命，返回 null 让界面只说「已可用」而不是编一个版本号。
                ?.takeIf { it.isNotBlank() }
        }
    }
}

/**
 * 出错时 FFmpeg 软件解码参与到了什么程度。
 *
 * 三态而不是布尔值：[PlaybackErrorMapper] 要对这三种情况给三句**下一步动作不同**的话。
 * 归成一个 `boolean softwareRetried` 的话，「本包没带 FFmpeg」（只能换设备）和
 * 「试过但还是不行」（只能换文件）会共用一句文案，而它们的建议恰好相反。
 */
enum class SoftwareDecodingAttempt {
    /** 这一次尝试里 FFmpeg 没有参与（例如失败发生在选解码器之前）。 */
    NOT_TRIED,

    /** FFmpeg 参与了这次尝试，并且同样失败。 */
    FAILED,

    /** 本安装包没带 FFmpeg（缺对应 CPU 架构的原生库）。 */
    UNAVAILABLE,
}

/** nextlib 的 `DecoderMode.AUTO` 解析出来的实际解码器类型。 */
internal fun DecoderMode.toMspKind(): MspDecoderKind = when (this) {
    DecoderMode.HARDWARE -> MspDecoderKind.HARDWARE
    DecoderMode.SOFTWARE -> MspDecoderKind.SYSTEM_SOFTWARE
    DecoderMode.FFMPEG -> MspDecoderKind.FFMPEG
    // `AUTO` 是「还没决定」而不是「一种解码器」。`activeVideoMode`/`activeAudioMode`
    // 报告的是**已经在用**的那一个，理论上不会是 AUTO；真出现了就当未知处理，
    // 不要硬塞进「硬件」——那会让界面上的结论变成假的。
    DecoderMode.AUTO -> MspDecoderKind.UNKNOWN
}

/**
 * 解码失败之后做什么。
 *
 * ## 为什么值得单独做成纯函数
 *
 * 这段逻辑写错的两个方向都很隐蔽：
 *
 * - **该回退却不回退**：表现为「装了 FFmpeg 但那个文件还是放不了」，而日志里
 *   只有一条普通的播放失败，看起来和没做这个功能一模一样；
 * - **不该回退却回退**：表现为**无限循环**——每次 `prepare()` 失败都再切一次
 *   FFmpeg、再 `prepare()` 一次。UI 上就是播放键按下去转圈转到天荒地老。
 *
 * 后者尤其危险，因为它在正常文件上完全看不出来。所以把决定抽出来穷举测试，
 * 而不是埋在 `onPlayerError` 里靠读代码确认。
 */
internal enum class DecoderFallbackDecision {
    /** 改用 FFmpeg 软件解码，并在原位置续播一次。 */
    RETRY_WITH_FFMPEG,

    /** 原样把错误显示给用户。 */
    REPORT,
}

internal object DecoderFallbackPolicy {

    /**
     * 值得让 FFmpeg 再试一次的 Media3 错误码。
     *
     * ⚠️ 刻意**不含** `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` /
     * `ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED`（它们在 `PlaybackErrorMapper`
     * 里和上面的解码码一起被归为「格式问题」）。
     *
     * 原因：nextlib 提供的是**解码器**，不是**解封装器**。容器读不出来时换成
     * FFmpeg 解码器一点用都没有，只会白白多一次「黑一下再失败」。
     * 复用 `isFormatProblem` 就会把这个错误也带进来，是很容易犯的错。
     */
    private val DECODER_ERROR_CODES = setOf(
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
    )

    fun isDecoderFailure(errorCode: Int): Boolean = errorCode in DECODER_ERROR_CODES

    /**
     * @param errorCode Media3 的 `PlaybackException.errorCode`。
     * @param alreadyRetried **这一条媒体**是否已经因为解码失败回退过一次。
     *   用「每条媒体一次」而不是「整个进程一次」：一个列表里第 3 条是 AC-3、
     *   第 7 条是 DTS 都很正常，绑定成进程级会让后面的文件白白放弃。
     * @param ffmpegAvailable 本安装包有没有 FFmpeg（见 [SoftwareDecoderSupport]）。
     * @param holdsNonFfmpegDecoder 当前还有至少一路在用非 FFmpeg 解码器。
     *   两路都已经是 FFmpeg 时再「切到 FFmpeg」就等于什么都没做，直接报告错误，
     *   否则就是一个必然失败的无限重试。
     */
    fun decide(
        errorCode: Int,
        alreadyRetried: Boolean,
        ffmpegAvailable: Boolean,
        holdsNonFfmpegDecoder: Boolean,
    ): DecoderFallbackDecision = when {
        !isDecoderFailure(errorCode) -> DecoderFallbackDecision.REPORT
        alreadyRetried -> DecoderFallbackDecision.REPORT
        !ffmpegAvailable -> DecoderFallbackDecision.REPORT
        !holdsNonFfmpegDecoder -> DecoderFallbackDecision.REPORT
        else -> DecoderFallbackDecision.RETRY_WITH_FFMPEG
    }
}
