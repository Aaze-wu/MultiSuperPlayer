package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.translate.translationMediaKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "GeneratedSubtitle"

/**
 * 生成字幕的规模：几份 + 磁盘占用。
 *
 * 两个数一起给，而不是只给份数：用户点「清空」真正想知道的是「这东西占了我多少地方」，
 * 而份数说不清这件事——一份两小时电影的字幕和一集 20 分钟的差好几倍。
 *
 * 文案（[describe] / [describeClearFailure] / [describeClearConfirmation]）和数字
 * 放在同一个类里，而不是搬到设置页去拼：这三句话的每个参数都由这两个数算出来，
 * 分开之后「一份都没有时说什么」这类分支就会在界面里再写一遍（然后写漏）。
 * 这也意味着它们可以在一份纯 JVM 单测里被断言（见 `GeneratedSubtitleCacheTextTest`）。
 */
data class GeneratedSubtitleCacheStats(
    val entries: Int,
    val bytes: Long,
) {

    /** 没有缓存可清。界面据此**不显示**「清空」按钮，而不是把按钮置灰。 */
    val isEmpty: Boolean get() = entries <= 0

    /**
     * 「已缓存 3 份字幕 · 1.2 MB」/「还没有缓存」。
     *
     * 体积用 [TimeFormat.fileSize]：和语音识别那一页「模型多大」是同一个格式，
     * 用户在上下两个数字之间来回看时，长得一样才好比。
     */
    fun describe(): MspText = if (isEmpty) {
        MspText.Res(R.string.msp_asr_cache_empty)
    } else {
        MspText.Res(R.string.msp_asr_cache_stats, entries, TimeFormat.fileSize(bytes))
    }

    /**
     * 删除失败时的说法。
     *
     * **必须把「还剩多少」说出来**，而且必须和 [describe] 长得不一样：
     * 只写「清空失败」的话，用户会把它当成一句无关痛痒的提示，而字幕其实还在、
     * 磁盘也没被释放——真正的失败现象是「我明明清过了，怎么还占着地方」。
     */
    fun describeClearFailure(): MspText =
        MspText.Res(R.string.msp_asr_cache_clear_failed, entries, TimeFormat.fileSize(bytes))

    /**
     * 清空前的确认句：要删多少、删了有什么代价、什么不会被删。
     *
     * 三件事一件都不能少：
     * - **数量**（会删掉 N 份）——没有数字的确认对话框只是让人多点一下；
     * - **代价**（要重新跑一遍识别，约等于音频时长）——这是按下去之后会后悔的那个点；
     * - **不会被删的东西**（模型文件）——这句话是用户敢不敢按的关键。这一页上
     *   「删除」按钮的邻居恰好是「删除模型」，不说清楚，刚下完模型的人会以为
     *   清缓存顺手把模型也清了（而那要重新下 190 MB）。
     */
    fun describeClearConfirmation(): MspText =
        MspText.Res(R.string.msp_asr_cache_clear_confirm, entries, TimeFormat.fileSize(bytes))
}

/**
 * 应用自己生成的字幕（本机语音识别）的存放处。
 *
 * ## 为什么放在私有目录，而不是写在片子旁边
 *
 * 写在片子旁边（`Movie.msp.srt`）对用户最方便——任何播放器都能直接用。但那要求
 * **对那个目录有写权限**：媒体库条目只给了读权限，SAF 授权只覆盖用户选中的那棵树，
 * 而 `WRITE_EXTERNAL_STORAGE` 在这个 API 等级上已经不允许了。所以生成结果存在
 * 应用私有目录里，卸载应用时一起清掉，不会在用户的外部存储里留下孤儿文件。
 * 想拿到文件用字幕面板里的「导出字幕」。
 *
 * ## 接口为什么按 uri，而不是按媒体条目
 *
 * 生成字幕要靠 [SubtitleSource.uri] 才能被播放页找到（那是 [SubtitleRepository]
 * 的缓存 key，也会当上 `SubtitleTrack.id`），所以这一层干脆只认那个 uri：
 * 调用方先调一次 [uriFor] 把媒体条目换过来，之后一律用它。两个方向都只经过
 * [keyOf] 一处映射，不会出现「一半地方用媒体地址、一半地方用生成地址」的错配
 * ——那种错配的表现是「明明生成好了却找不到」。
 *
 * ## 为什么存成 SRT，而不是自己的 JSON
 *
 * SRT 是这套流程里**已经有解析器**的格式（`SrtParser`），所以这份文件被读回来的
 * 路径与外部字幕**完全相同**：同一套候选列表、同一套解析、同一套翻译与导出。
 * 自己造一个格式就要再造一个解析器，而识别结果里没有样式、没有说话人，
 * SRT 表达得下全部信息。
 *
 * ## 文件名用哈希，不用片名
 *
 * 片名里会有 `/`、`:`、空格、emoji，不同片子也可能取同一个名字。哈希之后天然
 * 唯一、天然安全，而且和「人工修正译文」用的是同一个 key 函数
 * （[translationMediaKey]，区别只在目录）——同一个媒体的两套数据不会互相覆盖。
 * 界面上不显示这个名字：凡是 [com.multisuperplayer.core.model.SubtitleOrigin.GENERATED_ASR]
 * 的地方都显示「本机语音识别」。
 *
 * ## 为什么收 [filesDir] 而不是 `Context`
 *
 * 和 `AsrModelLocator` 同一个理由：这里要做的事全是「一个目录 + 几个文件」的读写，
 * 而它的几处判定（0 字节算不算存在、改名失败怎么办、删不掉旧文件怎么办）
 * 恰恰是最容易写错、又最难在手机上复现的。收一个 `File` 之后这些都能在 JVM 单测里
 * 跑，不必为了写一个用例去造 `Context`。
 */
