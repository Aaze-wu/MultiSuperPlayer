package com.multisuperplayer.core.translate

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.text.MspText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

private const val TAG = "TranslationCache"

/** 一条缓存记录：原文摘要 → 译文。 */
data class CacheEntry(
    val key: String,
    val source: String,
    val translation: String,
    val atMillis: Long,
)

/**
 * 缓存的规模：条数 + 磁盘占用。
 *
 * 两个数一起给，而不是只给条数：用户点「清空」真正想知道的是「这东西占了我多少地方」，
 * 而条数说不清这件事——同样 500 条，一句「嗯」和一句 40 字的台词差几十倍。
 *
 * 文案（[describe] / [describeClearFailure] / [describeClearConfirmation]）
 * 和数字放在同一个类里，而不是搬到设置页去拼：这三句话的每个参数都由这两个数算出来，
 * 分开之后「条数为 0 时说什么」这类分支就会在界面里再写一遍（然后写漏）。
 * 这也意味着它们可以在一份纯 JVM 单测里被断言（见 `TranslationCacheTextTest`）。
 */
data class TranslationCacheStats(
    val entries: Int,
    val bytes: Long,
) {

    /** 没有缓存可清。界面据此**不显示**「清空」按钮，而不是把按钮置灰。 */
    val isEmpty: Boolean get() = entries <= 0

    /**
     * 「已缓存 128 条译文 · 1.2 MB」/「还没有缓存」。
     *
     * 体积用 [TimeFormat.fileSize]（`1.2 GB` / `340 MB`）：和语音识别/本地模型那两页
     * 的「模型多大」是同一个格式，用户在上下来回看时数字长得一样。
     */
    fun describe(): MspText = if (isEmpty) {
        MspText.Res(R.string.msp_translate_cache_empty)
    } else {
        MspText.Res(R.string.msp_translate_cache_stats, entries, TimeFormat.fileSize(bytes))
    }

    /**
     * 删除失败时的说法。
     *
     * **必须把「还剩多少」说出来**，而且必须和 [describe] 长得不一样：
     * 只写「清空失败」的话，用户会把它当成一句无关痛痒的提示，而缓存其实还在、
     * 下次翻译还会命中——真正的失败现象是「我明明清过了，怎么还是没花钱」。
     */
    fun describeClearFailure(): MspText =
        MspText.Res(R.string.msp_translate_cache_clear_failed, entries, TimeFormat.fileSize(bytes))

    /**
     * 清空前的确认句：要删多少、删了有什么代价、什么不会被删。
     *
     * 三件事一件都不能少：
     * - **代价**（下次要重新付费）——这是用户按下去之后会后悔的那个点；
     * - **数量**（会删掉 N 条）——没有数字的确认对话框只是让人多点一下；
     * - **不会被删的东西**（手动改过的译文）——这句话是用户敢不敢按的关键，
     *   少了它，凡是改过译文的人都不敢清缓存（而那正是最需要清的人）。
     */
    fun describeClearConfirmation(): MspText =
        MspText.Res(R.string.msp_translate_cache_clear_confirm, entries, TimeFormat.fileSize(bytes))
}

/**
 * 缓存文件（JSONL）的行编解码。纯函数。
 *
 * ## 为什么是全局一份、而不是「每部片子一份」
 *
 * 因为命中的主体是**句子**，不是片子。同一集重看、同一部番的不同季、
 * 甚至不同片子里的同一句日常问候，都能互相复用。按片子分文件会让
 * 「同一句话翻译两次」变成常态，而那正是花钱的部分。
 *
 * 缓存文件里存的是译文，不是隐私数据；不存 API key、不存播放记录。
 */
internal object TranslationCacheCodec {

    private const val CODEC_VERSION = 1

    /**
     * 缓存 key。
     *
     * **参与计算的每一项都会改变结果**，所以每一项都不能省：
     * - 模型名：换模型就该换译文；
     * - 目标语言：翻成日语和翻成中文不是一回事；
     * - 术语表指纹：用户加了术语表后重新翻译，必须真的重翻，
     *   否则界面上看起来就是「点了没反应」；
     * - 提示词版本：改了提示词，旧结果不该复用。
     */
    fun key(
        model: String,
        target: TranslationTarget,
        glossaryFingerprint: String,
        source: String,
    ): String = sha256Hex(
        listOf(
            "v$CODEC_VERSION",
            "p$TRANSLATION_PROMPT_VERSION",
            model.trim(),
            target.code,
            glossaryFingerprint,
            source,
        ).joinToString("\u0000"),
    )

