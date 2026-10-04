package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.model.text.MspText
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

    /**
     * 容器里没写时长，所以没法把音频切成固定长度的块。
     *
     * 本机识别不需要这个信息（它一边解码一边识别），但云端必须先把音频切成
     * 服务商能收的块，而切块要先知道总长。**不能**退化成「当成一整块发出去」：
     * 一部两小时的片子会先生成一个 230 MB 的临时 WAV，多半先把手机存储写满，
     * 上传也必然超过服务商的上限——用户看到的会是「磁盘满」或「音频太大」，
     * 而不是真正的原因（这个文件没有时长信息）。
     */
    data object UnknownDuration : AsrException("media duration is unknown")

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

    // ------------------------------------------------------------------ 云端识别
    //
    // 下面这些类全部是「把音频发给服务商」这条路独有的失败。它们**不能**合并成
    // 一个「云端识别失败」，理由与上面那批完全一样——每一种的下一步动作都不同：
    //
    // | 失败 | 用户该做什么 |
    // |---|---|
    // | [CloudNetwork] | 换个网络 / 稍后再试（这一块没花钱） |
    // | [CloudAuth] | 去设置页填或换 API Key（改这里立刻能好） |
    // | [CloudBlocked] | 换服务商（**不是**密钥问题，改密钥无效） |
    // | [CloudEndpoint] | 去设置页改地址（密钥和模型都没问题） |
    // | [CloudAddress] | 去设置页把地址**填完**（现在连一个合法地址都还不是） |
    // | [CloudRequest] | 换模型或预设（这个组合服务商不接受） |
    // | [CloudTooLarge] | 改用本机识别（切片再切小治不了根因） |
    // | [CloudQuota] | 充值或换服务商（等一等也可能好） |
    // | [CloudServer] | 稍后重试（服务商侧故障，本地做什么都没用） |
    // | [CloudResponse] | 换预设 / 报 bug（服务端返回体不是预期格式） |
    //
    // 合并的后果是具体的：401 和 403 都变成「云端识别失败」，用户会去反复重填密钥，
    // 而 403 是组织策略挡的，改一万次密钥也不会好。429 和 5xx 都变成「重试」，
    // 而一个要等 24 小时、一个要等 5 分钟。404 与 400 归成一类的话，用户会去换模型，
    // 而真正该改的是那个填错的地址。

    /** 连不上服务商（DNS 解析不了、连接超时、被中途掐断）。 */
    class CloudNetwork(cause: Throwable) : AsrException("cloud asr network failure", cause)

    /**
     * 401：服务商拒绝了这次请求——密钥没填、填错、被吊销、或没权限用这个模型。
     *
     * [serviceId] 是预设 id 而不是显示名：显示名要跟着语言变（中/英/繁三种），
     * 而异常可能在任意一种语言下被构造和展示。文案里那个名字由
     * `describeAsrFailure()` 通过 [MspText.Res] 的嵌套参数延迟解析。
     */
    class CloudAuth(val serviceId: String) : AsrException("cloud asr authentication failed ($serviceId)")

    /**
     * 403：服务商按**账户策略**拒绝（组织策略、地区限制、内容策略）。
     *
     * 与 [CloudAuth] 分开的理由是整个类最要紧的一条：403 时密钥可能是完全正确的，
     * 「重填密钥」是错的建议。实测 OpenAI 的 403 只返回 `text/plain`
     * （错误体是纯文本而不是 JSON），所以 [detail] 的解析要容忍非 JSON body。
     */
    class CloudBlocked(val detail: String) : AsrException("cloud asr blocked: $detail")

    /**
     * 404：这个地址上**没有**识别接口。
     *
     * 单独一类而不是并进 [CloudRequest]：那类说「我们发错了」（换模型），
     * 这类说「地址写错了」（改地址）——两个完全相反的动作。而这是本功能里
     * **最可能**发生的一个配置错误：地址栏是可手填的，而多数 OpenAI 兼容服务
     * 要求把路径写到 `/v1`（少写一段就正好是 404）。
     */
    class CloudEndpoint(val code: Int) : AsrException("cloud asr endpoint not found: $code")

    /**
     * 地址根本不是一个能拿去开连接的地址（空、`example.com/v1` 这种漏了协议的、`ftp://`）。
     *
     * 与 [CloudEndpoint] 分开的理由：404 是「地址对得上域名但没有这个接口」——它证明
     * **连接成功过**，用户该做的是补路径；这一档连请求都没发出去，用户该做的是把地址
     * 填完整。只做预设的时候两者都不会出现（预设地址是常量），**这一档只为「自定义」
     * 存在**：那是唯一一个地址由用户手打的地方。
     *
     * 也不能并进 [CloudNetwork]：空的地址拿去开连接会抛 `MalformedURLException`
     * （它是 `IOException` 的子类），并进去就变成「连不上识别服务」——用户会去换网络，
     * 而真正该做的是把地址填完。
     */
    data object CloudAddress : AsrException("cloud asr address is not usable")

    /**
     * 400：请求参数不被服务商接受。
     *
     * 最常见的成因是**模型不支持返回时间轴**（`verbose_json`）：同一个端点上，
     * `whisper-1` 支持而 `gpt-4o-transcribe` 不支持，硅基流动更是连这个字段都不收。
     * 所以文案里必须点出这个可能，否则用户只会看到「参数错误」四个字。
     */
    class CloudRequest(val detail: String) : AsrException("cloud asr bad request: $detail")

    /** 413：这一块音频超出了服务商的体积上限。[bytes] 是实际发出去的字节数。 */
    class CloudTooLarge(val bytes: Long) : AsrException("cloud asr payload too large: $bytes")

    /** 429：额度用完、或被限流（可能带 `Retry-After`）。 */
    class CloudQuota(val detail: String) : AsrException("cloud asr rate limited: $detail")

    /** 5xx：服务商侧故障。本地做什么都没用，只能等。 */
    class CloudServer(val code: Int, val detail: String) : AsrException("cloud asr server $code: $detail")

    /**
     * 200，但内容读不出一句文字（不是 JSON、或者没有 `text` 字段）。
     *
     * 单独一类而不是并进 [CloudRequest]：那一类是「我们发错了」，这一类是
     * 「对面回了个我们看不懂的东西」，两者要用户做的事不同（前者改设置，
     * 后者换预设/报 bug）。而且这一类的典型成因是**中转站自己包了一层**，
     * 把真正的 `text` 藏在别的键下面——那种情况看原文比看推测有用。
     */
    class CloudResponse(val detail: String) : AsrException("cloud asr unreadable response: $detail")
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
    is AsrException.UnknownDuration -> MspText.Res(R.string.msp_asr_error_unknown_duration)
    is AsrException.Decode -> MspText.Res(R.string.msp_asr_error_decode, cause.detailOrSelf())
    is AsrException.Silent -> MspText.Res(R.string.msp_asr_error_no_speech)
    is AsrException.Engine -> MspText.Res(R.string.msp_asr_error_engine, cause.detailOrSelf())
    is AsrException.StorageFailed -> MspText.Res(R.string.msp_asr_error_storage)
    is AsrException.CloudNetwork ->
        MspText.Res(R.string.msp_asr_error_cloud_network, cause.detailOrSelf())
    is AsrException.CloudAuth ->
        MspText.Res(R.string.msp_asr_error_cloud_auth, AsrServices.byId(serviceId).displayName)
    is AsrException.CloudBlocked ->
        MspText.Res(R.string.msp_asr_error_cloud_blocked, detailText(detail))
    is AsrException.CloudEndpoint ->
        MspText.Res(R.string.msp_asr_error_cloud_endpoint, code)
    is AsrException.CloudAddress -> MspText.Res(R.string.msp_asr_error_cloud_address)
    is AsrException.CloudRequest ->
        MspText.Res(R.string.msp_asr_error_cloud_request, detailText(detail))
    is AsrException.CloudTooLarge ->
        MspText.Res(R.string.msp_asr_error_cloud_too_large, TimeFormat.fileSize(bytes))
    is AsrException.CloudQuota ->
        MspText.Res(R.string.msp_asr_error_cloud_quota, detailText(detail))
    is AsrException.CloudServer ->
        MspText.Res(R.string.msp_asr_error_cloud_server, code, detailText(detail))
    is AsrException.CloudResponse ->
        MspText.Res(R.string.msp_asr_error_cloud_response, detailText(detail))
    // IOException 也可能是从没包进上面两类的地方漏出来的（比如模型文件被外部删了）。
    is IOException -> MspText.Res(R.string.msp_asr_error_network, detailOrSelf())
    else -> MspText.Res(R.string.msp_asr_error_unknown, detailOrSelf())
}