class GeneratedSubtitleStore(
    filesDir: File,
    private val dispatchers: DispatcherProvider,
) {

    private val mutex = Mutex()

    private val directory: File = File(filesDir, DIRECTORY_NAME)

    /** 媒体条目 → 生成字幕的 uri。见类注释里为什么调用方要先用它换一次。 */
    fun uriFor(mediaUri: String?): String = URI_PREFIX + translationMediaKey(mediaUri)

    /** 这个 uri 对应的文件（可能还不存在）。不是本类认可的 uri 时返回 null。 */
    fun fileFor(generatedUri: String): File? =
        keyOf(generatedUri)?.let { File(directory, "$it.$EXTENSION") }

    /**
     * 有没有生成过。
     *
     * 用 `length() > 0` 而不是 `exists()`：0 字节的文件是写一半就被杀掉留下的
     * 垃圾（[save] 先写 `.tmp` 再改名，正常路径产不出 0 字节），把它算成「有字幕」
     * 会让面板列出一条点开什么都没有的候选。
     */
    fun exists(generatedUri: String): Boolean = (fileFor(generatedUri)?.length() ?: 0L) > 0L

    /** 占了多大空间。 */
    fun sizeBytes(generatedUri: String): Long = fileFor(generatedUri)?.length() ?: 0L

    /**
     * 写入。**先写 `.tmp` 再改名**：写一半被系统杀掉也不会留下半个字幕文件，
     * 而半个文件在下一次扫描时是「存在且能被解析出前几百条」的——看起来正常，
     * 只是后半部片子没有字幕。
     *
     * 返回是否成功。失败时**不抛**，由调用方决定怎么说（界面要说的话和
     * 「识别失败了」完全不同：字幕已经识别出来了，只是存不下）。
     */
    suspend fun save(generatedUri: String, text: String): Boolean = withContext(dispatchers.io) {
        val file = fileFor(generatedUri) ?: return@withContext false
        mutex.withLock {
            runCatching {
                directory.mkdirs()
                val temp = File(directory, file.name + TEMP_SUFFIX)
                temp.writeText(text, Charsets.UTF_8)
                if (file.exists() && !file.delete()) {
                    error("删不掉旧文件：${file.name}")
                }
                if (!temp.renameTo(file)) {
                    error("改名失败：${temp.name} → ${file.name}")
                }
            }.onFailure {
                MspLog.w(TAG, it) { "保存生成的字幕失败：${file.name}" }
            }.isSuccess
        }
    }

    suspend fun read(generatedUri: String): String? = withContext(dispatchers.io) {
        val file = fileFor(generatedUri) ?: return@withContext null
        mutex.withLock {
            if (!file.exists()) return@withLock null
            runCatching { file.readText(Charsets.UTF_8) }.onFailure {
                MspLog.w(TAG, it) { "读生成的字幕失败：${file.name}" }
            }.getOrNull()
        }
    }

    /**
     * 删除生成的字幕（用户在面板里换成别的字幕、或者想重新生成一遍）。
     *
     * 这里**真的删文件**，和「人工修正译文」刻意不删的做法相反：修正译文是用户
     * 一个字一个字敲出来的，误删无法恢复；而生成的字幕随时可以再跑一遍——
     * 代价是几分钟的算力，不是用户的手工劳动。
     */
    suspend fun delete(generatedUri: String): Boolean = withContext(dispatchers.io) {
        val file = fileFor(generatedUri) ?: return@withContext false
        mutex.withLock { file.delete() }
    }

    /**
     * 这份缓存现在有多大：几份、占了多少磁盘。给设置页那一行用。
     *
     * 只 stat、**不读内容**：设置页要的只是两个数，而把每份 SRT 都读进内存
     * （一部电影的字幕可以有几 MB）纯粹是打开设置页时白付的代价。
     *
     * 为什么走 [filesOnDisk] 而不是遍历已知的 uri：这一层不知道有哪些媒体，
     * 目录里现在有什么就是全部答案。那个判断也是 [clear] 的依据，两处共用一个
     * 列目录的口径，才不会出现「统计说 3 份、清空删掉 2 份」。
     */
    suspend fun stats(): GeneratedSubtitleCacheStats = withContext(dispatchers.io) {
        mutex.withLock { cacheStatsOf(filesOnDisk()) }
    }

    /**
     * 删掉**全部**生成的字幕。返回是否真的清干净了。
     *
     * 和 [delete] 的区别只在范围：那是「这一部片子重新生成一遍」，这是
     * 「把占的地方还给我」。两件事都不要确认之外的额外保护——生成的字幕随时
     * 可以再跑一遍，代价是几分钟算力，不是用户的手工劳动（见 [delete] 的注释）。
     *
     * 两件必须做到的事：
     *
     * 1. **`.tmp` 残留一起删。** 正常路径产不出它（[save] 写完就改名），留下的那份是
     *    写一半被系统杀掉的结果，而它照样占磁盘。只删正常文件的话，用户会看到
     *    「已清空」之后体积还在——那是最容易让人不再相信这个按钮的一种反馈。
     * 2. **删不掉的份数如实报回去**（不抛异常、也不假装成功）。删不掉时唯一诚实的
     *    做法是把剩下的量算出来告诉用户，见 [GeneratedSubtitleCacheStats.describeClearFailure]。
     */
    suspend fun clear(): Boolean = withContext(dispatchers.io) {
        mutex.withLock {
            val failed = filesOnDisk().filterNot { target ->
                runCatching { target.delete() }
                    .onFailure { MspLog.w(TAG, it) { "删除生成的字幕失败：${target.name}" } }
                    .getOrDefault(false)
            }
            if (failed.isNotEmpty()) {
                MspLog.w(TAG) { "清空生成的字幕：还有 ${failed.size} 份没删掉" }
            }
            failed.isEmpty()
        }
    }

    /** 目录里现在有什么。目录还不存在时是空表，不是异常。 */
    private fun filesOnDisk(): List<File> = directory.listFiles()?.filter { it.isFile }.orEmpty()

    /**
     * 统计一份文件列表。
     *
     * 抽出来是为了让「0 字节的垃圾算不算一份」这个判断能被单测直接喂数据测到，
     * 不必去构造真实的磁盘状态（那种测试会因为磁盘报错而时红时绿）。
     *
     * 口径与 [exists] 一致：0 字节的文件不算一份（写一半的垃圾不该在界面上
     * 显示成「已缓存 1 份」），但它的体积照样算进总量——占的地方是真的，
     * 而把只占 0 字节的东西漏掉反而会让「清空之后还剩 0 B」这种状态看不见。
     */
    private fun cacheStatsOf(files: List<File>): GeneratedSubtitleCacheStats =
        GeneratedSubtitleCacheStats(
            entries = files.count { it.length() > 0L },
            bytes = files.sumOf { it.length().coerceAtLeast(0L) },
        )

    /** 合成 uri → 哈希 key。**整个类里唯一一处反向映射**，见类注释。 */
    private fun keyOf(generatedUri: String): String? {
        if (!generatedUri.startsWith(URI_PREFIX)) return null
        return generatedUri.removePrefix(URI_PREFIX).takeIf { it.isNotEmpty() }
    }

    companion object {

        /** 私有目录里的子目录名。 */
        const val DIRECTORY_NAME: String = "generated_subtitles"

        /** 存的就是 SRT，理由见类注释。 */
        const val EXTENSION: String = "srt"

        /**
         * 合成 uri 的前缀。
         *
         * 用自定义 scheme 而不是 `file://`，是免得有人（包括以后的我们自己）
         * 真去 `openInputStream` 它——它不是任何 provider 的文档，
         * [SubtitleRepository] 读它走的是另一条路。
         */
        const val URI_PREFIX: String = "msp-generated://"

        private const val TEMP_SUFFIX = ".tmp"
    }
}
