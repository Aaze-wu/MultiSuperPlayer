package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.log.MspLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToLong

/**
 * 一次云端识别请求需要知道的全部东西。
 *
 * 刻意做成一个**扁平的数据包**而不是让 transcriber 自己去读设置：`core:asr`
 * 不认识 `DataStore`，也不该认识（它连 `core:data` 都看不到——依赖方向是反的）。
 * 密钥、地址、模型名都由 `core:data` 装配好之后传进来，好处是这一层**没有状态**
 * 可查，测试里把 [apiKey] 换掉就能验证「没填密钥时到底发不发 Authorization 头」。
 *
 * @param serviceId 只用来把 401 说成「XXX 拒绝了这次请求」。
 *   存 id 而不是显示名：显示名要跟着语言变，而 [AsrException.CloudAuth] 可能
 *   在设置页的三种语言下分别被构造出来。
 * @param wantsTimestamps 见 [AsrService.supportsSegments]。
 * @param audio 已经导好的 16 kHz 单声道 16 bit WAV。
 */
data class CloudAsrRequest(
    val url: String,
    val apiKey: String?,
    val model: String,
    val serviceId: String,
    val wantsTimestamps: Boolean,
    val audio: File,
    val fileName: String = "audio.wav",
    val mimeType: String = "audio/wav",
)

/**
 * `POST /audio/transcriptions` 的 multipart 请求体。
 *
 * ## 为什么手写 boundary
 *
 * 项目里没有 HTTP 库（见 README 的依赖说明），`HttpURLConnection` 也不提供
 * multipart 支持。所以这一段的每一处都直接对应 RFC 7578 的一句规定。
 *
 * ## 为什么是「先算长度、再写字节」而不是直接把东西拼成一个 ByteArray
 *
 * 拼成 `ByteArray` 要先把 9.6 MB 的音频读进内存，再拷一份到请求体里（约 20 MB 峰值），
 * 而这份内存的用途只是「交给 socket」。所以这里把每个部分描述出来，
 * [contentLength] 与 [writeTo] **共用同一份描述**——这是关键：
 *
 * `HttpURLConnection.setFixedLengthStreamingMode(n)` 声明的长度与实际写入的字节数
 * **必须逐字节相等**，否则写入端会抛 `ProtocolException: too many bytes written`
 * 或请求体被截断（**少了不报错**，服务商只看到半个音频，然后回一句「音频损坏」）。
 * 只要长度和字节来自同一个地方，这类错就不可能发生。
 */
internal class MultipartBody(boundary: String, parts: List<Part>) {

    /** 一个部分：头部只算一次，[contentLength] 与 [writeTo] 用的是同一份字节。 */
    private class Resolved(
        val header: ByteArray,
        val bodyLength: Long,
        val body: (OutputStream) -> Unit,
    )

    /** 一个部分的**完整**字节范围（含它的前导 `--boundary` 行与结尾 CRLF）。 */
    internal sealed interface Part {
        /** 说明这一部分的头部。`boundary` 由外层传进来，而不是让每个部分自己记一份。 */
        fun headerBytes(boundary: String): ByteArray

        /** 主体长度。 */
        fun bodyLength(): Long

        /** 把主体写出去（头部由 [MultipartBody.writeTo] 负责）。 */
        fun writeBodyTo(out: OutputStream)
    }

    /**
     * 一个普通的文本字段。
     *
     * 头部从这里**派生**而不是调用方额外传一份：`name = "model"` 与
     * `Content-Disposition: form-data; name="model"` 是两个可以不一致的地方，
     * 而它们不一致的后果是服务商说「你没给 model 参数」——完全看不出真实原因。
     */
    internal class TextPart(private val name: String, private val value: String) : Part {
        override fun headerBytes(boundary: String): ByteArray =
            partHeader(boundary, "form-data; name=\"$name\"")

        override fun bodyLength(): Long = value.toByteArray(Charsets.UTF_8).size.toLong()
        override fun writeBodyTo(out: OutputStream) = out.write(value.toByteArray(Charsets.UTF_8))
    }

