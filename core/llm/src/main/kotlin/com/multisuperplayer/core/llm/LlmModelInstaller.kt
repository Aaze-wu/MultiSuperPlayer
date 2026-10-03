package com.multisuperplayer.core.llm

import com.multisuperplayer.core.common.log.MspLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 下载进度。
 *
 * ## 为什么「校验中」是单独的一档，而不是「下载 100%」
 *
 * 因为校验要读完整整 345 MB（约 1 秒，还占满磁盘带宽），界面如果停在 100%
 * 一动不动，用户会以为卡死了、去点取消——而实际上只剩最后一秒。
 * 一句话的差别，决定他会不会白下 345 MB。
 */
sealed interface LlmInstallProgress {

    /** [bytes] 是这次下载已经落盘的字节数（`.part` 的大小），[total] 是清单里的大小。 */
    data class Downloading(val bytes: Long, val total: Long) : LlmInstallProgress {
        /** 0f～1f。用 Double 算完再转，避免整数除法把 99% 变成 98%。 */
        val fraction: Float get() = if (total <= 0L) 0f else (bytes.toDouble() / total).toFloat()
    }

    /** 传输完了，正在核对大小与 sha256。 */
    data object Verifying : LlmInstallProgress
}

/**
 * 「把模型装上」这一件事的门面：查状态、下载、校验、删掉。
 *
 * ## 为什么要有一个 Mutex
 *
 * 只有一条模型、一个用户，看起来并发是不可能的；但设置页的「下载」/「删除」
 * 是两个按钮，用户完全可能连着点（或者在下载中点删除）。没有互斥的话
 * 「删除」会在下载写到一半时把目录删掉，而下载器接下来往一个已删的目录里
 * `renameTo`——报出来的错和「网络失败」长得一样，排查方向全错。
 *
 * 所以这里串行化的是**对模型目录的写操作**，不是网络。互斥范围只覆盖
 * 「检查 + 下载 + 改名」这一串，不覆盖 UI 的进度收集。
 */
class LlmModelInstaller(
    private val locator: LlmModelLocator,
    private val downloader: LlmModelDownloader,
) {

    private val mutex = Mutex()

    /**
     * 确保 [model] 可用。已经装好就直接返回（幂等），否则下载 + 校验。
     *
     * @param baseUrl 下载源；null/空串会用 [DEFAULT_LLM_MODEL_BASE_URL]。
     * @param onProgress 进度回调。会在 IO 线程上被高频调用，实现要能承受。
     * @throws LlmModelException 下载或校验失败（[describeLlmFailure] 会变成人话）。
     * @throws kotlinx.coroutines.CancellationException 用户取消。
     */
    suspend fun install(
        model: LlmModelInfo,
        baseUrl: String?,
        onProgress: (LlmInstallProgress) -> Unit = {},
    ) = mutex.withLock {
        if (locator.isReady(model)) {
            MspLog.i(TAG) { "${model.id} already installed" }
            return@withLock
        }

        // 先建目录，再取文件路径：`fileOf` 是纯函数，不会替我们建目录。
        locator.ensureDirectory(model)
        val target = locator.fileOf(model)
        val total = model.sizeBytes
        val url = model.downloadUrl(baseUrl)
        MspLog.i(TAG) { "downloading ${model.id} from $url" }

        downloader.download(model, url, target, onBytes = { onProgress(LlmInstallProgress.Downloading(it, total)) })
        onProgress(LlmInstallProgress.Verifying)

        // 下载器内部已经校验过哈希与大小并改名了；这里只确认一次结果，
        // 因为「返回了但文件还是不对」这种情况要么是磁盘满了、要么是并发删过，
        // 静默返回会让上层以为装好了，直到加载模型时才炸。
        if (!locator.isReady(model)) {
            throw LlmModelException.Write(
                java.io.IOException("installed ${model.fileName} but the file is not usable"),
            )
        }
    }

    /**
     * 删掉模型文件。同样走互斥，避免在下载中途删掉目录。
     *
     * @return 是否真的删掉了什么。
     */
    suspend fun remove(model: LlmModelInfo): Boolean = mutex.withLock {
        val removed = locator.remove(model)
        if (removed) MspLog.i(TAG) { "removed ${model.id}" }
        removed
    }

    private companion object {
        const val TAG = "LlmInstaller"
    }
}
