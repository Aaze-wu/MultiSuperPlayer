package com.multisuperplayer.core.data.subtitle

import android.content.Context
import android.net.Uri
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleOrigin
import com.multisuperplayer.core.model.SubtitleTrack
import com.multisuperplayer.core.subtitle.ParseResult
import com.multisuperplayer.core.subtitle.SubtitleParseException
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
 * 解析一份几千条的字幕要跑正则，比读文件贵得多。缓存的键是「uri + 体积」
 * （见 [ParsedSubtitleCache]：只按 uri 命中会让「文件内容变了」永远读到旧解析结果），
 * 上限很小（[ParsedSubtitleCache.MAX_ENTRIES]）——单条媒体的候选字幕通常只有几个，
 * 缓存存在的意义是「切走再切回来别重新解析」，不是当数据库用。
 */
class SubtitleRepository(
    context: Context,
    private val locator: SubtitleFileLocator,
    private val safLocator: SafSubtitleLocator,
    private val fileSystemLocator: FileSystemSubtitleLocator,
    private val parserRegistry: SubtitleParserRegistry,
    private val generatedStore: GeneratedSubtitleStore,
    private val dispatchers: DispatcherProvider,
) {

    private val appContext = context.applicationContext

    private val cacheMutex = Mutex()

    private val cache = ParsedSubtitleCache()

    /**
     * 找出这条媒体的字幕候选，按关联度从高到低排序。
     *
     * 不抛异常：所有失败都翻译成 [SubtitleScan] 的具体分支，让调用方（以及界面）
     * 必须对每种情况给出不同的提示。
     *
     * 结果里会有两种东西：目录里找到的外挂文件，以及**应用自己生成的字幕**
     * （见 [GeneratedSubtitleStore]，下面叫它「生成字幕」）。后者不依赖任何目录
     * 权限，所以它能在下面每一档里都出现——包括「拿不到目录」那几档。
     */
    suspend fun scan(entry: MediaEntry): SubtitleScan = withContext(dispatchers.io) {
        scanExternal(entry).withGenerated(generatedSourceOf(entry))
    }

    /** 只看目录里那一条来源。生成字幕由 [scan] 另外接上去。 */
    private suspend fun scanExternal(entry: MediaEntry): SubtitleScan = withContext(dispatchers.io) {
        // displayName 才是磁盘上的真实文件名，用它做匹配；title 是给界面看的，
        // 可能已经被「去后缀」「未知标题兜底」改过。
        val mediaName = entry.displayName?.takeIf { it.isNotBlank() } ?: entry.title

        // 三条来源问的是**三个不同的地方**，一条都不能退化成另一条：
        // 媒体库条目有索引（查 RELATIVE_PATH）、SAF 条目有授权（问 provider）、
        // 文件浏览器打开的条目两样都没有（直接列目录）。判断本身是个纯函数，
        // 见 SubtitleLookup 的注释——那里写了为什么它必须可测。
        val lookup = subtitleLookupOf(entry)
        val where = lookup.where

        val scan = try {
            when (lookup) {
                is SubtitleLookup.SafDocument -> safLocator.scan(lookup.uri)

                is SubtitleLookup.LocalDirectory -> fileSystemLocator.scanDirectory(lookup.path)

                is SubtitleLookup.MediaStoreDirectory -> locator.scanDirectory(lookup.relativePath)

                SubtitleLookup.Unavailable -> {
                    MspLog.d(TAG) { "「$mediaName」拿不到所在目录，无法自动查找外挂字幕" }
                    return@withContext SubtitleScan.NoDirectory
                }
            }
        } catch (error: Exception) {
            MspLog.w(TAG, error) { "查询目录「$where」失败" }
            return@withContext SubtitleScan.Failed(
                error.detailOr(MspText.Res(R.string.msp_subtitle_reason_directory_unreadable)),
            )
        }

        when (scan) {
            DirectoryScan.Invisible -> {
                MspLog.w(TAG) {
                    "目录「$where」不可见：可能是没权限（未开启「所有文件访问」），也可能是 SAF 授权已失效"
                }
                return@withContext SubtitleScan.DirectoryInvisible
            }

            is DirectoryScan.NoSubtitles -> {
                MspLog.d(TAG) { "目录「$where」可见 ${scan.visibleFiles} 个文件，但没有字幕" }
                return@withContext SubtitleScan.Found(emptyList())
            }

            is DirectoryScan.Found -> {
                val sources = scan.subtitles
                    .mapNotNull { row -> toSource(entry, mediaName, row) }
                    // 与「自动选择」用同一个比较器，否则面板第一行和被挂上的那条
                    // 可能不是同一条，用户会以为自动选择坏了。
                    .sortedForSelection()

                MspLog.d(TAG) {
                    "「$mediaName」在「$where」发现 ${scan.subtitles.size} 个字幕文件，" +
                        "其中 ${sources.count { it.matchesMedia }} 个与片名匹配"
                }
                SubtitleScan.Found(sources)
            }
        }
    }

    /**
     * 把生成字幕合进扫描结果。
     *
     * ## 为什么「拿不到目录」也要合
     *
     * 那几档原本回答的是「这个片子的文件夹里有没有外挂字幕」，而生成字幕**不在**
     * 任何文件夹里（它在应用私有目录），也不会因为权限被挡住。要是不合，后果最重
     * 的一类设备正好中招：Android 9 及以下拿不到 `relativePath`，媒体库条目就走
     * 了 `NoDirectory`——而那恰好是最需要语音识别的场景（旁边什么都没有）。
     * 所以宁可丢掉「目录读不到」这句提示，也不能让用户看着自己刚生成的字幕
     * 却被告知「没有可用字幕」。
     *
     * 代价是那一档的提示看不到了。这一点是知道的：用户按「重新扫描」走的是同一个
     * 函数，所以他也看不到——不去管它，因为「想找外挂字幕而找不到」这件事，
     * 在用户已经有一条能用字幕时不再是当前要解决的问题。
     */
    private fun SubtitleScan.withGenerated(generated: SubtitleSource?): SubtitleScan {
        if (generated == null) return this
        return when (this) {
            is SubtitleScan.Found -> SubtitleScan.Found((sources + generated).sortedForSelection())

            SubtitleScan.NoDirectory,
            SubtitleScan.DirectoryInvisible,
            is SubtitleScan.Failed,
            -> SubtitleScan.Found(listOf(generated))
        }
    }

    /**
     * 这条媒体自己没有生成字幕时的候选；没有就返回 null。
     *
     * ## 关联分定在自动挂载的门槛上
     *
     * [AUTO_MATCH_SCORE] 就是「可以不经用户确认就挂上」的最低分。生成字幕正好
     * 属于这一档：分数再低就会变成「生成了却不会自动显示」，用户会认为功能坏了；
     * 再高就会压过真正的外挂字幕（同分时 [sortedForSelection] 会把外挂文件排前面，
     * 见那里的第 2 层）。
     *
     * ## 为什么语言是空的
     *
     * 文件名（也就是这片子的来源）里没有任何语言信息。填一个猜来的语言值，
     * 以后「按语言挑字幕」这类功能会拿它当真——猜错的代价是挑了条用户看不懂的
     * 字幕，而他现在明明看见字幕就在屏幕上。让它空着，界面会照常显示「语言未知」。
     */
    private fun generatedSourceOf(entry: MediaEntry): SubtitleSource? {
        val generatedUri = generatedStore.uriFor(entry.uri)
        if (!generatedStore.exists(generatedUri)) return null

        return SubtitleSource(
            uri = generatedUri,
            // 磁盘上的名字是哈希（见 GeneratedSubtitleStore），但这个名字要出现在
            // 解析失败提示和日志里，所以用能读的「片名.asr.srt」。
            fileName = "${(entry.displayName ?: entry.title).substringBeforeLast('.')}.asr.${GeneratedSubtitleStore.EXTENSION}",
            format = SubtitleFormat.SRT,
            languageTag = null,
            isForced = false,
            isBilingual = false,
            sizeBytes = generatedStore.sizeBytes(generatedUri),
            matchScore = AUTO_MATCH_SCORE,
            trailingTagCount = 0,
            origin = SubtitleOrigin.GENERATED_ASR,
        )
    }

    /**
     * 读取并解析一条字幕。同一条（uri + 体积都一样）第二次调用直接给缓存。
     *
     * 体积参与命中判定的理由见 [ParsedSubtitleCache]：只按 uri 命中会让「重新生成字幕」
     * 和「重新扫描字幕」都读不到新内容。
     *
     * 失败**不进缓存**：文件可能只是暂时读不到（SD 卡没挂上、权限刚授予），
     * 把失败缓存下来会让用户怎么重试都没用。
     */
    suspend fun load(source: SubtitleSource): SubtitleLoadResult {
        cacheMutex.withLock { cache.get(source) }?.let { return SubtitleLoadResult.Loaded(it) }

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
            cacheMutex.withLock { cache.put(source, document) }
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

    private suspend fun readBytes(source: SubtitleSource): ByteArray {
        // 生成字幕的 uri 是合成的（见 [GeneratedSubtitleStore.uriFor]），不是任何
        // provider 的文档，交给 ContentResolver 开只会得到一个空流。它是存在应用
        // 私有目录里的普通文件，直接读。
        if (source.origin == SubtitleOrigin.GENERATED_ASR) {
            val text = generatedStore.read(source.uri)
                ?: throw SubtitleReadException(
                    MspText.Res(R.string.msp_subtitle_reason_file_unreadable),
                )
            return text.toByteArray(Charsets.UTF_8)
        }

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
     * 【我们自己造的失败】带的是可翻译的 [MspText]（[SubtitleReadException] /
     * [SubtitleParseException]），直接取出来用；系统自己抛的异常只有 `message`
     * （而且通常已经是系统语言），有内容就原样透出去，null 才退回 [fallback]。
     *
     * 两个自定义异常必须**先**判：它们的 `message` 只是 `text.toString()`
     * （形如 `Res(id=…, args=…)`），把它当文案显示出来就是给用户看一串开发信息。
     */
    private fun Throwable.detailOr(fallback: MspText): MspText = when (this) {
        is SubtitleReadException -> text
        is SubtitleParseException -> text
        else -> message?.takeIf { it.isNotBlank() }?.let(MspText::Plain) ?: fallback
    }

    private fun buildDocument(
        source: SubtitleSource,
        decoded: SubtitleTextDecoding.Decoded,
        parsed: ParseResult,
    ): SubtitleDocument {
        val warnings = subtitleWarnings(parsed, decoded)

        return SubtitleDocument(
            track = SubtitleTrack(
                id = source.uri,
                // 用来源自带的 origin，不写死：语音识别生成的文档要是被标成
                // EXTERNAL_FILE，界面上就会把它说成「外挂文件」，
                // 而且用户无从知道这份字幕是机器听出来的。
                origin = source.origin,
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
        /** 8 MB。正常字幕文件在几百 KB 量级，超过这个数基本是选错了文件。 */
        const val MAX_SOURCE_BYTES = 8L * 1024 * 1024
    }
}

/**
 * 解析告警的组装：解析器自己收集的告警 + 「编码是猜的」这条。
 *
 * ## 为什么摘成一个 `internal` 顶层函数，而不是留在 [SubtitleRepository] 里
 *
 * 因为它**不碰 Context / ContentResolver**，摘出来就能被单测直接调用。
 * 「编码是猜的必须说出来」这条规则此前一行测试都没有，而它错起来的样子
 * 恰好是**最不像 bug 的那种**：Big5 字幕会被按 GB18030 解出「字形合法、
 * 内容全错」的汉字，界面上除了看起来乱没有任何提示，用户只会以为播放器坏了。
 * 而真正要钉的不是那句话的措辞，是「它必须是一条能翻译的 [MspText]」——
 * 一个硬编码的中文 `String` 在英文界面里是完全看不见的漏洞。
 *
 * ## 为什么用 `charset` 这个字符串而不是 Charset 对象
 *
 * [SubtitleTextDecoding.Decoded.charset] 已经是「给用户看的名字」
 * （日语统一报 `Shift-JIS`，不漏出平台上的 `windows-31j`），
 * 这里再传 [java.nio.charset.Charset] 就把那个决定重复实现了两遍。
 */
internal fun subtitleWarnings(
    parsed: ParseResult,
    decoded: SubtitleTextDecoding.Decoded,
): List<MspText> = buildList {
    addAll(parsed.warnings)
    if (decoded.guessed) {
        add(MspText.Res(R.string.msp_subtitle_warn_charset_guessed, decoded.charset))
    }
}
