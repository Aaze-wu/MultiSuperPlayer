package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "HttpChatClient"

/**
 * 用 `HttpURLConnection` 直连。
 *
 * ## 为什么不引 OkHttp / Ktor
 *
 * 只有一个 POST 和一个 GET，没有连接池、重定向、拦截器的需求。
 * 为两个请求引入一个几 MB 的网络栈，还得跟着它的版本升级走，
 * 收益是负的。`HttpURLConnection` 在 Android 上是 OkHttp 的包装，够用。
 *
 * ## 线程
 *
 * 这里全是阻塞调用，**必须在 IO 线程上跑**。切线程由 [TranslationEngine]
 * 的 `flowOn(dispatchers.io)` 负责，客户端自己不偷偷切——否则单测里
 * 想换调度器观察行为就会失效。
 */
internal class HttpChatClient(
    private val connectTimeoutMs: Int = ChatCompletionRequest.DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = ChatCompletionRequest.DEFAULT_READ_TIMEOUT_MS,
) : ChatCompletionClient {

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionOutcome {
        val body = buildChatRequestJson(request).toString().toByteArray(Charsets.UTF_8)
        val headers = buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            put("Accept", "application/json")
            request.apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return execute(
            url = request.url,
            method = "POST",
            headers = headers,
            payload = body,
            connectTimeoutMs = request.connectTimeoutMs,
            readTimeoutMs = request.readTimeoutMs,
        ).let { outcome ->
            when (outcome) {
                is RawOutcome.Failed -> ChatCompletionOutcome.Err(outcome.failure)
                is RawOutcome.Success -> parseChatCompletionResponse(outcome.body, request.maxTokens)
            }
        }
    }

    override suspend fun listModels(baseUrl: String, apiKey: String?): ModelListOutcome {
        val headers = buildMap {
            put("Accept", "application/json")
            apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return when (
            val outcome = execute(
                url = modelsUrl(baseUrl),
                method = "GET",
                headers = headers,
                payload = null,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = connectTimeoutMs.coerceAtLeast(10_000),
            )
        ) {
            is RawOutcome.Failed -> ModelListOutcome.Err(outcome.failure)
            is RawOutcome.Success -> parseModelList(outcome.body)
        }
    }

    private sealed interface RawOutcome {
        data class Success(val body: String) : RawOutcome
        data class Failed(val failure: TranslationFailure) : RawOutcome
    }

    private suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        payload: ByteArray?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): RawOutcome = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (payload != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size)
                }
            }

            if (payload != null) {
                connection.outputStream.use { it.write(payload) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (status in 200..299) {
                RawOutcome.Success(body)
            } else {
                val retryAfter = connection.getHeaderField("Retry-After")
                MspLog.w(TAG) { "HTTP $status from ${connection.url}: ${extractProviderMessage(body, 200)}" }
                RawOutcome.Failed(classifyHttpFailure(status, retryAfter, body))
            }
        } catch (error: IOException) {
            MspLog.w(TAG, error) { "IO error calling $url" }
            RawOutcome.Failed(classifyNetworkException(error))
        } finally {
            connection?.disconnect()
        }
    }
}
