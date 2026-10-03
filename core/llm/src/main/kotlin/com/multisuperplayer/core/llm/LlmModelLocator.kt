package com.multisuperplayer.core.llm

import java.io.File

/**
 * 模型在本地的状态。
 *
 * 为什么要三态而不是一个 `Boolean isDownloaded`：三种状态在界面上的动作**完全不同**。
 *
 * | 状态 | 界面动作 |
 * |---|---|
 * | [Absent] | 「下载」+ 先问一句「约 329 MiB」 |
 * | [Incomplete] | 「重新下载」+「上次没下完（已收到 x MB）」 |
 * | [Ready] | 「已下载」+「删除」 |
 *
 * 压成一个布尔值，下到 60% 被系统杀掉之后再回来，界面会说「未下载」，
 * 用户点下去从零开始，而那 60% 的流量白花了（本条模型 345 MB，这一下就是几百 MB）。
 *
 * ⚠️ 与 `core:asr` 的 `AsrModelStatus.Partial` 有一处**故意的差异**：那边叫
 * 「继续下载」，而本下载器**不支持断点续传**（整文件重下，理由见
 * [LlmModelDownloader]），所以这里不能给它一个「继续」的说法——那是在界面上承诺
 * 一件做不到的事。文案上说「重新下载」，并如实显示已收到的字节数。
 */
sealed interface LlmModelStatus {

    /** 磁盘上一个字节都没有。 */
    data object Absent : LlmModelStatus

    /**
     * 有残留但不可用：要么是中断下载留下的 `.part`，要么是正式文件的**大小对不上**
     * （存储损坏，或者同一个 id 的模型换了版本）。
     *
     * [presentBytes] 是磁盘上残留物中最大的那一份的大小，只用于显示「已收到 x MB」，
     * **不能**当成进度条的起点（不会从这里接着下）。
     */
    data class Incomplete(val presentBytes: Long) : LlmModelStatus

    /** 文件在，且大小正好等于清单里写的。 */
    data object Ready : LlmModelStatus
}

/**
 * 模型在磁盘上的位置，以及「在不在」的判断。
 *
 * 目录结构：`<rootDir>/<模型 id>/<fileName>`。用模型 id 当目录名，
 * 是为了「换个模型」这件事只需要改一个设置值，不用搬文件。
 *
 * ## 为什么 [statusOf] 只比大小、不校验哈希
 *
 * 哈希要读完整份文件：这条模型 345 MB，实测 181 MB 约 0.3 s，也就是 345 MB 约 0.6 s，
 * 还要占满磁盘带宽。而设置页一打开就要显示状态，翻译开始前也要查一次——每次都算
 * 一遍不可接受。
 *
 * 所以这里的约定同 `core:asr`：**下载完成的那一刻校验一次哈希**
 * （见 [LlmModelDownloader]），之后一律只认大小。代价是「文件在磁盘上被静默损坏」
 * 这种极端情况查不出来——那时的表现是译文乱码或加载报错，用户重新下载即可。
 *
 * ## 为什么不支持断点续传
 *
 * HTTP 范围续传要处理服务端不支持 `Range`、要处理文件在服务端换了版本
 * （`ETag`/`Last-Modified` 不匹配就得从头来）。这里只有**一个**文件，整文件重下
 * 比一套半靠谱的续传逻辑更省事，也不会产出「两个版本拼起来的模型」——那种文件
 * 大小可能对得上、哈希通不过，要到加载时才崩，排查成本高得多。
 */
class LlmModelLocator(private val rootDir: File) {

    companion object {

        /** 本地推理相关文件在私有目录里的根目录名。 */
        const val DIR_NAME: String = "llm"

        /** 下载中的临时文件后缀（与下载器一致，改动要同时改两处）。 */
        const val PART_SUFFIX: String = ".part"

        /**
         * 从应用的私有目录算出根目录。
         *
         * 模型（345 MB）放这里，卸载应用时跟着一起没了，不会在用户的外部存储里
         * 留下一堆孤儿文件。
         */
        fun rootOf(filesDir: File): File = File(filesDir, DIR_NAME)
    }

    /** 某条模型的目录。 */
    fun directoryOf(model: LlmModelInfo): File = File(rootDir, model.id)

    /** 正式文件的位置。 */
    fun fileOf(model: LlmModelInfo): File = File(directoryOf(model), model.fileName)

    /** 下载中的临时文件（`.part`），只用来算「已收到多少」。 */
    fun partFileOf(model: LlmModelInfo): File =
        File(directoryOf(model), model.fileName + PART_SUFFIX)

    /** 判断状态。不创建任何目录，纯读。 */
    fun statusOf(model: LlmModelInfo): LlmModelStatus {
        val target = fileOf(model)
        if (target.isFile && target.length() == model.sizeBytes) return LlmModelStatus.Ready

        // 只把「比清单小」的那一份当成残留：比清单大的文件不可能是这条模型
        // 下了一半，多半是名字撞上了别的东西，报一个假的进度还不如报 0。
        val partial = partFileOf(model).takeIf { it.isFile }?.length() ?: 0L
        val stale = target.takeIf { it.isFile }?.length() ?: 0L
        val present = maxOf(partial, stale).coerceAtMost(model.sizeBytes)
        return if (present == 0L) LlmModelStatus.Absent else LlmModelStatus.Incomplete(present)
    }

    /** 这条模型是否可以直接拿去用。 */
    fun isReady(model: LlmModelInfo): Boolean = statusOf(model) == LlmModelStatus.Ready

    /** 建好模型目录（下载前调用）。 */
    fun ensureDirectory(model: LlmModelInfo): File = directoryOf(model).apply { mkdirs() }

    /**
     * 删掉这条模型的全部文件（正式文件与 `.part` 残留）。
     *
     * 删目录而不是逐个文件：`.part` 的名字由本类决定，逐个删就有第三个地方
     * 需要知道这个名字。
     *
     * 这是**永久的**（不走回收站），但对象只是「重新下得回来的模型文件」，
     * 不是用户唯一的数据。代价是 345 MB 流量，界面里要写清楚。
     *
     * @return 是否真的删掉了什么（用于「删除」按钮的可用状态与提示语）。
     */
    fun remove(model: LlmModelInfo): Boolean = directoryOf(model).let { dir ->
        dir.exists() && dir.deleteRecursively()
    }

    /** 扫描根目录下所有模型目录，返回「认识的」那几条模型 id。 */
    fun installedModelIds(): Set<String> {
        val known = LlmModelCatalog.models.mapTo(mutableSetOf()) { it.id }
        return rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.filter { it in known }
            ?.toSet()
            .orEmpty()
    }
}
