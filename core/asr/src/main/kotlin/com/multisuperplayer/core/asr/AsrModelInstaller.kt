package com.multisuperplayer.core.asr

/**
 * 模型下载进度。
 *
 * [fileName] 是当前正在下的文件（相对路径），用来在界面上写「正在下载 xxx」——
 * 光有一个百分比，用户看到长时间停在 60% 时不知道是在下 3 MB 的 tokens
 * 还是在下 181 MB 的 encoder。
 */
data class AsrModelProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val fileName: String,
) {
    /** 0..1。总体积为 0 时返回 0（列表写错才会出现，不该让界面算出 NaN）。 */
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }
}

/**
 * 把一条模型的文件都下全。
 *
 * ## 为什么是「按文件」而不是「按字节断点续传」
 *
 * 这套模型只有 2～4 个文件，最大 181 MB。做 HTTP 范围续传要额外处理：服务端不认
 * `Range`、服务端换了文件版本（`ETag`/`Last-Modified` 不匹配就得从头来）、
 * 以及「两个版本各下一半拼起来」——最后那种的产物大小可能刚好对得上，
 * 哈希通不过，而且要到加载模型时才崩。整文件重下比这堆逻辑更省事，也更不容易出错。
 *
 * 所以进度是**按文件**推进的：下完一个文件，进度条跳一大格。
 */
class AsrModelInstaller(
    private val locator: AsrModelLocator,
    private val downloader: AsrModelDownloader,
) {

    /**
     * 下全 [model] 缺的文件。已经下好的文件不会重下（大小对得上就算下好，
     * 理由见 [AsrModelLocator.statusOf]）。
     *
     * [baseUrl] 为 `null` 时用默认镜像站；它只影响地址，不影响校验——
     * 从任何源下下来的文件都要过哈希。
     */
    suspend fun install(
        model: AsrModelInfo,
        baseUrl: String?,
        onProgress: (AsrModelProgress) -> Unit = {},
    ) {
        val missing = locator.missingFiles(model)
        if (missing.isEmpty()) return

        locator.ensureDirectory(model)
        // 已经下好的部分算进进度起点，界面上就不会出现「下到 90% 又重新从 0 开始」。
        var completedBytes = model.totalBytes - missing.sumOf { it.sizeBytes }

        for (file in missing) {
            val base = completedBytes
            onProgress(AsrModelProgress(base, model.totalBytes, file.path))
            downloader.download(
                file = file,
                url = model.downloadUrl(file, baseUrl),
                target = locator.fileOf(model, file),
            ) { written ->
                onProgress(AsrModelProgress(base + written, model.totalBytes, file.path))
            }
            completedBytes = base + file.sizeBytes
            onProgress(AsrModelProgress(completedBytes, model.totalBytes, file.path))
        }

        // 下完再核一遍状态：中间可能有人（或系统的清理）删掉了某个文件，
        // **不能**假设「下载函数没抛异常 = 模型可用」。这一步让失败落在
        // 「模型还没下载完」这句用户能看懂的话上，而不是等到加载时崩。
        if (locator.statusOf(model) !is AsrModelStatus.Ready) {
            throw AsrException.ModelMissing(model)
        }
    }
}
