package com.multisuperplayer.core.player

import androidx.media3.common.PlaybackException
import com.multisuperplayer.core.model.text.MspText

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

    /**
     * 兜底文案里带上原始错误码，方便用户截图反馈时定位。
     *
     * @param causeNames 整条异常链的**类名**（从外到内，见 [causeNames]）。
     *
     * 为什么是「一串类名」而不是当初那个 `causeName: String?`（只取最外层原因的
     * 简单类名）：那样只能拿到一个名字，而这个名字恰恰常常是**最没有信息量的那个**。
     * 实测一个自签名证书的 HTTPS 源失败时，链是
     * `PlaybackException → q5.u（HttpDataSourceException，已被 R8 混淆）→\n     * SSLHandshakeException → CertificateException → CertPathValidatorException`，
     * 取最外层就得到 `u`——用户看到的是「无法连接到服务器（错误码 2001，u）」，
     * 既说错了成因，又像是乱码。
     *
     * 顺带一提，这里**只能是字符串**、不能改成收 `Throwable`：收 `Throwable` 就得在
     * 单测里构造 `PlaybackException`，纯函数就变成了「依赖 Media3 类可加载」，
     * 整个文件当初就是为了躲开这件事才只收 `Int` 和字符串（见类注释）。
     */
    fun describe(
        errorCode: Int,
        causeNames: List<String> = emptyList(),
        /**
         * 出错时 FFmpeg 软件解码参与到了什么程度。见 [SoftwareDecodingAttempt]。
         *
         * 它必须是个三态而不是一个布尔值：这三种情况的**下一步动作**完全不同
         * （换设备 / 只能换文件 / 再点一次重试），用 `Boolean` 就会有两态共用一个文案。
         */
        softwareDecoding: SoftwareDecodingAttempt = SoftwareDecodingAttempt.NOT_TRIED,
    ): MspText {
        // 「证书不被信任」必须先于错误码判断。
        //
        // 握手失败会被 HTTP 栈折成 `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`
        // （自签名证书实测就是这条），于是「网络不通」和「证书不认识」共用了一句
        // 文案——而两者的下一步动作完全相反：前者去查网络/Wi-Fi，后者去确认服务器证书
        // （自建服务器可以打开「信任不受信任的证书」）。一句文案被两种相反的成因共用，
        // 就一定有一边在说谎，这一条现在说的是后者。
        val certificateFailure = isCertificateFailure(causeNames)

        val base: MspText = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                MspText.Res(R.string.msp_playback_error_file_not_found)

            PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                MspText.Res(R.string.msp_playback_error_no_permission)

            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                MspText.Res(R.string.msp_playback_error_cleartext)

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                if (certificateFailure) {
                    MspText.Res(R.string.msp_playback_error_certificate)
                } else {
                    MspText.Res(R.string.msp_playback_error_network_failed)
                }

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

        // 原因名只在有信息量时才追加。
        //
        // 这里刻意用字符串字面量而不是 `PlaybackException::class.java.name`：
        // 后者会让本函数在运行时真的去加载那个类，从而把一个纯函数变成
        // 「需要 Media3 类可加载」的东西，JVM 单元测试就得多加一层依赖。
        // `when (errorCode)` 里的常量是 `const val`，编译期就内联了，没有这个问题。
        //
        // 证书这一支不再追加原因名：文案已经把「该去做什么」说完了，而链上第一个
        // 能读的类名只会是 `SSLHandshakeException` 这类英文类名，纯属噪音。
        val cause = if (certificateFailure) {
            null
        } else {
            causeNames.firstNotNullOfOrNull(::informativeCauseName)
        }

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

    /**
     * 网络类的失败值得提供「重试」按钮，本地文件类的重试通常没意义。
     *
     * 证书失败也走「网络类」：证书判断在 [describe] 里单独成句，但这个按钮管的是
     * 「再点一次会不会可能成功」——用户先去设置里打开信任开关，回来正好点它重试。
     */
    fun isRetryable(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        -> true

        else -> false
    }

    /**
     * 整条异常链的类名，从最外层到最内层。
     *
     * 刻意**只取类名、不取消息**：消息里可能带服务器地址、本地路径等隐私，
     * 而这里拿到的字符串会被写进给用户看的文案。
     *
     * 收 `Throwable?` 不会破坏「纯函数」这点：它在 JVM 单测里可以用
     * `RuntimeException(IOException(...))` 这类标准库异常直接构造，不需要 Android、
     * 也不需要 Media3 的实例。
     */
    fun causeNames(error: Throwable?): List<String> {
        val names = mutableListOf<String>()
        var current = error
        var depth = 0
        // 自引用（`cause === this`）会死循环，链过深说明是别人拼出来的怪东西：两种都截断。
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            names.add(current::class.java.name)
            val next = current.cause
            if (next === current) break
            current = next
            depth++
        }
        return names
    }

    /**
     * 这条链里有没有「服务器证书不被信任」的痕迹。
     *
     * 判据是**证书类异常出现在链上**，而不是「看到 `SSLHandshakeException` 就算」：
     * 握手失败的原因还有协议版本对不上、套件不兼容这些，那类问题去信任证书是**治不了的**，
     * 把它们也叫「证书不受信任」等于把用户引到一条走不通的路上。
     *
     * 按**包前缀**匹配 `java.security.cert.*` 而不是逐个列类名：`CertificateException`、
     * `CertPathValidatorException`、`CertificateExpiredException`……一个包里的子类全是
     * 「证书本身有问题」，漏列一个就会退化成语义错误的「网络不通」。
     * `javax.net.ssl.SSLPeerUnverifiedException` 是主机名和证书对不上（同样要靠
     * 放宽校验才能放），所以也归到这一支。
     */
    fun isCertificateFailure(causeNames: List<String>): Boolean = causeNames.any { name ->
        name.startsWith(CERTIFICATE_PACKAGE_PREFIX) || name == HOSTNAME_MISMATCH_CLASS
    }

    /**
     * 从链上挑一个**值得给用户看**的原因名（简单类名）。
     *
     * 两条过滤规则都由实测踩出来：
     * - R8 会把第三方库的类名混淆成单个字母（上面那个 `u`），显示出来就是乱码；
     * - `PlaybackException` 是「没有更具体原因」的包装层，它自己的名字零信息量。
     */
    private fun informativeCauseName(className: String): String? {
        if (className.isBlank()) return null
        val simple = className.substringAfterLast('.')
        if (simple.length < MIN_INFORMATIVE_NAME_LENGTH) return null
        if (simple == GENERIC_ERROR_SIMPLE_NAME) return null
        return simple
    }

    /**
     * `PlaybackException` 自身的类名——出现它说明没有更具体的原因，不值得显示给用户。
     *
     * 用**简单类名**比较：release 构建里 Media3 会被重打包/混淆（实测整族类都在 `q5.*`），
     * 拿全限定名比就再也过滤不掉。
     */
    private const val GENERIC_ERROR_SIMPLE_NAME = "PlaybackException"

    /** `java.security.cert` 下全是「证书本身有问题」，见 [isCertificateFailure]。 */
    private const val CERTIFICATE_PACKAGE_PREFIX = "java.security.cert."

    /** 证书里的主机名和请求的地址对不上。 */
    private const val HOSTNAME_MISMATCH_CLASS = "javax.net.ssl.SSLPeerUnverifiedException"

    /** 短于它的类名基本可以断定是混淆产物（实测 `u`），见 [informativeCauseName]。 */
    private const val MIN_INFORMATIVE_NAME_LENGTH = 2

    /** 异常链的遍历上限，防自引用和病态长链。 */
    private const val MAX_CAUSE_DEPTH = 16
}
