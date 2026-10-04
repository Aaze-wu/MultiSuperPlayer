package com.multisuperplayer.core.llm

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.model.text.MspText
import java.io.IOException

/**
 * 下载/安装模型时的失败。
 *
 * ## 为什么要分成这么几档，而不是一个「下载失败」
 *
 * 因为**下一步动作完全不同**：
 *
 * | 失败 | 用户该做的 |
 * |---|---|
 * | [Network] | 等一会儿重试，或换个网络 |
 * | [Http] | 换下载源（404/403 是地址或权限问题） |
 * | [BadSource] | 改设置里的下载源（少写了 https://、多了个空格） |
 * | [SizeMismatch] / [HashMismatch] | 重试；反复如此就换源（镜像站给了旧版本或损坏文件） |
 * | [Write] | 清手机存储（写不进去） |
 *
 * 把它们压成一句「下载失败」，用户能做的只有「再点一次」——而其中的
 * [BadSource] 那一档再点一百次也不会好。
 */
sealed class LlmModelException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** 连不上、超时、传输中断。 */
    class Network(cause: Throwable) : LlmModelException("network failure", cause)

    /** 服务端返回了非 200。 */
    class Http(val code: Int) : LlmModelException("http $code")

    /** 下载源本身不合法（解析不出 URL）。 */
    class BadSource(val url: String) : LlmModelException("bad source: $url")

    /** 收到的字节数和清单对不上。 */
    class SizeMismatch(val fileName: String, val expected: Long, val actual: Long) :
        LlmModelException("size mismatch for $fileName: expected $expected, got $actual")

    /** 大小对得上但内容不对（镜像旧版本 / 传输中被改写）。 */
    class HashMismatch(val fileName: String) : LlmModelException("hash mismatch for $fileName")

    /** 写本地文件失败（没有空间、权限）。 */
    class Write(cause: Throwable) : LlmModelException("write failure", cause)
}

/**
 * 把上面这族异常变成界面上的一句话。资源见 `msp_llm_error_*`。
 *
 * ⚠️ 这里**只有下载/安装**的失败。推理期的失败（模型没下载、引擎起不来、
 * 这次生成挂了）不走异常，而是 [LlmTextGenerator] 用返回值报出来的——原因见那个
 * 接口的 KDoc：调用方在另一个模块里，它不该为了接一个错误而认识本模块的异常类型。
 *
 * 与 `describeAsrFailure` 同一套做法：每个变体一条资源，`is IOException` 与
 * `else` 兜底。刻意**不**在一个 `when` 里拼字符串——那是把界面语言写进 core 层。
 */
fun Throwable.describeLlmFailure(): MspText = when (this) {
    is LlmModelException.Network -> MspText.Res(R.string.msp_llm_error_network, detailOrSelf())
    is LlmModelException.Http -> MspText.Res(R.string.msp_llm_error_http, code)
    is LlmModelException.BadSource -> MspText.Res(R.string.msp_llm_error_source, url)
    // 体积走 TimeFormat 而不是直接塞字节数：`预期 344671744` 这种话没人读得出来，
    // 而它恰恰是用户唯一能拿去和「存储空间不足」对照的数字。
    is LlmModelException.SizeMismatch -> MspText.Res(
        R.string.msp_llm_error_size,
        TimeFormat.fileSize(expected),
        TimeFormat.fileSize(actual),
    )
    is LlmModelException.HashMismatch -> MspText.Res(R.string.msp_llm_error_hash, fileName)
    is LlmModelException.Write -> MspText.Res(R.string.msp_llm_error_write, detailOrSelf())
    is IOException -> MspText.Res(R.string.msp_llm_error_network, detailOrSelf())
    else -> MspText.Res(R.string.msp_llm_error_unknown, detailOrSelf())
}

/**
 * 异常的「一句话原文」。
 *
 * 系统异常（`UnknownHostException: api…`）自带 message，那些信息量最大，直接透出去；
 * 没有 message 的（比如 `SocketTimeoutException` 的空构造）就用类名，
 * 至少能让人搜到那一类问题。绝不返回空串——界面上会出现一句「：」结尾的怪话。
 */
private fun Throwable?.detailOrSelf(): String =
    this?.message?.takeIf { it.isNotBlank() } ?: this?.let { it::class.java.simpleName } ?: "unknown"
