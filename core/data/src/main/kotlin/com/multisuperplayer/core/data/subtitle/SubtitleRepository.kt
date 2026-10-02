package com.multisuperplayer.core.data.subtitle

import android.content.Context
import android.net.Uri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleOrigin
import com.multisuperplayer.core.model.SubtitleTrack
import com.multisuperplayer.core.subtitle.ParseResult
import com.multisuperplayer.core.subtitle.SubtitleParserRegistry
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "SubtitleRepo"

/**
 * 外挂字幕的入口：**发现 → 读取 → 解析**。
 *
 * ## 为什么放在 `core:data`
 *
 * 这个模块的职责就是「系统数据 → 领域模型」，[com.multisuperplayer.core.data.library.MediaStoreScanner]
 * 也在这里。解析器本身留在 `:core:subtitle`（纯逻辑、零 Android 依赖、可单测），
 * 这一层只负责把它接上 `ContentResolver`。
 *
 * ## 缓存的是「解析结果」而不是「文件字节」
 *
 * 解析一份几千条的字幕要跑正则，比读文件贵得多。缓存挂在 uri 上、按访问顺序淘汰，
 * 上限很小（[MAX_CACHED_DOCUMENTS]）——单条媒体的候选字幕通常只有几个，
 * 缓存存在的意义是「切走再切回来别重新解析」，不是当数据库用。
 */
