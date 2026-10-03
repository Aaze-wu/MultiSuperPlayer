package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * 把一块 WAV 发给 OpenAI 兼容的 `/audio/transcriptions`，拿回响应体。
 *
 * ## 只做「发一次、拿回应答」
 *
 * 切片、偏移累加、进度、重试都不在这里——这里没有循环，没有状态，也不认识
 * `AsrProgress`。它的全部职责是：**给一个请求，回答一句话或者抛一个分好类的失败**。
 * 这样这一层可以用一个假的 `HttpURLConnection` 之外的任何东西测（见
 * [AsrException] 的分类断言），而切片逻辑不必经过 socket 才能被测到。
 *
 * ## 为什么不用 HTTP 库
 *
 * 与全项目一致（见 README 的依赖说明）：`HttpURLConnection` 是 android.jar
 * 自带的，为一次 multipart POST 引一个 3 MB 的客户端库不划算。
 *
 * ## 超时为什么是 15 秒 / 3 分钟
 *
 * - 连接 15 秒：连接阶段慢到 15 秒，说明这个地址基本不可用，再等只是让用户干看。
 * - 读 3 分钟：一块 5 分钟的音频，主流服务商要 10~60 秒；3 分钟是给长音频 +
 *   慢网络留的余量。**不能**设成「不超时」：那会让一次挂死的请求把界面永远
 *   卡在「识别中」，而用户唯一的出口「取消」其实点了也没用（见下面取消那一节）。
 */