    fun encodeLine(entry: CacheEntry): String = JsonObject(
        mapOf(
            "key" to JsonPrimitive(entry.key),
            "source" to JsonPrimitive(entry.source),
            "translation" to JsonPrimitive(entry.translation),
            "at" to JsonPrimitive(entry.atMillis),
        ),
    ).toString()

    /**
     * 单行解码。任何一项不对就返回 null——**坏行只丢自己**，
     * 不能因为一行磁盘损坏就让整份缓存作废（那等于重新付一遍钱）。
     */
    fun decodeLine(line: String): CacheEntry? {
        if (line.isBlank()) return null
        val obj = TranslationJson.parseObjectOrNull(line) ?: return null
        val key = obj["key"].rawStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val translation = obj["translation"].rawStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val source = obj["source"].rawStringOrNull().orEmpty()
        val at = obj["at"].stringOrNull()?.toLongOrNull() ?: 0L
        return CacheEntry(key, source, translation, at)
    }

    /** 文件内容 → 索引。重复 key 取**后写的**（新结果覆盖旧结果）。 */
    fun decodeAll(text: String): Map<String, CacheEntry> {
        val result = LinkedHashMap<String, CacheEntry>()
        for (line in text.lineSequence()) {
            decodeLine(line)?.let { result[it.key] = it }
        }
        return result
    }
}

/**
 * 译文缓存。
 *
 * 形态是「追加写的 JSONL + 内存索引」。追加写的好处是**崩了也不丢已付过钱的结果**
 * ——每批拿到译文就落盘，用户中途退出、进程被杀，下次都是接着用。
 * 代价是文件会变长，所以到了阈值就压一次（重写成当前索引）。
 */
