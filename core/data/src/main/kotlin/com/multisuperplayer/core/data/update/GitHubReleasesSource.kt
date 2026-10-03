package com.multisuperplayer.core.data.update

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 从 GitHub Releases 读候选版本。
 *
 * 用 `HttpURLConnection` 直连，与项目里其他联网代码同一套做法（见 README 的依赖说明：
 * 没有 OkHttp/Retrofit）。JSON 用 `kotlinx.serialization` 的 `JsonElement` API
 * 手取字段，**不用 `@Serializable`**：那需要额外的编译器插件，而这里只读一层
 * 固定结构，取字段的代码本来就没几行。
 *
 * ## 三条不能省的东西
 *
 * 1. **`Accept: application/vnd.github+json`**。不带它也能用（GitHub 默认就是这个），
 *    但带上之后返回的就是稳定格式，不会在某天悄悄换成网页版 HTML——
 *    那种失败会表现为「解析出来空列表」，也就是「已是最新版本」，一句看不出问题的假话。
 * 2. **`User-Agent`**。GitHub API **要求**它，缺了直接 403。报错信息是
 *    「API rate limit exceeded」，与真正的原因只差一个词，很难查。
 * 3. **分页只取第一页但显式写 `per_page`**。默认 30 条，本项目发布节奏下足够；
 *    不写的话默认值哪天变了，取回的条数也跟着变。真到了 30 条不够那天，
 *    症状是「很旧的版本查不到」，而那时早就该换成按 tag 精确查询了。
 *
 * [configProvider] 是**每次请求前**取一次配置，而不是构造时取一次：令牌是用户在
 * 设置页里填的，而数据源是应用级单例。在构造时读一次的话，用户填完令牌点「检查更新」
 * 仍然走未认证的额度——他会以为自己的令牌无效，而界面没有任何东西能说明这一点。
 */
