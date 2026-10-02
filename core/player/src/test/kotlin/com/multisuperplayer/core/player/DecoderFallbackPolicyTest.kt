package com.multisuperplayer.core.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解码失败之后「要不要换 FFmpeg 再试一次」的判定。
 *
 * 这两个方向写错都很隐蔽，所以这里穷举：
 *
 * - **该退却不退**：表现为「装了 FFmpeg 但那个文件还是放不了」，日志里只有一条
 *   普通播放失败，看起来和没做这个功能一模一样；
 * - **不该退却退**：表现为**无限循环**——每次 `prepare()` 失败都再切一次 FFmpeg、
 *   再 `prepare()` 一次，界面上就是转圈转到天荒地老。它在正常文件上完全看不出来。
 */
class DecoderFallbackPolicyTest {

    private fun decide(
        errorCode: Int,
        alreadyRetried: Boolean = false,
        ffmpegAvailable: Boolean = true,
        holdsNonFfmpegDecoder: Boolean = true,
    ): DecoderFallbackDecision = DecoderFallbackPolicy.decide(
        errorCode = errorCode,
        alreadyRetried = alreadyRetried,
        ffmpegAvailable = ffmpegAvailable,
        holdsNonFfmpegDecoder = holdsNonFfmpegDecoder,
    )

    @Test
    fun `硬件解码不支持时改用 FFmpeg`() {
        assertEquals(
            DecoderFallbackDecision.RETRY_WITH_FFMPEG,
            decide(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED),
        )
    }

    @Test
    fun `能力不足时改用 FFmpeg`() {
        // 「本机解码器支持这个格式但参数超标」（比如 4K HEVC 只有 1080p 解码器）
        // 是软件解码最典型的适用场景，不能漏。
        assertEquals(
            DecoderFallbackDecision.RETRY_WITH_FFMPEG,
            decide(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES),
        )
    }

    @Test
    fun `每一个解码类错误码都值得重试`() {
        // 用**集合**断言而不是逐个 `when`：以后 Media3 加了新的解码类错误码时，
        // 有人把它加进 isDecoderFailure 却忘了同步这里的期望，这条会红。
        val decoderCodes = listOf(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
        )

        decoderCodes.forEach { code ->
            assertEquals(
                "错误码 $code 应该可以回退到 FFmpeg",
                DecoderFallbackDecision.RETRY_WITH_FFMPEG,
                decide(code),
            )
        }
    }

    @Test
    fun `同一条媒体只回退一次`() {
        // 第二次失败时 FFmpeg 已经在用了，再退一次就是必然失败的无限重试。
        assertEquals(
            DecoderFallbackDecision.REPORT,
            decide(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                alreadyRetried = true,
            ),
        )
    }

    @Test
    fun `本包没有 FFmpeg 时不假装回退`() {
        // 产生一个「黑一下再失败」的假动作，比直接报错更让人困惑。
        assertEquals(
            DecoderFallbackDecision.REPORT,
            decide(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                ffmpegAvailable = false,
            ),
        )
    }

    @Test
    fun `两路都已经在用 FFmpeg 时不再回退`() {
        // 「切到 FFmpeg」这句话在已经全是 FFmpeg 时等于什么都没做，
        // 于是「失败 → 切换 → prepare → 再失败」会一直循环下去。
        assertEquals(
            DecoderFallbackDecision.REPORT,
            decide(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                holdsNonFfmpegDecoder = false,
            ),
        )
    }

    @Test
    fun `封装格式不认识时不回退`() {
        // nextlib 提供的是**解码器**而不是**解封装器**：容器读不出来时换成
        // FFmpeg 解码器一点用都没有。复用 `PlaybackErrorMapper.isFormatProblem`
        // 会把这个错误也带进来——那是这个功能最容易犯的一个错，
        // 因为「格式问题」这个词本身就模糊。
        val parsingCodes = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )

        parsingCodes.forEach { code ->
            assertEquals(
                "错误码 $code 换解码器没用，不该回退",
                DecoderFallbackDecision.REPORT,
                decide(code),
            )
        }

        // 反证：这些码在 `isFormatProblem` 里**是**被当成格式问题的，
        // 所以本测试确实在区分两套集合，而不是「反正都返回 REPORT」。
        assertTrue(
            "封装不支持应当仍被 isFormatProblem 认为是格式问题",
            PlaybackErrorMapper.isFormatProblem(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            ),
        )
    }

    @Test
    fun `非解码类的错误不回退`() {
        val otherCodes = listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            987_654,
        )

        otherCodes.forEach { code ->
            assertEquals(
                "错误码 $code 与解码器无关，不该回退",
                DecoderFallbackDecision.REPORT,
                decide(code),
            )
        }
    }
}
