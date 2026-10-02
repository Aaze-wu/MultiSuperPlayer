package com.multisuperplayer.core.player

import androidx.media3.common.PlaybackException

/**
 * 把 Media3 的错误码翻译成「用户看得懂 + 能据此行动」的文案。
 *
 * 为什么不用 `PlaybackException.localizedMessage`：那是英文、且常常只是
 * 「Source error」这类没有信息量的句子。播放器的头号用户抱怨必然是
 * 「这个文件放不了」，而放不了的原因完全不同（权限 / 解码器 / 容器损坏），
 * 对应完全不同的处理方式，所以这里按错误码分流。
 *
 * 刻意做成**纯函数**（`Int` + 原因名 → `String`）：这样它能进 JVM 单元测试，
 * 不需要起一个 Android 环境或真的造一个坏文件。
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
    ): String {
        val base = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                "找不到该文件，可能已被移动或删除"

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                "没有读取该文件的权限。请重新选择这个文件或文件夹以授予访问权限"

            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                "系统禁止明文 HTTP 播放。请在设置中允许该地址使用不加密连接"

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "无法连接到服务器，请检查网络或播放地址"

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "连接服务器超时，请检查网络"

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "服务器返回了错误状态码，播放地址可能已失效"

            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            ->
                // ⚠️ 这里不能说「去设置里开 FFmpeg 软件解码」——那是 0.3 的旧文案，
                // 当时根本没有那个设置项。现在软件解码默认就在，而且是**自动**回退的，
                // 所以走到这里时它多半已经试过并失败了。
                when (softwareDecoding) {
                    SoftwareDecodingAttempt.UNAVAILABLE ->
                        "本机没有能解码该音视频格式的解码器，而这个安装包不含 FFmpeg 软件解码" +
                            "（可能是 CPU 架构不受支持）"

                    SoftwareDecodingAttempt.FAILED ->
                        "硬件解码和 FFmpeg 软件解码都解不开这个文件。常见于冷门编码，" +
                            "或封装格式本身不被支持（例如 WMV、RealMedia）"

                    SoftwareDecodingAttempt.NOT_TRIED ->
                        "本机没有能解码该音视频格式的解码器（常见于 AC-3/DTS/TrueHD 音轨" +
                            "或特殊编码的 HEVC）。再点一次播放会重试一次"
                }

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            ->
                if (softwareDecoding == SoftwareDecodingAttempt.FAILED) {
                    "解码失败：硬件解码和 FFmpeg 软件解码都试过并失败了。" +
                        "这个文件的编码方式不被支持"
                } else {
                    "解码器初始化失败。若反复出现，通常是这个文件的编码方式不被支持"
                }

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            ->
                "文件（或流清单）已损坏或下载不完整"

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            ->
                "无法识别这个文件/流的封装格式"

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            ->
                "音频输出初始化失败，可能是音频设备被其他应用占用"

            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                "直播进度落后过多，正在重新缓冲"

            PlaybackException.ERROR_CODE_TIMEOUT ->
                "播放超时，请检查网络"

            else ->
                "播放失败"
        }

        // 原因名只在有信息量（不是 generic 的 PlaybackException）时才追加。
        //
        // 这里刻意用字符串字面量而不是 `PlaybackException::class.java.name`：
        // 后者会让本函数在运行时真的去加载那个类，从而把一个纯函数变成
        // 「需要 Media3 类可加载」的东西，JVM 单元测试就得多一层依赖。
        // `when (errorCode)` 里的常量是 `const val`，编译期就内联了，没有这个问题。
        val cause = causeName?.takeIf { it.isNotBlank() && it != GENERIC_ERROR_CLASS }
        return buildString {
            append(base)
            append("（错误码 ")
            append(errorCode)
            if (cause != null) {
                append("，")
                append(cause)
            }
            append("）")
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