    /**
     * 文件字段。
     *
     * 不把文件读进内存：`writeBodyTo` 是一条流到流的拷贝。
     */
    internal class FilePart(
        private val name: String,
        private val fileName: String,
        private val mimeType: String,
        private val source: File,
    ) : Part {
        override fun headerBytes(boundary: String): ByteArray = partHeader(
            boundary = boundary,
            disposition = "form-data; name=\"$name\"; filename=\"$fileName\"",
            extra = "Content-Type: $mimeType",
        )

        override fun bodyLength(): Long = source.length()
        override fun writeBodyTo(out: OutputStream) {
            // `copyTo` 返回拷贝的字节数，写成表达式体的话这个 override 会变成
            // 「返回 Long」——接口声明是 Unit，编译不过。用块体，顺手把这个返回值丢掉。
            source.inputStream().use { it.copyTo(out) }
        }
    }

    private val resolved: List<Resolved> = parts.map {
        Resolved(header = it.headerBytes(boundary), bodyLength = it.bodyLength(), body = it::writeBodyTo)
    }

    /**
     * 结尾 boundary。
     *
     * 结尾是 `--B--` 而**不是** `--B`：RFC 7578 用一个额外的 `--` 表示「没有更多部分了」。
     * 漏掉它的话，服务商读到的是一份被截断的 multipart——报错内容通常是「no file found」，
     * 而文件明明就在里面。
     */
    private val epilogue: ByteArray = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    val contentType: String = "multipart/form-data; boundary=$boundary"

    /** 正好等于 [writeTo] 会写出去的字节数。 */
    val contentLength: Long = resolved.sumOf { it.header.size + it.bodyLength + CRLF.size } +
        epilogue.size

    fun writeTo(out: OutputStream) {
        resolved.forEach { part ->
            out.write(part.header)
            part.body(out)
            // 每个部分（含最后一个）之后都有一个 CRLF。因为「空行」也是正文的一部分，
            // 少一个换行就会让服务商把下一个 boundary 当成字段内容。
            out.write(CRLF)
        }
        out.write(epilogue)
    }

    internal companion object {
        private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)
    }
}

/**
 * 按 [supportsTimestamps] 组装请求体。
 *
 * ## 为什么字段是「按需」而不是固定一套
 *
 * 硅基流动的请求体在官方文档里**只有 `file` 和 `model`**，响应里**只有 `text`**。
 * 给它多发一个 `response_format` 不是「多一点信息」，而是多一个 400 的机会——
 * 严格校验字段的服务端会直接拒收。所以能力标记驱动字段集合，而不是「都发上，
 * 不支持的服务商会忽略」：那条经验在 OpenAI 兼容端点上是**不成立的**。
 *
 * ## 为什么这一版不发 `language`
 *
 * `language` 在 OpenAI/Groq 上都支持、也能显著提升短音频的准确率，但它同样是
 * 「多发一个字段」。而在没有界面开关之前，我唯一能填的值只能从别处猜（比如字幕
 * 语言偏好），猜错的后果是**整段识别成另一种语言**——比不发这个字段差得多。
 * 要发的前提是先有一个用户能看见、能改的「音频语言」设置。
 *
 * Boundary 由调用方给（[randomBoundary]），好让测试里钉死一个值。
 */
internal fun buildTranscriptionBody(
    boundary: String,
    model: String,
    audio: File,
    fileName: String,
    mimeType: String,
    supportsTimestamps: Boolean,
): MultipartBody {
    val parts = mutableListOf<MultipartBody.Part>()

    // OpenAI 官方 curl 示例里就是 `-F model="whisper-1"`，字段名固定小写。
    parts += MultipartBody.TextPart(name = "model", value = model)

    if (supportsTimestamps) {
        // `verbose_json` 是唯一带 `segments`（每句起止时间）的响应格式；
        // 而 `timestamp_granularities[]` 只在 verbose_json 下才会被采纳。
        // 两个一起发才是「我要每句的时间」。
        parts += MultipartBody.TextPart(name = "response_format", value = "verbose_json")
        // 字段名带 `[]`：这是 OpenAPI 里 array 型的表单字段名，官方 curl 就是这么写的，
        // 写成 `timestamp_granularities` 会得到 400「你给的不是数组」。
        parts += MultipartBody.TextPart(name = "timestamp_granularities[]", value = "segment")
    }

    parts += MultipartBody.FilePart(
        name = "file",
        fileName = fileName,
        mimeType = mimeType,
        source = audio,
    )

    return MultipartBody(boundary, parts)
}

