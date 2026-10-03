package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText
import java.io.IOException

/**
 * 模型下载与安装过程中的失败。
 *
 * ## 为什么是四个类而不是一个「下载失败」
 *
 * 这四种失败的**下一步动作完全不同**：
 *
 * | 失败 | 用户该做什么 |
 * |---|---|
 * | [Network] | 换个网络再试，等一会儿 |
 * | [Http] | 换一个下载源（这个源上没有这个文件 / 被拦了） |
 * | [SizeMismatch] / [HashMismatch] | 重试；反复失败就换源（镜像站内容不对） |
 * | [Write] | 清存储空间（手机满了） |
 *
 * 合并成一句话（「下载失败」）的结果是：用户看着「下载失败」反复点重试，
 * 而实际原因是磁盘满了——重试永远不会成功。
 */
sealed class AsrModelException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 连不上、超时、连接中断。 */
    class Network(cause: Throwable) : AsrModelException("model download network failure", cause)

    /** 服务器返回了非 200。 */
    class Http(val code: Int) : AsrModelException("model download http $code")

    /**
     * 下载源地址本身就是错的（少了协议、多了空格、拼错了）。
     *
     * 与 [Network] 分开：网络问题要「等一等再试」，地址写错了**等多久都不会好**。
     */
    class BadSource(val url: String) : AsrModelException("model source url is invalid: $url")

    /** 下下来的字节数和清单里的对不上。 */
    class SizeMismatch(val fileName: String, val expected: Long, val actual: Long) :
        AsrModelException("model file $fileName size $actual != $expected")

    /** 大小对得上但内容不对（镜像站有旧版本 / 传了个错误页）。 */
    class HashMismatch(val fileName: String) : AsrModelException("model file $fileName checksum mismatch")

    /** 写本地文件失败（磁盘满、权限、重命名失败）。 */
    class Write(cause: Throwable) : AsrModelException("model file write failure", cause)
}

/**
 * 生成字幕过程中的失败。
 *
 * 这里的 [Silent] 与 [Decode] 分开的理由同 [AsrModelException]：
 * 「这条片子里没人说话」是**正常结果**（用户该去选另一条片子而不是重试），
 * 「音频解不开」是**能力问题**（这个容器的音频编码我不支持，该告诉作者）。
 * 合成一句「生成失败」，两种情况都会变成「用户反复重试同一个必然失败的操作」。
 */
sealed class AsrException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 选中的模型还没下好。 */
    class ModelMissing(val model: AsrModelInfo) : AsrException("asr model ${model.id} is not installed")

    /** 文件里没有可用的音轨（纯视频、或者音轨是加密的）。 */
    data object NoAudioTrack : AsrException("no usable audio track")

    /** 音频解码失败（不支持的编码、损坏的文件）。 */
    class Decode(cause: Throwable) : AsrException("audio decode failure", cause)

    /** 从头到尾没识别出一句人话。 */
    data object Silent : AsrException("no speech detected")

    /**
     * 引擎这一侧坏了：加载时（模型文件损坏、ABI 不对、原生库加载不了）
     * 或识别中途（原生层抛出的任意 Throwable）。
     */
    class Engine(cause: Throwable) : AsrException("engine failure", cause)

    /**
     * 字幕已经识别出来了，但没能存下来（磁盘满、私有目录不可写）。
     *
     * 单独一个类型而不是并进 [Engine]：这时**算力已经花完了**，重跑一遍识别
     * 还是存不下（而重跑要几分钟）。用户的下一步是去清存储空间，「再试一次」
     * 在这里是错的建议。
     */
    data object StorageFailed : AsrException("generated subtitle could not be saved")
}

/**
 * 把失败翻成给人看的一句话。
 *
 * 结构化取值（`when` 到具体的类）而不是看消息文本：消息文本是给日志看的，
 * 界面文案要能被翻译成三种语言。
 *
 * 最后那个兜底是必须的：生成字幕会穿过 MediaCodec、JNI、文件系统，
 * 任何一个都可能抛出没预料到的异常类型。没有兜底就会把异常直接抛到 UI 线程。
 *
 * 对界面层公开：**只有这一处**把失败翻成文案，界面模块不该再写一份自己的映射
 * （两份映射会慢慢分岔，而分岔的表现是「同一个错在不同页面说法不一样」）。
 */
fun Throwable.describeAsrFailure(): MspText = when (this) {
    is AsrModelException.Network -> MspText.Res(R.string.msp_asr_error_network, cause.detailOrSelf())
    is AsrModelException.Http -> MspText.Res(R.string.msp_asr_error_http, code)
    is AsrModelException.BadSource -> MspText.Res(R.string.msp_asr_error_source, url)
    is AsrModelException.SizeMismatch -> MspText.Res(
        R.string.msp_asr_error_size,
        fileName,
        TimeFormat.fileSize(expected),
        TimeFormat.fileSize(actual),
    )
    is AsrModelException.HashMismatch -> MspText.Res(R.string.msp_asr_error_hash, fileName)
    is AsrModelException.Write -> MspText.Res(R.string.msp_asr_error_write, cause.detailOrSelf())
    is AsrException.ModelMissing ->
        MspText.Res(R.string.msp_asr_error_model_missing, model.name, model.sizeText())
    is AsrException.NoAudioTrack -> MspText.Res(R.string.msp_asr_error_no_audio)
    is AsrException.Decode -> MspText.Res(R.string.msp_asr_error_decode, cause.detailOrSelf())
    is AsrException.Silent -> MspText.Res(R.string.msp_asr_error_no_speech)
    is AsrException.Engine -> MspText.Res(R.string.msp_asr_error_engine, cause.detailOrSelf())
    is AsrException.StorageFailed -> MspText.Res(R.string.msp_asr_error_storage)
    // IOException 也可能是从没包进上面两类的地方漏出来的（比如模型文件被外部删了）。
    is IOException -> MspText.Res(R.string.msp_asr_error_network, detailOrSelf())
    else -> MspText.Res(R.string.msp_asr_error_unknown, detailOrSelf())
}

/**
 * 异常自己的一句话说明，没有就用类名。
 *
 * 为什么不直接 `message`：`UnsatisfiedLinkError`、`CodecException` 这类异常的
 * `message` 常常是空的，然后把「加载失败：（空）」显示给用户——等于没说。
 */
private fun Throwable?.detailOrSelf(): String =
    this?.message?.takeIf { it.isNotBlank() } ?: this?.let { it::class.java.simpleName } ?: "unknown"