/**
 * 服务商错误体的展示用截断。
 *
 * ## 为什么只在这里截断，日志里不截
 *
 * 服务商的错误体可能很长（整段 HTML、几百字的策略说明）。界面上一句话里塞不下，
 * 而一条失败的提示**必须在一屏内读完**，否则用户看到的是一坨被截成两行半的文字。
 *
 * 但截断只发生在**展示**这一步：原始错误体在 [AsrException.CloudBlocked] 这类异常里
 * 原封不动，日志那一侧打的是全文（见 `CloudAsrClient`）。把截断做进解析器、让异常里
 * 存的就是掐头去尾的字符串——那种写法会让「这条失败到底说了什么」永远查不出来，
 * 也就是把这类故障变成「只能重跑一遍再赌」。
 *
 * 换行统一压成空格：截断点之后残留的换行会让一行提示在界面上变成两行。
 */
private fun preview(raw: String, limit: Int = PREVIEW_LIMIT): String {
    val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
    if (flat.length <= limit) return flat
    return flat.take(limit) + "…"
}

/**
 * 服务商给的那句话 → 能嵌进文案里的一个参数。
 *
 * ## 为什么空串要换成一句专门的话
 *
 * `%1$s` 收到空串的结果是「服务商不接受这次请求：。常见原因是……」——一个悬空的冒号。
 * 那不只是难看：用户会以为提示本身坏了，而实际上它是**服务商没给原因**这一个
 * 独立的事实。空串在整条链路上都必须被当成「没有」处理，且只丢掉那一个字段，
 * 不能把整条提示也丢了（错误类型才是「用户该做什么」的来源）。
 */
private fun detailText(detail: String): MspText {
    val flat = preview(detail)
    return if (flat.isEmpty()) {
        MspText.Res(R.string.msp_asr_error_cloud_no_detail)
    } else {
        MspText.Plain(flat)
    }
}

private const val PREVIEW_LIMIT = 160

/**
 * 异常自己的一句话说明，没有就用类名。
 *
 * 为什么不直接 `message`：`UnsatisfiedLinkError`、`CodecException` 这类异常的
 * `message` 常常是空的，然后把「加载失败：（空）」显示给用户——等于没说。
 */
private fun Throwable?.detailOrSelf(): String =
    this?.message?.takeIf { it.isNotBlank() } ?: this?.let { it::class.java.simpleName } ?: "unknown"
