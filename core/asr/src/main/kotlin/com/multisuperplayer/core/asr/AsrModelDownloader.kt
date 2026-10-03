package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * 把一个 URL 上的模型文件下到本地。
 *
 * 它只管「URL → 本地文件」，**不拼 URL**：地址怎么拼（镜像站前缀、仓库名、分支）
 * 是清单和设置的事（见 [AsrModelInfo.downloadUrl]），混在一起后「下载失败」到底
 * 是地址错了还是网络不通就分不出来——而这两句话要告诉用户的事情完全不同。
 *
 * 只用 `HttpURLConnection`，和项目里其他联网代码同一套做法（没有 OkHttp/Retrofit）。
 *
 * ## 三条必须遵守的规则
 *
 * 1. **先写 `.part`，校验通过后才改名**。直接写目标文件名的话，中途被杀掉会留下一个
 *    「大小不对的正式文件」——而 [AsrModelLocator] 是按大小判断状态的，于是它会把这个
 *    半个文件当成「下好了」，直到加载模型时才崩（而且崩在 native 里，看不到原因）。
 * 2. **失败必须删掉 `.part`**。181 MB 的残file 会一直占着用户的空间，而界面上显示的
 *    却是「未下载」——用户找不到这 181 MB 去哪了。
 * 3. **校验用哈希，不用「能不能打开」**。镜像站可能给出一份旧版本（大小不同）或者
 *    一个 HTML 错误页（大小也不同），这两种都会被大小检查拦住；而**同大小的坏文件**
 *    只有哈希能发现。
 */
class AsrModelDownloader(private val dispatchers: DispatcherProvider) {

    /**
     * 下载一个模型文件到 [target]，地址由调用方拼好。
     *
     * @param onBytes 每读一块回调一次，参数是**这个文件**已经下到的字节数。
     *   调用方把它加上「前面已完成的文件」得到总进度。回调在 IO 线程上同步调用，
     *   所以实现里不要做耗时操作（推给 `MutableStateFlow` 就够了，它会自动合并）。
     */
    suspend fun download(
        file: AsrModelFile,
        url: String,
        target: File,
        onBytes: (Long) -> Unit,
    ) = withContext(dispatchers.io) {
        // 循环体是阻塞 IO，协程的取消没法穿透进去，所以把 Job 带进去手动查：
        // 181 MB 在慢网络下一分钟以上，按了取消却什么都不发生，看起来就是「卡死」。
        downloadBlocking(file, url, target, onBytes, currentCoroutineContext()[Job])
    }

    private fun downloadBlocking(
        file: AsrModelFile,
        url: String,
        target: File,
        onBytes: (Long) -> Unit,
        job: Job?,
    ) {
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw AsrModelException.Write(IOException("cannot create ${parent.absolutePath}"))
        }

        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(url)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw AsrModelException.Http(code)

            var written = 0L
            val buffer = ByteArray(BUFFER_BYTES)
            FileOutputStream(part).use { out ->
                connection.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                        if (job?.isActive == false) throw CancellationException("模型下载已取消")
                    }
                }
                // 必须 sync：不然最后一段还躺在页缓存里，进程被系统杀掉时文件就短了一截。
                // 这个调用在慢速存储上要几百毫秒，但每个文件只调一次。
                out.fd.sync()
            }

            if (written != file.sizeBytes) {
                throw AsrModelException.SizeMismatch(file.path, file.sizeBytes, written)
            }
            val actual = sha256HexOfFile(part)
            if (!actual.equals(file.sha256, ignoreCase = true)) {
                MspLog.w(TAG) { "checksum mismatch for ${file.path}: expected ${file.sha256}, got $actual" }
                throw AsrModelException.HashMismatch(file.path)
            }
            if (!part.renameTo(target)) {
                throw AsrModelException.Write(IOException("cannot rename ${part.name}"))
            }
            MspLog.i(TAG) { "downloaded ${file.role.name} ${file.path} (${file.sizeBytes} bytes)" }
        } catch (e: CancellationException) {
            // 取消也要删：留着 181 MB 的 `.part`，用户下次手动重下会先占着空间，
            // 而界面上显示的是「未下载」，他找不到这些字节去哪了。
            part.delete()
            throw e
        } catch (e: AsrModelException) {
            part.delete()
            throw e
        } catch (e: SecurityException) {
            part.delete()
            throw AsrModelException.Write(e)
        } catch (e: IOException) {
            part.delete()
            throw AsrModelException.Network(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val parsed = try {
            URL(url)
        } catch (e: MalformedURLException) {
            throw AsrModelException.BadSource(url)
        }
        return (parsed.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // 镜像站会 302 到 CDN，不跟随就会把重定向响应体当成模型文件存下来。
            instanceFollowRedirects = true
            requestMethod = "GET"
            // Java 默认的 UA（"Java/17"）会被一些 CDN/WAF 直接拒掉，返回 403。
            setRequestProperty("User-Agent", USER_AGENT)
            // 必须显式声明不要压缩：如果服务端 gzip 了，我们拿到的字节数就比清单里的少，
            // 于是会报「文件不完整」——一个看起来像网络问题、实际是协议问题的假故障。
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private companion object {
        const val TAG = "AsrDownload"
        const val PART_SUFFIX = ".part"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 128 * 1024
        const val USER_AGENT = "MultiSuperPlayer/0.6"
    }
}
