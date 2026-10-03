package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.translate.translationMediaKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "GeneratedSubtitle"

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