internal class CloudAsrClient(
    private val dispatchers: DispatcherProvider,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {

    /**
     * 发一次请求，返回响应体原文（**不做任何解析**——解析在
     * [parseTranscriptionSegments] 里，那边是纯函数，能单测）。
     *
     * @param onSent 已发送 / 总字节数。上传 9.6 MB 在慢网络上是几十秒的黑屏，
     *   没有这个回调的话用户看不出「在动」。
     */
    suspend fun transcribe(
        request: CloudAsrRequest,
        onSent: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(dispatchers.io) {
        val holder = AtomicReference<HttpURLConnection?>(null)

        // ---------------------------------------------------------------- 取消
        // `HttpURLConnection` 的阻塞读/写**不响应协程取消**：`readTimeout` 到点之前，
        // 那个线程会一直停在 socket 上。没有下面这个看门协程的话，用户点「取消」
        // 之后界面会继续显示「识别中」直到超时——看上去就是按钮坏了。
        // `disconnect()` 会关掉底层 socket，让阻塞中的 read/write 立刻抛 IOException。
        //
        // 用 `launch` + `awaitCancellation` 而不是 `invokeOnCompletion`：后者要等到
        // 协程**结束**才触发，而这里要处理的正是「结束不了」的情况。
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { holder.get()?.disconnect() }
            }
        }
        try {
            execute(request = request, holder = holder, onSent = onSent)
        } finally {
            // 正常完成时把看门协程收掉，否则它会一直挂在那里等一个永远不来的取消。
            watcher.cancel()
        }
    }

    /**
     * 阻塞地跑一次请求。
     *
     * 骨架（`errorStream` 兜底、`instanceFollowRedirects`、`finally` 里 `disconnect`）
     * 与 `core:translate` 的 `HttpChatClient` 一致——那种一致性不是抄，是两者都得
     * 踩过同一批坑（不读 `errorStream` 会让 4xx 的错误体变成「空响应」；不 disconnect
     * 会把连接池占住）。
     */
    private fun execute(
        request: CloudAsrRequest,
        holder: AtomicReference<HttpURLConnection?>,
        onSent: (sent: Long, total: Long) -> Unit,
    ): String {
        // 地址不可用就**不要开连接**：空地址会抛 `MalformedURLException`（`IOException`
        // 的子类），下面那个 catch 会把它报成「连不上识别服务」——用户于是去换网络，
        // 而实际该做的是把地址填完。放在最前面还有一个好处：不白建一个 multipart。
        checkEndpointUsable(request.url)

        val boundary = randomBoundary()
        val body = buildTranscriptionBody(
            boundary = boundary,
            model = request.model,
            audio = request.audio,
            fileName = request.fileName,
            mimeType = request.mimeType,
            supportsTimestamps = request.wantsTimestamps,
        )

        var connection: HttpURLConnection? = null
        try {
            connection = URL(request.url).openConnection() as HttpURLConnection
            holder.set(connection)
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", body.contentType)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            // 密钥为空**不发这个头**：发一个 `Authorization: Bearer `（后面什么都没有）
            // 与完全不发，在有些服务端是两种不同的错误（「密钥无效」vs「缺少密钥」），
            // 而我们的文案要能同时解释两者。
            request.apiKey?.takeIf { it.isNotBlank() }?.let { key ->
                connection.setRequestProperty("Authorization", "Bearer $key")
            }

            // 声明长度而不是 chunked：multipart 的总长是完全可算的（见 [MultipartBody]），
            // 而有些网关/服务商对 `Transfer-Encoding: chunked` 的支持不如定长。
            connection.setFixedLengthStreamingMode(body.contentLength)
            CountingOutputStream(connection.outputStream, body.contentLength, onSent).use { sink ->
                body.writeTo(sink)
            }

            val status = connection.responseCode
            // 4xx/5xx 的错误体在 `errorStream` 里；只读 `inputStream` 的话错误详情
            // 全是「空响应」，等于把服务商给的那句话扔掉。
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status in 200..299) return text

            // 日志打**全文**：截断只在 ui 文案那一步做（见 `preview`）。日志里被截掉的
            // 那半句经常正是「为什么被拒」的原因。
            MspLog.w(TAG) { "云端识别返回 HTTP $status：$text" }
            throw failureFor(
                status = status,
                retryAfter = connection.getHeaderField("Retry-After"),
                body = text,
                request = request,
            )
        } catch (error: IOException) {
            // 取消时看门协程会 disconnect 掉连接，于是这里也会收到一个 IOException。
            // 不特判：外层是协程取消，`withContext` 退出时会把 CancellationException
            // 重新抛出，用户看到的是「已取消」而不是「连不上」。
            throw AsrException.CloudNetwork(error)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    internal companion object {
        /** 建立连接的上限。 */
        const val DEFAULT_CONNECT_TIMEOUT_MS: Int = 15_000

        /**
         * 等待响应的上限。
         *
         * 3 分钟是**有意的**：它同时是「取消能生效的最坏时间」——点取消时看门协程会
         * 立刻掐掉 socket，所以这个值只在没有取消的情况下才真正生效。
         */
        const val DEFAULT_READ_TIMEOUT_MS: Int = 180_000

        private const val USER_AGENT = "MultiSuperPlayer"
    }
}

/**
 * 一边往 socket 写，一边按固定步长汇报进度。
 *
 * 只在**跨过步长**时回调（而不是每次 write）：`copyTo` 用的是 8 KB 缓冲，
 * 9.6 MB 会调用上千次，每次都回调解锁一遍 ui 状态是纯浪费。
 */
private class CountingOutputStream(
    private val delegate: OutputStream,
    private val total: Long,
    private val onSent: (Long, Long) -> Unit,
) : OutputStream() {

    private var sent = 0L
    private var reported = 0L

    override fun write(b: Int) {
        delegate.write(b)
        sent += 1
        report()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        sent += len
        report()
    }

    override fun flush() = delegate.flush()

    override fun close() = delegate.close()

    private fun report() {
        if (sent - reported < REPORT_STEP_BYTES && sent < total) return
        reported = sent
        onSent(sent, total)
    }

    private companion object {
        const val REPORT_STEP_BYTES = 512L * 1024L
    }
}

/**
 * 发请求之前先把地址本身筛一遍。
 *
 * 抽成独立函数只为一件事：**它必须能被单测**。它在 [CloudAsrClient.execute] 里的位置
 * 决定它没法在单测里被触发（那需要真的开一次连接），而这一条恰恰是最容易漏的分支——
 * 只有「自定义预设 + 地址没填完」这一种配置会走到，而那种配置在那台设备上
 * 是 100% 会走到的（用户第一次进设置页就是空的）。
 *
 * @throws AsrException.CloudAddress 地址不是 http/https 开头（含空、含 `example.com/v1`
 *   这种漏了协议的）。**不是** [AsrException.CloudNetwork]：那一条会建议用户去换网络。
 */
internal fun checkEndpointUsable(url: String) {
    if (!isHttpAddress(url)) throw AsrException.CloudAddress
}

/**
 * HTTP 状态码 → 分好类的失败。
 *
 * 分类的**依据是下一步动作**而不是码本身（见 `AsrErrors.kt` 那张表）：
 *
 * - 401 与 403 分开：403 时密钥可能是对的，「去重填密钥」是错的建议；
 * - 404 与 400 分开：404 是地址写错了（改地址），400 是请求形态不对（换模型）；
 * - 429 与 5xx 分开：一个要等额度/限流窗口，一个是服务商自己坏了。
 *
 * 不在表里的 4xx（405/415/422…）归到 [AsrException.CloudRequest]：它们都是
 * 「这次请求的内容/形态对方不收」，用户能做的动作与 400 一样。
 *
 * `internal` 而不是 `private`：这张表是**用户该做什么**的决策表，值得被逐行断言，
 * 而它唯一的输入是一个状态码和一段 body——没有任何网络依赖。
 */
internal fun failureFor(
    status: Int,
    retryAfter: String?,
    body: String,
    request: CloudAsrRequest,
): AsrException {
    val detail = providerMessage(body)
    return when {
        status == 401 -> AsrException.CloudAuth(request.serviceId)
        status == 403 -> AsrException.CloudBlocked(detail)
        status == 404 -> AsrException.CloudEndpoint(status)
        status == 413 -> AsrException.CloudTooLarge(request.audio.length())
        status == 429 -> AsrException.CloudQuota(withRetryAfter(detail, retryAfter))
        status >= 500 -> AsrException.CloudServer(status, detail)
        status in 400..499 -> AsrException.CloudRequest(detail)
        // 1xx/3xx：`instanceFollowRedirects` 已经跟过重定向了，还走到这里说明服务端
        // 在做一些我们理解不了的重定向。归到「服务商侧不正常」比归到「你发错了」准。
        else -> AsrException.CloudServer(status, detail)
    }
}

/**
 * 服务商的错误体 → 一句话。
 *
 * ## 为什么不能只 `JSON.parse(body).error.message`
 *
 * 实测（见 `AsrServices` 的表头）：
 * - **OpenAI 403 只回 `text/plain`**（不是 JSON 对象）；
 * - **硅基流动所有错误体都是裸字符串**（OpenAPI 里错误体的 schema 就是 `type: string`）；
 * - **404 常常是 HTML**（网关自己的错误页），而 `<title>` 恰好是唯一有用的那一句。
 *
 * 所以这里按「JSON 对象 → JSON 字符串 → HTML 标题 → 原文」依次降级，
 * 每一步都可能成功，且**每一步都返回非空**（空串由 `detailText` 换成专门文案）。
 *
 * ## 为什么「读到 JSON 但读不出原因」要返回空串
 *
 * `{"code":4001}` 这种体里没有任何能读给人看的话。把它原样平铺进文案，
 * 用户看到的是「服务商不接受这次请求：{"code":4001}」——既难看又没信息，
 * 而**日志里已经有一份完整的原文**。所以这种情况返回空串，让 `detailText`
 * 换成「服务商没有说明原因」。
 */
internal fun providerMessage(body: String): String {
    val raw = body.trim()
    if (raw.isEmpty()) return ""

    when (val element = CloudAsrJson.parseElement(raw)) {
        is JsonObject -> {
            val error = element["error"]
            val message = (error as? JsonObject)
                ?.get("message").asStringOrNull()
                // `{"error":"Invalid API key"}`：error 直接是字符串。
                ?: error.asStringOrNull()
                ?: element["message"].asStringOrNull()
                // FastAPI / vLLM / 自建网关（Ollama 兼容层）的字段名。
                ?: element["detail"].asStringOrNull()
                ?: element["msg"].asStringOrNull()
            return if (message.isNullOrBlank()) "" else flatten(message)
        }
        // 裸 JSON 字符串：`"Invalid token"`
        is JsonPrimitive -> {
            val message = element.asStringOrNull()
            return if (message.isNullOrBlank()) "" else flatten(message)
        }
        // 数组形状的错误体（`[{"message":…}]`）：同样读不出「原因」这两个字。
        is JsonArray -> return ""
        // 不是 JSON（HTML 错误页、纯文本）——交给下面按文本处理。
        null -> Unit
    }

    // HTML 错误页：`<title>` 那一句就是全部有用信息（"404 Not Found" / "403 Forbidden"）。
    // 直接返回整页 HTML 的话，界面上的提示会变成一坨标签。
    if (raw.startsWith("<")) {
        HTML_TITLE.find(raw)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return flatten("$it（服务商返回了网页而不是接口响应）") }
        return flatten(raw.replace(HTML_TAG, " "))
    }

    return flatten(raw)
}