/**
 * 一个部分的头部。
 *
 * 每行都以 CRLF 结尾、最后多一个空行——这个空行是「头部结束、正文开始」的分界。
 * 用 `\n` 而不是 `\r\n` 的后果很隐蔽：多数服务端能容忍，但**边界处**会把
 * 多余的 `\r` 当成正文的第一个字节，于是文件开头多一个字节、长度校验失败。
 */
private fun partHeader(boundary: String, disposition: String, extra: String? = null): ByteArray {
    val builder = StringBuilder()
    builder.append("--").append(boundary).append("\r\n")
    builder.append("Content-Disposition: ").append(disposition).append("\r\n")
    if (extra != null) builder.append(extra).append("\r\n")
    builder.append("\r\n")
    return builder.toString().toByteArray(Charsets.UTF_8)
}

/**
 * 随机 boundary。
 *
 * 只用 `[A-Za-z0-9]`：boundary 出现在正文里就要被当作分隔符，所以它必须
 * **不可能**是正文的一部分。字母数字的 32 位随机串在实践中够用，而且不需要
 * 引号（RFC 允许的 `token` 字符集里没有 `"`，带引号的 boundary 会被某些
 * 服务端当成值的一部分）。
 */
internal fun randomBoundary(): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val random = java.util.Random()
    return buildString {
        append("----msp")
        repeat(32) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
}

/**
 * 把 `/audio/transcriptions` 的响应体解成字幕段。
 *
 * ## 两种响应形态
 *
 * - **有多句时间轴**（OpenAI / Groq 的 `verbose_json`）：
 *   `{"segments":[{"start":0.0,"end":3.3,"text":" …"}], "text":"…"}`
 * - **只有整段文字**（硅基流动的 `{text}`、或用不支持 verbose_json 的模型）：
 *   `{"text":"…"}`
 *
 * 第二种不是错误，是**这家服务商的能力就这样**——所以回退成「整块一条字幕」
 * （用户决策 #4），而不是抛异常。这也正是 [AsrService.supportsSegments]
 * 存在的价值：让「拿不到时间轴」变成一个预期内的分支而不是一次失败。
 *
 * ## 时间单位是**秒的浮点**
 *
 * `start` / `end` 都是秒（`3.319999933242798`），要 `round(x * 1000)` 转成毫秒
 * 再加上这一块的偏移。直接当毫秒用的话，3.3 秒会变成 3 毫秒——所有字幕挤在开头，
 * 看起来像「识别全错了」。
 *
 * ## 每一段的 `text` 都带前导空格
 *
 * 官方示例里是 `" The beach was a popular spot…"`（刻意保留的句首空格）。
 * 直接拿去显示就是每行前面一个空格。所以统一走 [AsrTextNormalizer.normalize]——
 * 与**本机识别那条路用的是同一个函数**：两条路的清洗规则必须一致，
 * 否则同一个文件用两种引擎会得到风格不同的字幕。
 *
 * @param chunkStartMs 这一块在整条音轨里的起始毫秒。
 * @param chunkEndMs 这一块**实际**覆盖到的结束毫秒（由写出的样本数算出来，
 *   不是「起点 + 5 分钟」——最后一块通常更短，用名义值会让最后一条字幕拖到片子外面）。
 */
internal fun parseTranscriptionSegments(
    body: String,
    chunkStartMs: Long,
    chunkEndMs: Long,
): List<AsrSegment> {
    val root = runCatching { CloudAsrJson.parse(body) }.getOrNull()
    if (root == null) {
        // 不是 JSON。**不要**退回「把整段 body 当文字」：200 状态配上错误页 HTML
        // 在中转站上很常见，那样做会生成一条内容为 `<html>…` 的字幕。
        throw AsrException.CloudResponse(snippet(body))
    }

    val segments = root["segments"] as? JsonArray
    if (segments != null) {
        val parsed = segments.mapNotNull { it.toSegment(chunkStartMs) }
        if (parsed.isNotEmpty()) {
            if (parsed.size < segments.size) {
                // 「找到了但一部分读不出来」和「一条都没有」是两种不同的信号，
                // 不能共用一个日志：前者说明服务商的字段名/类型和预期不完全一样
                // （时间轴会缺几条），后者说明这条路根本不通（要回退成整块一条）。
                MspLog.w(TAG) { "时间轴部分解析失败：${parsed.size}/${segments.size} 条可用" }
            }
            return parsed
        }
        MspLog.w(TAG) { "返回了 segments 但一条都读不出来，回退成整块一条" }
    }

    // 回退路径：整段音频 = 一条字幕。
    val rawText = root["text"].textOrEmpty()
    val text = AsrTextNormalizer.normalize(rawText)
    if (text.isNotEmpty()) return listOf(AsrSegment(startMs = chunkStartMs, endMs = chunkEndMs, text = text))

    // 有 text 但清洗完是空的（纯标点、`<unk>`）是**正常的**——这一段里没有人说话。
    // 与「响应里根本没有 text」分开：后者是服务商返回体不对，要报出来。
    if (rawText.isNotBlank()) {
        MspLog.d(TAG) { "这一块清洗后没有可显示的内容，跳过" }
        return emptyList()
    }
    throw AsrException.CloudResponse(snippet(body))
}