class SubtitleRepository(
    context: Context,
    private val locator: SubtitleFileLocator,
    private val parserRegistry: SubtitleParserRegistry,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    private val cacheMutex = Mutex()

    /** `accessOrder = true`：读一次就把它挪到队尾，淘汰最久没用过的。 */
    private val cache = object :
        LinkedHashMap<String, SubtitleDocument>(CACHE_INITIAL_CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SubtitleDocument>): Boolean =
            size > MAX_CACHED_DOCUMENTS
    }

    /**
     * 找出这条媒体的外挂字幕候选，按关联度从高到低排序。
     *
     * 不抛异常：所有失败都翻译成 [SubtitleScan] 的具体分支，让调用方（以及界面）
     * 必须对每种情况给出不同的提示。
     */
    suspend fun scan(entry: MediaEntry): SubtitleScan = withContext(dispatchers.io) {
        val relativePath = entry.relativePath?.takeIf { it.isNotBlank() }
            ?: return@withContext SubtitleScan.NoDirectory

        // displayName 才是磁盘上的真实文件名，用它做匹配；title 是给界面看的，
        // 可能已经被「去后缀」「未知标题兜底」改过。
        val mediaName = entry.displayName?.takeIf { it.isNotBlank() } ?: entry.title

        val scan = try {
            locator.scanDirectory(relativePath)
        } catch (error: Exception) {
            MspLog.w(TAG, error) { "查询目录「$relativePath」失败" }
            return@withContext SubtitleScan.Failed(
                error.detailOr(MspText.Res(R.string.msp_subtitle_reason_directory_unreadable)),
            )
        }

        when (scan) {
            DirectoryScan.Invisible -> {
                MspLog.w(TAG) { "目录「$relativePath」在 MediaStore 里不可见，可能缺少存储权限" }
                return@withContext SubtitleScan.DirectoryInvisible
            }

            is DirectoryScan.NoSubtitles -> {
                MspLog.d(TAG) { "目录「$relativePath」可见 ${scan.visibleFiles} 个文件，但没有字幕" }
                return@withContext SubtitleScan.Found(emptyList())
            }

            is DirectoryScan.Found -> {
                val sources = scan.subtitles
                    .mapNotNull { row -> toSource(entry, mediaName, row) }
                    // 与「自动选择」用同一个比较器，否则面板第一行和被挂上的那条
                    // 可能不是同一条，用户会以为自动选择坏了。
                    .sortedForSelection()

                MspLog.d(TAG) {
                    "「$mediaName」在「$relativePath」发现 ${scan.subtitles.size} 个字幕文件，" +
                        "其中 ${sources.count { it.matchesMedia }} 个与片名匹配"
                }
                SubtitleScan.Found(sources)
            }
        }
    }

    /**
     * 读取并解析一条字幕。同一个 uri 第二次调用直接给缓存。
     *
     * 失败**不进缓存**：文件可能只是暂时读不到（SD 卡没挂上、权限刚授予），
     * 把失败缓存下来会让用户怎么重试都没用。
     */
    suspend fun load(source: SubtitleSource): SubtitleLoadResult {
        cacheMutex.withLock { cache[source.uri] }?.let { return SubtitleLoadResult.Loaded(it) }

        return withContext(dispatchers.io) {
            val bytes = try {
                readBytes(source)
            } catch (error: SubtitleReadException) {
                MspLog.w(TAG, error) { "读取字幕失败：${source.fileName}" }
                return@withContext SubtitleLoadResult.Failed(source.fileName, error.text)
            } catch (error: Exception) {
                MspLog.w(TAG, error) { "读取字幕失败：${source.fileName}" }
                return@withContext SubtitleLoadResult.Failed(
                    source.fileName,
                    error.detailOr(MspText.Res(R.string.msp_subtitle_reason_file_unreadable)),
                )
            }

            val decoded = SubtitleTextDecoding.decode(bytes)
            if (decoded.guessed) {
                MspLog.d(TAG) { "${source.fileName} 不是合法 UTF-8，已按 ${decoded.charset} 解码" }
            }

            val parsed = try {
                parserRegistry.parse(decoded.text, hint = null, fileName = source.fileName)
            } catch (error: Exception) {
                MspLog.w(TAG, error) { "解析字幕失败：${source.fileName}（编码 ${decoded.charset}）" }
                return@withContext SubtitleLoadResult.Failed(
                    source.fileName,
                    error.detailOr(MspText.Res(R.string.msp_subtitle_reason_unparsable)),
                )
            }

            val document = buildDocument(source, decoded, parsed)
            cacheMutex.withLock { cache[source.uri] = document }
            MspLog.d(TAG) {
                "${source.fileName} 解析出 ${document.cues.size} 条字幕（判定格式 ${parsed.format}）"
            }
            SubtitleLoadResult.Loaded(document)
        }
    }

    private fun toSource(entry: MediaEntry, mediaName: String, row: SubtitleFileRow): SubtitleSource? {
        val format = SubtitleFileNaming.formatOf(row.fileName)
        // 后缀认不出来就不列进候选：那是「这个文件不是字幕」，不是「这条字幕评分低」。
        if (format == SubtitleFormat.UNKNOWN) return null

        val info = SubtitleFileNaming.analyze(row.fileName)
        return SubtitleSource(
            uri = row.uri,
            fileName = row.fileName,
            format = format,
            languageTag = info.languageTag,
            isForced = info.isForced,
            isBilingual = info.isBilingual,
            sizeBytes = row.sizeBytes,
            matchScore = SubtitleFileNaming.associationScore(
                mediaFileName = mediaName,
                subtitleFileName = row.fileName,
                subtitleFormat = format,
                mediaKind = entry.kind,
            ),
            trailingTagCount = info.trailingTagCount,
        )
    }

    private fun readBytes(source: SubtitleSource): ByteArray {
        if (source.sizeBytes > MAX_SOURCE_BYTES) {
            throw SubtitleReadException(tooLargeText(source.sizeBytes))
        }
        val stream = appContext.contentResolver.openInputStream(Uri.parse(source.uri))
            ?: throw SubtitleReadException(MspText.Res(R.string.msp_subtitle_reason_file_unopenable))
        return stream.use { input ->
            val bytes = input.readBytes()
            // 再查一次：MediaStore 里的 SIZE 有可能过时（文件刚被换掉）。
            if (bytes.size > MAX_SOURCE_BYTES) {
                throw SubtitleReadException(tooLargeText(bytes.size.toLong()))
            }
            bytes
        }
    }

    private fun tooLargeText(sizeBytes: Long): MspText =
        MspText.Res(R.string.msp_subtitle_reason_file_too_large, sizeBytes / 1024 / 1024)

    /**
     * 读字幕时**我们自己**发现的失败。
     *
     * 为什么不直接用 `error.message`：那句话要显示给用户，必须能翻译，而 `message`
     * 只能是一个已经定死的字符串。系统自己抛的异常仍然走 [detailOr]——系统原文
     * 不翻译、原样透出去，否则真实原因会被一句中文盖掉。
     */
    private class SubtitleReadException(val text: MspText) : IOException(text.toString())

    /**
     * 那句给人看的说明。
     *
     * 系统异常的 `message` 可能是 null（而且通常已经是系统语言），那就退回
     * 我们自己的 [fallback] 句子；有内容就原样透出。
     */
    private fun Throwable.detailOr(fallback: MspText): MspText =
        message?.takeIf { it.isNotBlank() }?.let(MspText::Plain) ?: fallback

    private fun buildDocument(
        source: SubtitleSource,
        decoded: SubtitleTextDecoding.Decoded,
        parsed: ParseResult,
    ): SubtitleDocument {
        val warnings = buildList {
            addAll(parsed.warnings)
            // 编码是猜出来的时候必须说出来：Big5 文件会被按 GB18030 解出
            // 「字形合法但内容全错」的汉字，界面上看起来只是乱，看不出原因。
            if (decoded.guessed) {
                add("文件不是 UTF-8，已按 ${decoded.charset} 解码；若显示为乱码请另存为 UTF-8 再试")
            }
        }

        return SubtitleDocument(
            track = SubtitleTrack(
                id = source.uri,
                origin = SubtitleOrigin.EXTERNAL_FILE,
                format = parsed.format,
                languageTag = source.languageTag,
                label = source.fileName,
                sourceUri = source.uri,
                isForced = source.isForced,
                cueCount = parsed.cues.size,
            ),
            cues = parsed.cues,
            styles = parsed.styles,
            defaultStyle = parsed.defaultStyle,
            metadata = parsed.metadata,
            warnings = warnings,
        )
    }

    private companion object {
        /** 单条媒体同时挂着的字幕文档上限。 */
        const val MAX_CACHED_DOCUMENTS = 6
        const val CACHE_INITIAL_CAPACITY = 8

        /** 8 MB。正常字幕文件在几百 KB 量级，超过这个数基本是选错了文件。 */
        const val MAX_SOURCE_BYTES = 8L * 1024 * 1024
    }
}