private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/**
 * 把换行/连续空白压成一个空格，并留一个上限。
 *
 * 这里的上限（[MESSAGE_LIMIT]）与展示层的 `preview()`（160 字）**不是同一件事**：
 * 这一层管的是「异常对象里别揣着一整页 HTML 到处传」，展示层管的是「界面上那一行
 * 能放多少字」。两层各自有理由，所以它们各自有一个数，不互相引用。
 *
 * 但这不代表可以在这里把原因截成废信息——日志里记的是完整的 body
 * （见 `CloudAsrClient.execute`），所以原文不会丢。
 */
private fun flatten(raw: String, limit: Int = MESSAGE_LIMIT): String {
    val flat = raw.replace(WHITESPACE, " ").trim()
    return if (flat.length <= limit) flat else flat.take(limit) + "…"
}

/**
 * 把 `Retry-After` 拼进详情。
 *
 * 单独拼而不是给 [AsrException.CloudQuota] 加一个字段：这个头的格式有秒数和
 * HTTP 日期两种，我们不需要解析它——用户要的是「等多久」，而服务商自己写的那句话
 * 通常已经含了。把它附在原文后面，比我们自己换算成「约 3 分钟」更不容易说错。
 */
private fun withRetryAfter(detail: String, retryAfter: String?): String {
    val retry = retryAfter?.trim().orEmpty()
    if (retry.isEmpty()) return detail
    val note = "Retry-After: $retry"
    return if (detail.isEmpty()) note else "$detail（$note）"
}

private val WHITESPACE = Regex("\\s+")
private val HTML_TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val HTML_TAG = Regex("<[^>]*>")

private const val MESSAGE_LIMIT = 300

private const val TAG = "AsrCloud"