class TranslationCacheStore(
    private val file: File,
    private val dispatchers: DispatcherProvider,
) {
    private val mutex = Mutex()
    private var index: MutableMap<String, CacheEntry>? = null
    private var linesOnDisk = 0

    suspend fun lookup(keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        return withContext(dispatchers.io) {
            mutex.withLock {
                val loaded = ensureLoaded()
                keys.mapNotNull { key -> loaded[key]?.let { key to it.translation } }.toMap()
            }
        }
    }

    /**
     * 一次性取回「这份字幕里己经有缓存」的行（cues 下标 → 译文）。
     *
     * 用它把翻译过的片子**一打开就显示出来**，而不是让用户以為译文没了、
     * 又点一次翻译（那一次虽然会全部命中缓存、不花钱，但用户不知道）。
     *
     * key 里包含模型/目标语言/术语表指纹，所以换一个目标语言重开，这里会正确地全部落空。
     */
    suspend fun lookupDocument(
        cues: List<SubtitleCue>,
        model: String,
        target: TranslationTarget,
        glossary: Glossary,
    ): Map<Int, String> {
        if (cues.isEmpty()) return emptyMap()
        val fingerprint = glossary.fingerprint()
        // 用 cueText 而不是 cue.text：口径必须和分批/可翻下标一致，
        // 否则注释行和空行的 key 会白算一遍（还可能命中脏数据）。
        val keyed = cues.indices.mapNotNull { index ->
            cueText(cues, index)?.let { index to TranslationCacheCodec.key(model, target, fingerprint, it) }
        }
        if (keyed.isEmpty()) return emptyMap()

        val found = lookup(keyed.map { it.second })
        return keyed
            .mapNotNull { (index, key) -> found[key]?.let { index to it } }
            .toMap()
    }

    /** 写入若干条（已存在的 key 跳过）。返回本次实际新增的条数。 */
    suspend fun store(entries: Collection<CacheEntry>): Int {
        if (entries.isEmpty()) return 0
        return withContext(dispatchers.io) {
            mutex.withLock {
                val loaded = ensureLoaded()
                val fresh = entries.filter { it.key.isNotBlank() && !loaded.containsKey(it.key) }
                if (fresh.isEmpty()) return@withLock 0

                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(
                        fresh.joinToString(separator = "\n", postfix = "\n") {
                            TranslationCacheCodec.encodeLine(it)
                        },
                        Charsets.UTF_8,
                    )
                    linesOnDisk += fresh.size
                    fresh.forEach { loaded[it.key] = it }
                    if (linesOnDisk > compactThreshold(loaded.size)) compact(loaded.values)
                }.onFailure {
                    // 缓存写失败不该让翻译失败：结果已经在内存里，用户照样能看到译文。
                    MspLog.w(TAG, it) { "写入缓存失败：${file.absolutePath}" }
                }
                fresh.size
            }
        }
    }

    suspend fun size(): Int = withContext(dispatchers.io) {
        mutex.withLock { ensureLoaded().size }
    }

    /**
     * 当前缓存规模。给设置页的「清空翻译缓存」那一行用。
     *
     * 走 [ensureLoaded]，顺带做一次「文件超大就重建」的自检：打开设置页时
     * 就把它处理掉，比等下次翻译时才发现好。代价是把缓存读进内存
     * （上限 [MAX_CACHE_BYTES]），而翻译过的会话里它本来就已经在内存里了。
     */
    suspend fun stats(): TranslationCacheStats = withContext(dispatchers.io) {
        mutex.withLock {
            TranslationCacheStats(ensureLoaded().size, onDiskBytes())
        }
    }

    /**
     * 删掉缓存并清空内存索引。**返回是否真的删掉了。**
     *
     * 失败时**不清索引**：以前这里是「删不掉也照样把索引清空」，于是界面显示
     * 「已清空」、下次翻译却全部命中旧缓存（钱是省了，但用户唯一的结论是
     * 「这个按钮是假的」）。索引留着，界面才能诚实地告诉他「还剩 128 条」。
     *
     * 不碰 `translation_edits/`：那是用户手动改过的译文，是**内容**不是缓存。
     * 清缓存顺便把人工校对的成果也清掉，等于把用户几小时的活一次抹平，
     * 而他从确认对话框里的那句「清空缓存」根本想不到会这样。
     */
    suspend fun clear(): Boolean = withContext(dispatchers.io) {
        mutex.withLock {
            // 顺手删掉上次压缩失败留下的临时文件：它和主文件加起来才是真正占的地方，
            // 留着它会让「已清空」之后统计里还挂着一块体积。
            val removed = listOf(file, tempFile())
                .filter { it.exists() }
                .map { target ->
                    runCatching { target.delete() }
                        .onFailure { MspLog.w(TAG, it) { "清空缓存失败：${target.absolutePath}" } }
                        .getOrDefault(false)
                }
                .all { it }
            if (!removed) return@withLock false
            index = mutableMapOf()
            linesOnDisk = 0
            true
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun ensureLoaded(): MutableMap<String, CacheEntry> {
        index?.let { return it }
        val loaded = LinkedHashMap<String, CacheEntry>()
        var lines = 0
        if (file.exists()) {
            if (file.length() > MAX_CACHE_BYTES) {
                // 缓存文件异常大（磁盘被别的东西写满了、或以前有 bug 疯狂追加）。
                // 直接丢掉重新开始，比 OOM 好——缓存本来就是可再生数据。
                MspLog.w(TAG) {
                    "缓存文件 ${file.length()} 字节，超过上限，直接重建：${file.absolutePath}"
                }
                runCatching { file.delete() }
            } else {
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
                    MspLog.w(TAG, it) { "读缓存失败" }
                    ""
                }
                loaded.putAll(TranslationCacheCodec.decodeAll(text))
                lines = text.count { it == '\n' }
            }
        }
        index = loaded
        linesOnDisk = lines
        return loaded
    }

    private fun compactThreshold(size: Int): Int =
        maxOf(MIN_COMPACT_LINES, size + size / 2)

    /** 真正占的字节数：主文件 + 上次压缩留下的临时文件（见 [compact]）。 */
    private fun onDiskBytes(): Long = file.length() + tempFile().length()

    private fun tempFile(): File = File(file.parentFile, file.name + TEMP_SUFFIX)

    private fun compact(entries: Collection<CacheEntry>) {
        val temp = tempFile()
        temp.writeText(
            entries.joinToString(separator = "\n", postfix = "\n") {
                TranslationCacheCodec.encodeLine(it)
            },
            Charsets.UTF_8,
        )
        if (file.exists() && !file.delete()) {
            MspLog.w(TAG) { "压缩缓存时无法删除旧文件" }
            temp.delete()
            return
        }
        if (!temp.renameTo(file)) {
            MspLog.w(TAG) { "压缩缓存时改名失败，缓存会继续追加" }
        }
        linesOnDisk = entries.size
    }

    companion object {
        const val FILE_NAME = "translation_cache.jsonl"

        /** 压缩时用的临时文件后缀（[compact] 与 [onDiskBytes] 必须用同一个）。 */
        const val TEMP_SUFFIX = ".tmp"

        /** 超过这个体积就直接重建。8 MB ≈ 数万条译文，正常远达不到。 */
        const val MAX_CACHE_BYTES = 8L * 1024 * 1024

        /** 少于这么多行就不值得压缩（重写整个文件也是有成本的）。 */
        const val MIN_COMPACT_LINES = 64
    }
}