class GitHubReleasesSource(
    private val dispatchers: DispatcherProvider,
    private val configProvider: suspend () -> UpdateSourceConfig,
) : UpdateSource {

    override suspend fun listReleases(): List<UpdateRelease> = withContext(dispatchers.io) {
        val config = configProvider()
        val url = URL(
            "https://api.github.com/repos/${config.repository}/releases?per_page=$PAGE_SIZE",
        )
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", USER_AGENT)
                // 服务端 gzip 的话，读到的是压缩流，`readBytes()` 拿到的就不是 JSON——
                // 报错会变成「解析失败」，而真因是协议协商。直接声明不要压缩。
                setRequestProperty("Accept-Encoding", "identity")
                val token = config.token?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 403 与 429 在这里几乎总是限流。必须与「网络不通」分开说：
                // 前者的解法是「过一会儿再试，或填一个 token」，后者是「检查网络」。
                val rateLimited = code == HTTP_FORBIDDEN || code == HTTP_TOO_MANY_REQUESTS
                MspLog.w(TAG) { "GitHub Releases 返回 $code（限流=$rateLimited）" }
                throw UpdateException.Http(code, rateLimited, remainingText(connection))
            }

            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            parseGitHubReleases(body)
        } catch (e: UpdateException) {
            throw e
        } catch (e: IOException) {
            throw UpdateException.Network(e)
        } finally {
            connection?.disconnect()
        }
    }

    /** 限流时的剩余额度，只用于日志与提示语，解析失败就算了。 */
    private fun remainingText(connection: HttpURLConnection): String? =
        connection.getHeaderField("X-RateLimit-Remaining")

    private companion object {
        const val TAG = "UpdateSource"
        const val PAGE_SIZE = 30
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "MultiSuperPlayer"
        const val HTTP_FORBIDDEN = 403
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/**
 * 把 GitHub 的 `/releases` 响应变成 [UpdateRelease] 列表。
 *
 * 抽成顶层 internal 函数是为了能在 JVM 单测里直接钉住它——这一段是整个更新链路里
 * 最容易被上游改动打坏、又最难在真机上复现的地方（要恰好有一个新版本发布才能试）。
 *
 * **跳过而不是失败**：任何一条读不懂（tag 格式怪、没有 APK、结构不对）都只是
 * 少一个候选，不会让整次检查失败。反过来把第一个异常抛出去，就等于「仓库里
 * 只要有人发过一个奇怪的 release，这个功能就永远坏掉」。
 */
internal fun parseGitHubReleases(json: String): List<UpdateRelease> {
    val root = try {
        Json.parseToJsonElement(json)
    } catch (e: Exception) {
        MspLog.w("UpdateSource") { "发行版列表解析失败：${e.message}" }
        return emptyList()
    }
    val array = root as? JsonArray ?: return emptyList()
    return array.mapNotNull { it.toUpdateRelease() }
}

private fun kotlinx.serialization.json.JsonElement.toUpdateRelease(): UpdateRelease? {
    val obj = this as? JsonObject ?: return null
    // 草稿是「还没发布」的，连作者自己都不该在应用里看到它。
    if (obj.booleanField("draft") == true) return null

    val tag = obj.stringField("tag_name")?.trim().orEmpty()
    if (tag.isEmpty()) return null
    val version = UpdateVersion.parse(tag) ?: run {
        MspLog.w("UpdateSource") { "跳过解析不了版本号的 tag：$tag" }
        return null
    }

    val apk = (obj["assets"] as? JsonArray)?.firstNotNullOfOrNull { it.toApkAsset() }

    return UpdateRelease(
        tagName = tag,
        version = version,
        isPreRelease = obj.booleanField("prerelease") == true,
        publishedAtEpochMs = obj.stringField("published_at")?.let(::parseInstantMs),
        notes = obj.stringField("body"),
        apkUrl = apk?.url,
        apkSizeBytes = apk?.size,
        apkSha256 = apk?.sha256,
    )
}

private data class ApkAsset(val url: String, val size: Long?, val sha256: String?)

/**
 * 从一个资产里挑出 APK。
 *
 * 判据是**文件名后缀**而不是 `content_type`：`content_type` 是上传时浏览器给的，
 * 用 `curl` 上传会变成 `application/octet-stream`，于是「按类型找 APK」会在换一种
 * 上传方式后突然找不到——而它返回的是 null，界面上的表现是「有新版本但没有下载
 * 地址」，即一句没有出路的话。
 */
private fun kotlinx.serialization.json.JsonElement.toApkAsset(): ApkAsset? {
    val obj = this as? JsonObject ?: return null
    val name = obj.stringField("name").orEmpty()
    if (!name.endsWith(".apk", ignoreCase = true)) return null
    val url = obj.stringField("browser_download_url")?.trim().orEmpty()
    if (url.isEmpty()) return null
    return ApkAsset(
        url = url,
        size = obj.longField("size")?.takeIf { it > 0 },
        // `digest` 形如 `sha256:abc…`；没提供这个字段的旧响应里它是 null。
        // 它**只是加分项**，真正的安全边界是下载后的「包名 + 签名」校验
        // （见 UpdateApkVerifier）：一个能改 Release 的攻击者也能改 digest。
        sha256 = obj.stringField("digest")?.substringAfter(':', "")?.trim()?.takeIf { it.isNotEmpty() },
    )
}

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.longField(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

/** `2026-10-03T15:04:50Z` → epoch 毫秒；解析不了返回 null（时间只用来显示）。 */
internal fun parseInstantMs(text: String): Long? = try {
    Instant.parse(text).toEpochMilli()
} catch (e: DateTimeParseException) {
    null
}

/**
 * 检查更新这条路上的失败。
 *
 * [rateLimited] 单独拎出来是因为它的**解法和其他所有失败都不一样**：网络错误让用户
 * 去查网络，限流要告诉他「过一会儿再试，或者在下面填一个 GitHub 令牌」。
 * 把这两句合成一句「检查失败」的后果是用户反复点重试、每次都失败，
 * 而他永远不知道只要等一小时就好。
 */
sealed class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 网络层失败（DNS、超时、连接被拒）。 */
    class Network(cause: Throwable) : UpdateException("检查更新失败：网络不可达", cause)

    /** HTTP 非 200。 */
    class Http(val code: Int, val rateLimited: Boolean, val remaining: String?) :
        UpdateException("检查更新失败：HTTP $code")

    /** 这一条发行版没有可下载的安装包（界面不该走到这里：规则层已经滤掉了）。 */
    class NoAsset(val tag: String) : UpdateException("$tag 没有可下载的安装包")

    /**
     * 服务端给的内容长度和发布信息里的长度不一致。
     *
     * 与 [Checksum] **分开**：一个说的是「拿到的不是那个资产」（可能是错误页、
     * 可能是 CDN 返回了别的东西），另一个说的是「字节数对了但内容变了」。
     * 混成一句话会让排查方向反掉。
     */
    class AssetMismatch(val expectedBytes: Long, val actualBytes: Long) :
        UpdateException("安装包大小不符（期望 $expectedBytes，实际 $actualBytes）")

    /** 下载完成但摘要不符。内容已经确定损坏，`.part` 已被删掉。 */
    class Checksum(val expected: String, val actual: String) :
        UpdateException("安装包校验失败（摘要不符）")

    /** 下载完成、摘要也通过，但包本身不能被接受（见 [UpdateApkVerifier]）。 */
    class RejectedApk(val verification: ApkVerification.Rejected) :
        UpdateException("安装包被拒绝：${verification.reason}")
}
