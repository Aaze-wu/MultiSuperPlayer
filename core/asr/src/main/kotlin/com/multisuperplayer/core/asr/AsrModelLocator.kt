package com.multisuperplayer.core.asr

import java.io.File

/**
 * 模型在本地的状态。
 *
 * 为什么要有 [Absent] 和 [Partial] 之分，而不是一个 `Boolean isDownloaded`：
 * 这两个状态在界面上的动作**完全不同**——没下过要显示「下载」（几百 MB 的流量，
 * 该先问一句），下了一半要显示「继续下载」加一个百分比。压成一个布尔值，
 * 下到 60% 被系统杀掉之后再回来，界面会说「未下载」，用户点下去从零开始，
 * 而那 60% 的流量白花了。
 */
sealed interface AsrModelStatus {

    /** 一个文件都没有，什么都没下过。 */
    data object Absent : AsrModelStatus

    /**
     * 下了一部分。
     *
     * [presentBytes] 只统计**大小对得上**的文件，所以它是「已经拿到的有效字节」，
     * 可以直接当进度条的起点；[missing] 就是还要下的东西。
     */
    data class Partial(val presentBytes: Long, val missing: List<AsrModelFile>) : AsrModelStatus

    /** 所有文件都在，且大小都对。 */
    data object Ready : AsrModelStatus
}

/**
 * 模型在磁盘上的位置，以及「在不在」的判断。
 *
 * 目录结构：`<rootDir>/<模型 id>/<清单里的相对路径>`。用模型 id 当目录名，
 * 是为了「换个模型」这件事只需要改一个设置值，不用搬文件。
 *
 * ## 为什么 [statusOf] 只比大小、不校验哈希
 *
 * 哈希要读完整份文件（181 MB ≈ 0.3 s，还要占满磁盘带宽）。设置页一打开就要显示
 * 两条模型的状态，每次都算一遍是不可接受的；而且哈希通过的文件在「大小也对」这个
 * 条件下几乎不可能不通过，多算一次买不到什么。
 *
 * 所以这里的约定是：**下载完成的那一刻校验一次哈希**（见 `AsrModelInstaller`），
 * 之后一律只认大小。代价是「文件在磁盘上被静默损坏」这种极端情况查不出来——
 * 那种情况的表现是识别结果乱码，用户重新下载即可，不值得每次启动都付这 0.3 s。
 */
class AsrModelLocator(private val rootDir: File) {

    companion object {

        /** 语音识别相关文件在私有目录里的根目录名。 */
        const val DIR_NAME: String = "asr"

        /**
         * 从应用的私有目录算出根目录。
         *
         * 模型（几百 MB）和 VAD 模型（643 KB）都放在这里，卸载应用时跟着一起没了，
         * 不会在用户的外部存储里留下一堆孤儿文件。
         */
        fun rootOf(filesDir: File): File = File(filesDir, DIR_NAME)
    }

    /** 某条模型的目录。 */
    fun directoryOf(model: AsrModelInfo): File = File(rootDir, model.id)

    /** 某条模型的某个文件。 */
    fun fileOf(model: AsrModelInfo, file: AsrModelFile): File = File(directoryOf(model), file.path)

    /** 判断状态。不创建任何目录，纯读。 */
    fun statusOf(model: AsrModelInfo): AsrModelStatus {
        val missing = mutableListOf<AsrModelFile>()
        var presentBytes = 0L
        for (file in model.files) {
            val local = fileOf(model, file)
            if (local.isFile && local.length() == file.sizeBytes) {
                presentBytes += file.sizeBytes
            } else {
                missing += file
            }
        }
        return when {
            missing.isEmpty() -> AsrModelStatus.Ready
            presentBytes == 0L && missing.size == model.files.size -> AsrModelStatus.Absent
            else -> AsrModelStatus.Partial(presentBytes, missing)
        }
    }

    /** 某条模型是否可以直接拿去用。 */
    fun isReady(model: AsrModelInfo): Boolean = statusOf(model) == AsrModelStatus.Ready

    /**
     * 还没下好的文件，也就是下载器真正要下的东西。
     *
     * 为什么不复用 `.part` 临时文件做断点续传：HTTP 范围续传要处理服务端不支持
     * `Range`、要处理文件在服务端换了版本（`ETag`/`Last-Modified` 不匹配就得从头来），
     * 而这套模型只有 2～4 个文件、单个最大 181 MB。整文件重下比一套半靠谱的续传逻辑
     * 更省事，也不会产出「两个不同版本拼起来的模型」——那种模型的大小对得上、
     * 哈希通不过，而且要到加载时才崩，排查成本高得多。
     *
     * 所以这里的进度是**按文件**推进的，不是按字节。
     */
    fun missingFiles(model: AsrModelInfo): List<AsrModelFile> =
        model.files.filterNot { isComplete(model, it) }

    private fun isComplete(model: AsrModelInfo, file: AsrModelFile): Boolean {
        val local = fileOf(model, file)
        return local.isFile && local.length() == file.sizeBytes
    }

    /** 建好模型目录（下载前调用）。 */
    fun ensureDirectory(model: AsrModelInfo): File = directoryOf(model).apply { mkdirs() }

    /**
     * 删掉一条模型的全部文件。
     *
     * 这是**永久的**（不走回收站），但对象只是「重新下得回来的模型文件」，
     * 不是用户唯一的数据，所以可以接受。代价是 190 MB 流量，界面里要写清楚。
     *
     * @return 是否真的删掉了什么（用于「删除」按钮的可用状态与提示语）。
     */
    fun remove(model: AsrModelInfo): Boolean = directoryOf(model).let { dir ->
        dir.exists() && dir.deleteRecursively()
    }

    /** 扫描根目录下所有模型目录，返回「有文件的」那几条模型 id。 */
    fun installedModelIds(): Set<String> =
        rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.filter { name -> name in AsrModelCatalog.models.map { it.id } }
            ?.toSet()
            .orEmpty()
}