/**
 * 一条 `segments[]` 项 → [AsrSegment]；读不出时间或文本就返回 null。
 *
 * 「跳过读不出来的那一条」而不是「整块失败」：服务商偶尔会在末尾多塞一条
 * `{"id":-1,"start":null,…}` 的哨兵项，为它丢掉前面几十条正确的时间轴不值得。
 * 但跳过会被**计数**（见 [parseTranscriptionSegments]），所以不会静默。
 */
private fun JsonElement.toSegment(chunkStartMs: Long): AsrSegment? {
    val item = this as? JsonObject ?: return null
    val startSeconds = item["start"].numberOrNull() ?: return null
    val endSeconds = item["end"].numberOrNull() ?: return null
    val text = AsrTextNormalizer.normalize(item["text"].textOrEmpty())
    if (text.isEmpty()) return null

    val startMs = chunkStartMs + (startSeconds * 1000.0).roundToLong()
    val rawEndMs = chunkStartMs + (endSeconds * 1000.0).roundToLong()
    // 零长度（甚至倒序）的时间轴会让这条字幕在播放器里**永远匹配不上**——
    // 它既不会报错也不会显示，表现成「少了一句」。给它一个能看见的最短时长。
    val endMs = if (rawEndMs > startMs) rawEndMs else startMs + MIN_CUE_MS
    return AsrSegment(startMs = startMs, endMs = endMs, text = text)
}

private fun JsonElement?.textOrEmpty(): String =
    (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/**
 * 取一个数字。
 *
 * 刻意**不**叫 `doubleOrNull`：`kotlinx.serialization.json` 已经给 `JsonPrimitive`
 * 定义了同名扩展，而重名会让「这里调的是哪一个」取决于接收者类型的特异性——
 * 一旦解析到自己的那个就是无限递归（`StackOverflowError`），且编译器不会提醒。
 */
private fun JsonElement?.numberOrNull(): Double? = when (this) {
    null -> null
    is JsonPrimitive -> doubleOrNull
    else -> null
}

/** 零长度字幕的最小可见时长。 */
private const val MIN_CUE_MS: Long = 400L

/** 日志/文案里的响应体预览长度（这里只是给日志看，不截断异常里的原文）。 */
private const val SNIPPET_LIMIT = 400

private fun snippet(body: String): String {
    val flat = body.replace('\r', ' ').replace('\n', ' ').trim()
    return if (flat.isEmpty()) "(空响应)" else flat.take(SNIPPET_LIMIT)
}

private const val TAG = "AsrCloud"

/**
 * 云端响应专用的 JSON 解析器。
 *
 * `isLenient`：中转站/自建部署常常回一两个不合规的转义。让它直接解析失败
 * 等于把一次**已经付过钱**的识别结果丢掉。
 *
 * 不用 `@Serializable`：字段名由服务商决定，且同一个含义在不同家叫法不同
 * （`text` 一定有，`segments`/`words` 不一定）。强类型映射会因为一个缺字段
 * 把整个响应判死，而我们要的是「尽量取值」。
 */
internal object CloudAsrJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        explicitNulls = false
    }

    /** 解析成任意 JSON 值；解不开（HTML、纯文本）返回 null。 */
    fun parseElement(body: String): JsonElement? =
        runCatching { json.parseToJsonElement(body.trim()) }.getOrNull()

    /** 解析成对象；不是对象（数组、裸值）返回 null。 */
    fun parse(body: String): JsonObject? = parseElement(body) as? JsonObject
}
