package com.multisuperplayer.core.data.update

import android.content.Context
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** 下载进度。[totalBytes] 为 null 时表示服务端没给长度，界面只能转圈不能画进度条。 */data class UpdateProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
) {
    /** 0f..1f；长度未知时为 null（**不要**用它当成 0，那会画出一条永远不动的进度条）。 */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }
            ?.let { (bytesRead.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
}

/**
 * 把发行版里的 APK 下到本地。
 *
 * 三条规矩照抄 `AsrModelDownloader`（那边是模型下载，这里是升级包，风险完全一样：
 * 140 MB 的东西下到一半断了，而用户看不出区别）：
 *
 * 1. **先写 `<name>.part`，校验通过才 `renameTo`**。直接写目标名的话，一次中断会留下
 *    一个「大小差不多、内容不全」的 APK，下次进来看到文件已存在就以为下好了——
 *    安装时才报「解析包时出现问题」，而那时已经没人会想到是下载的锅。
 *    同目录内的 rename 是原子的，所以目标名出现 ⟺ 内容完整。
 * 2. **失败/取消必须删掉 `.part`**。不删的话用户看到的是「占用了几十 MB 却什么都没有」，
 *    而且他会再点一次下载，再占几十 MB。
 * 3. **校验用 sha256**，不用「能不能打开」或者「大小对不对」。大小只是提前发现
 *    拿到错误响应的便宜检查，真正的判据是摘要；而摘要缺失时（源没给）就**不假装**
 *    校验过（见 [UpdateRelease.apkSha256]）。
 *
 * 另外：阻塞 IO **不会**响应协程取消，所以要自己查 [ensureActive]。少了它，
 * 用户退出这一页之后下载还在后台把流量跑完。
 */
class UpdateDownloader(
    context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    /**
     * 落地目录 `cacheDir/updates/`。
     *
     * 必须是 `cacheDir` 下的这一个子目录，不能改：FileProvider 的白名单
     * （`res/xml/update_apk_paths.xml`）只放开了它。改成别处会在调安装器时抛
     * `IllegalArgumentException: Failed to find configured root`。
     *
     * 放 cacheDir 而不是 filesDir：升级包装完就没用了，放在缓存里系统能在空间
     * 吃紧时自己回收，而放在 filesDir 的那 140 MB 只有我们自己记得删。
     */
    private val downloadDir: File
        get() = File(appContext.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 下载 [release] 的 APK。
     *
     * @param onProgress 在主线程之外的调度器上回调（界面侧要自己切回主线程）。
     * @return 已经下载并（在摘要可用时）校验过的文件。
     */
    suspend fun download(
        release: UpdateRelease,
        onProgress: (UpdateProgress) -> Unit = {},
    ): File = withContext(dispatchers.io) {
        val url = release.apkUrl?.takeIf { it.isNotBlank() }
            ?: throw UpdateException.NoAsset(release.tagName)
        val target = File(downloadDir, fileNameFor(release))
        val part = File(downloadDir, "${target.name}.part")

        // 已经下过同一版（比如用户上次点完安装又退回来了）就直接复用，
        // 但**只在摘要对得上时**。源没给摘要时不复用：没法确认那个文件是完整的，
        // 而复用一个坏文件的代价是「安装失败」，比多下一次 140 MB 更贵。
        val expectedSha = release.apkSha256?.lowercase(Locale.ROOT)
        if (target.exists() && expectedSha != null && sha256Of(target) == expectedSha) {
            MspLog.d(TAG) { "已存在校验通过的安装包，跳过下载：${target.name}" }
            return@withContext target
        }

        // 先清残留：上一次中断留下的 `.part` 或者摘要对不上的旧文件。
        part.delete()
        target.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                // GitHub 的资产下载会 302 到 objects.githubusercontent.com，
                // 不跟随重定向的话拿到的是那个 302 的空 body，而 responseCode 是 200 之外的值——
                // 报出来就是「HTTP 302」，看起来像服务端的问题。
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw UpdateException.Http(code, rateLimited = false, remaining = null)
            }

            val total = connection.contentLengthLong.takeIf { it > 0 }
            // 服务端给了长度、且发布信息里也有长度，两者不一致 ⇒ 现在就能判定拿错了东西，
            // 不必等把 140 MB 读完再让摘要失败（失败消息也只是「摘要不符」，查不出真因）。
            val declared = release.apkSizeBytes
            if (total != null && declared != null && total != declared) {
                throw UpdateException.AssetMismatch(declared, total)
            }

            var read = 0L
            var lastReported = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            // 进度回调的节流：141 MB 按 128 KB 一块读是一千多次，每次都推一次
            // Compose 状态会让界面自己变成瓶颈。按 1% 或者 512 KB（取大的那个）报一次。
            val step = total?.let { maxOf(it / 100, PROGRESS_STEP_BYTES) } ?: PROGRESS_STEP_BYTES

            connection.inputStream.use { input ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read += count
                        if (read - lastReported >= step) {
                            lastReported = read
                            onProgress(UpdateProgress(read, total))
                        }
                    }
                    output.flush()
                    // 到磁盘再 rename：不然断电后可能留下一个「名字对、内容在页缓存里」的文件。
                    output.fd.sync()
                }
            }
            onProgress(UpdateProgress(read, total ?: read))

            if (expectedSha != null) {
                val actual = digest.digest().toHex()
                if (actual != expectedSha) {
                    // 摘要不符时**必须**删掉：这是一个内容已经损坏的文件，
                    // 留着它下次会因为「文件已存在」而被当成下好了。
                    part.delete()
                    throw UpdateException.Checksum(expected = expectedSha, actual = actual)
                }
            } else {
                MspLog.w(TAG) { "发布信息没有 sha256，跳过摘要校验（仍有包名+签名校验）" }
            }

            if (!part.renameTo(target)) {
                // rename 失败（目标已存在、跨文件系统）时不能默默返回 `.part`：
                // 调用方拿到一个 `.part` 路径去交给安装器，报错会是「找不到文件」。
                part.delete()
                throw UpdateException.Network(IOException("无法重命名 ${part.name} → ${target.name}"))
            }
            MspLog.d(TAG) { "安装包下载完成：${target.name}（$read 字节）" }

            pruneOthers(target)
            target
        } catch (e: Throwable) {
            // 无论失败还是取消，`.part` 都不能留下。放在这里而不是各个 throw 前面，
            // 是为了让「新加的失败分支」自动也享受这条规矩——漏一条的症状是
            // 用户磁盘上悄悄多出一个几十 MB 的隐名文件。
            part.delete()
            throw downloadFailureOf(e)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 删掉目录里「不是这一次目标」的旧安装包。
     *
     * **只在新包落地之后删**：早一步（下载开始时）就可能删掉用户上一次下好、
     * 正等着去装的那一个——而他在设置页里看到的是「已经下载好了」，点安装却找不到文件。
     *
     * 不做「按时间清理」或定时任务：这个目录只在我们自己下载时增长，
     * 而每次下载成功都会顺手把它清干净，所以它的大小上界就是一个安装包。
     */
    private fun pruneOthers(keep: File) {
        downloadDir.listFiles()?.forEach { file ->
            if (file.name != keep.name) {
                if (file.delete()) MspLog.d(TAG) { "清理旧安装包：${file.name}" }
            }
        }
    }

    /** 文件名里不能带路径分隔符或 `..`：tag 来自网络，不能直接拼进路径。 */
    private fun fileNameFor(release: UpdateRelease): String =
        release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"

    private fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private companion object {
        const val TAG = "UpdateDownloader"
        const val DIR_NAME = "updates"
        const val BUFFER_BYTES = 128 * 1024
        const val PROGRESS_STEP_BYTES = 512L * 1024
        const val CONNECT_TIMEOUT_MS = 15_000
        // 读超时给 60s 而不是 30s：升级包 140 MB，慢速网络下相邻两个 128 KB 块之间
        // 的间隔也可能超过 30 秒，那会让一次本来能成功的下载在大约 70% 处失败。
        const val READ_TIMEOUT_MS = 60_000
        const val USER_AGENT = "MultiSuperPlayer"
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    "%02x".format(byte)
}

/**
 * 把下载阶段抛出的异常归成 [UpdateException]。
 *
 * 存在的唯一理由是**这个组件的失败必须都是 `UpdateException`**。
 * `HttpURLConnection` 会把 `SocketException`（连接被重置）、`UnknownHostException`、
 * 读超时这些**原样**抛出来，它们是 `IOException` 而不是 `UpdateException`——
 * 调用方（ViewModel）只接 `UpdateException`，漏掉一个的后果是**整个应用崩掉**。
 * 真机上发生过一次：`SocketException: Connection reset` 从 `getResponseCode()` 逃到
 * `viewModelScope`，`FATAL EXCEPTION: main`，用户看到的是「应用突然自己关了」。
 *
 * 三条边界，每条都有测试钉住：
 * - `UpdateException` 原样放行（已经分类好的失败，不该被套两层）；
 * - 其它 `IOException` → [UpdateException.Network]，**并保留 cause**（日志要堆栈）；
 * - 既不是 IO 也不是 `UpdateException` 的（`NPE`、`IllegalStateException`）
 *   **原样重抛**：那是编程错误，伪装成「网络问题」只会让它永远查不出来。
 *   协程取消用的 `CancellationException` 走的就是这条路。
 */
internal fun downloadFailureOf(e: Throwable): Throwable =
    if (e is IOException && e !is UpdateException) UpdateException.Network(e) else e

