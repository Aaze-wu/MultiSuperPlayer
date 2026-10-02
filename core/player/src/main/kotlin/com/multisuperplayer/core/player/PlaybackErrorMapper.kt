package com.multisuperplayer.core.player

import androidx.media3.common.PlaybackException
import com.multisuperplayer.core.common.text.MspText

/**
 * 把 Media3 的错误码翻译成「用户看得懂 + 能据此行动」的文案。
 *
 * 为什么不用 `PlaybackException.localizedMessage`：那是英文、且常常只是
 * 「Source error」这类没有信息量的句子。播放器的头号用户抱怨必然是
 * 「这个文件放不了」，而放不了的原因完全不同（权限 / 解码器 / 容器损坏），
 * 对应完全不同的处理方式，所以这里按错误码分流。
 *
 * 刻意做成**纯函数**（`Int` + 原因名 → [MspText]）：这样它能进 JVM 单元测试，
 * 不需要起一个 Android 环境或真的造一个坏文件。返回 [MspText] 而不是 `String`
 * 也是为了同一件事——取字符串需要 `Resources`，而单测里没有；解析交给
 * UI 边界（见 `MspText` 的类注释）。
 */
object PlaybackErrorMapper {

    /** 兜底文案里带上原始错误码，方便用户截图反馈时定位。 */
    fun describe(
        errorCode: Int,
        causeName: String? = null,
        /**
         * 出错时 FFmpeg 软件解码参与到了什么程度。见 [SoftwareDecodingAttempt]。
         *
         * 它必须是个三态而不是一个布尔值：这三种情况的**下一步动作**完全不同
         * （换设备 / 只能换文件 / 再点一次重试），用 `Boolean` 就会有两态共用一个文案。
         */
        softwareDecoding: SoftwareDecodingAttempt = SoftwareDecodingAttempt.NOT_TRIED,
    ): MspText {
        val base: MspText = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                MspText.Res(R.string.msp_playback_error_file_not_found)

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                MspText.Res(R.string.msp_playback_error_no_permission)

            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                MspText.Res(R.string.msp_playback_error_cleartext)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                MspText.Res(R.string.msp_playback_error_network_failed)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                MspText.Res(R.string.msp_playback_error_network_timeout)

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                MspText.Res(R.string.msp_playback_error_http_status)

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            ->
                // ⚠️ 这里不能说「去设置里开 FFmpeg 软件解码」——那是 0.3 的旧文案，
                // 当时根本没有那个设置项。现在软件解码默认就在，而且是**自动**回退的，
                // 所以走到这里时它多半已经试过并失败了。
                when (softwareDecoding) {
                    SoftwareDecodingAttempt.UNAVAILABLE ->
                        MspText.Res(R.string.msp_playback_error_decoder_unavailable)

                    SoftwareDecodingAttempt.FAILED ->
                        MspText.Res(R.string.msp_playback_error_decoder_software_failed)

                    SoftwareDecodingAttempt.NOT_TRIED ->
                        MspText.Res(R.string.msp_playback_error_decoder_software_not_tried)
                }

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            ->
                if (softwareDecoding == SoftwareDecodingAttempt.FAILED) {
                    MspText.Res(R.string.msp_playback_error_decoder_both_failed)
                } else {
                    MspText.Res(R.string.msp_playback_error_decoder_init)
                }

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            ->
                MspText.Res(R.string.msp_playback_error_container_malformed)

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            ->
                MspText.Res(R.string.msp_playback_error_container_unsupported)

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            ->
                MspText.Res(R.string.msp_playback_error_audio_output)

            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                MspText.Res(R.string.msp_playback_error_live_window)

            PlaybackException.ERROR_CODE_TIMEOUT ->
                MspText.Res(R.string.msp_playback_error_timeout)

            else ->
                MspText.Res(R.string.msp_playback_error_generic)
        }

        // 原因名只在有信息量（不是 generic 的 PlaybackException）时才追加。
        //
        // 这里刻意用字符串字面量而不是 `PlaybackException::class.java.name`：
        // 后者会让本函数在运行时真的去加载那个类，从而把一个纯函数变成
        // 「需要 Media3 类可加载」的东西，JVM 单元测试就得多加一层依赖。
        // `when (errorCode)` 里的常量是 `const val`，编译期就内联了，没有这个问题。
        val cause = causeName?.takeIf { it.isNotBlank() && it != GENERIC_ERROR_CLASS }

        // 括号和分隔符交给文案：中文是全角、英文是半角，而「错误码」三个字本身也要翻译。
        return if (cause == null) {
            MspText.Res(R.string.msp_playback_error_with_code, base, errorCode)
        } else {
            MspText.Res(R.string.msp_playback_error_with_code_and_cause, base, errorCode, cause)
        }
    }

    /** 是否需要提示用户「换内核/换文件」这类无法原地重试的情况。 */
    fun isFormatProblem(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> true

        else -> false
    }

    /** 网络类的失败值得提供「重试」按钮，本地文件类的重试通常没意义。 */
    fun isRetryable(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        -> true

        else -> false
    }

    /** `PlaybackException` 自身的类名——出现它说明没有更具体的原因，不值得显示给用户。 */
    private const val GENERIC_ERROR_CLASS = "androidx.media3.common.PlaybackException"
}
