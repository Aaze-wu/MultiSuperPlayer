package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.SubtitleCue

/**
 * 一批要翻译的字幕行。
 *
 * @param cueIndices 本批各行在 `SubtitleDocument.cues` 里的下标。
 * @param texts 与 [cueIndices] 一一对应（已 trim）的原文。
 * @param contextBefore 上文若干行，**只给模型看、不要它翻**。
 *   代词和省略句（中文/日文字幕里极常见）离开上文根本没法翻，
 *   但「带上文一起翻」会让返回的行数对不上，所以必须明确区分「要翻的」和「参考的」。
 */
data class TranslationBatch(
    val cueIndices: List<Int>,
    val texts: List<String>,
    val contextBefore: List<String>,
)

/** 切批参数与上限。列成一个 object 是为了让「为什么是这个数」有个统一的地方写。 */
object TranslationBatching {

    /**
     * 一批 8 行。
     *
     * ## 拆批不省钱，只说清楚代价
     *
     * 每次请求都要重付一遍系统提示词（含 JSON Schema 和术语表）的输入开销——
     * 这是固定成本，实测能到上万 token 量级。所以「批越小越省钱」是反的：
     * 拆批买到的是**失败半径**（一批翻不出来只影响 8 行而不是 200 行）
     * 和「每一批都能装下」。行短的时候（歌词）可以调大，行长的时候（对白）调小。
     */
    const val DEFAULT_BATCH_SIZE = 8
    const val MIN_BATCH_SIZE = 1
    const val MAX_BATCH_SIZE = 64

    /** 一批的字符上限。行数合适但某行特别长时，按这个兜底。 */
    const val DEFAULT_MAX_CHARS = 1200
    const val MIN_MAX_CHARS = 200

    const val DEFAULT_CONTEXT_LINES = 2
    const val MAX_CONTEXT_LINES = 6

    /**
     * 上限纯粹是防御性的：输入异常（比如字幕文件有 20 万行）时不该让循环
     * 把手机拖到 ANR。正常 24 分钟一集大约 300–500 行 ⇒ 60 批左右。
     */
    const val MAX_BATCHES = 20_000
}

/**
 * 把要翻译的行切成批。纯函数。
 *
 * ## 为什么不是「全都翻」
 *
 * - `isComment` 是 ASS 的注释行，本来就**不渲染**，翻它等于按字数给钱买空气；
 * - 空行/纯空白行翻出来也是空行，还会把「行数对齐」搞坏（模型对空行的处理不稳定）。
 *
 * 所以被跳过的行**不算进「总数」**——进度条的分母必须是「真正要翻的行数」，
 * 否则一条片子永远停在 87% 再也不会动，用户会以为卡住了。
 *
 * @param pendingIndices 要翻的行；null = 全都要。
 */
fun planTranslationBatches(
    cues: List<SubtitleCue>,
    pendingIndices: List<Int>? = null,
    batchSize: Int = TranslationBatching.DEFAULT_BATCH_SIZE,
    maxChars: Int = TranslationBatching.DEFAULT_MAX_CHARS,
    contextLines: Int = TranslationBatching.DEFAULT_CONTEXT_LINES,
): List<TranslationBatch> {
    if (cues.isEmpty()) return emptyList()

    val perBatch = batchSize.coerceIn(
        TranslationBatching.MIN_BATCH_SIZE,
        TranslationBatching.MAX_BATCH_SIZE,
    )
    val charBudget = maxChars.coerceAtLeast(TranslationBatching.MIN_MAX_CHARS)
    val context = contextLines.coerceIn(0, TranslationBatching.MAX_CONTEXT_LINES)

    val order = (pendingIndices ?: cues.indices.toList())
        .filter { it in cues.indices }
        .distinct()
        .sorted()

    val usable = order.filter { cueText(cues, it) != null }
    if (usable.isEmpty()) return emptyList()

    val batches = ArrayList<TranslationBatch>()
    var currentIndices = ArrayList<Int>()
    var currentTexts = ArrayList<String>()
    var currentChars = 0

    fun flush() {
        if (currentIndices.isEmpty()) return
        if (batches.size >= TranslationBatching.MAX_BATCHES) return
        batches += TranslationBatch(
            cueIndices = currentIndices,
            texts = currentTexts,
            contextBefore = contextBefore(cues, currentIndices.first(), context),
        )
        currentIndices = ArrayList()
        currentTexts = ArrayList()
        currentChars = 0
    }

    for (index in usable) {
        val text = cueText(cues, index) ?: continue
        val cost = if (currentTexts.isEmpty()) text.length else text.length + 1
        val wouldOverflow = currentTexts.isNotEmpty() &&
            (currentTexts.size >= perBatch || currentChars + cost > charBudget)
        if (wouldOverflow) flush()
        currentIndices += index
        currentTexts += text
        currentChars += cost
    }
    flush()

    return batches
}

/** 这一行能拿去翻吗？不能就返回 null（注释行、空行）。 */
internal fun cueText(cues: List<SubtitleCue>, index: Int): String? {
    val cue = cues.getOrNull(index) ?: return null
    if (cue.isComment) return null
    return cue.text.trim().ifEmpty { null }
}

/**
 * 取 [firstIndex] 之前最近的可翻行作为上文。
 *
 * 只往回找**可翻行**：如果上文里混进注释行或空行，模型的注意力会被无意义的行分掉，
 * 而且空行还可能被它当成「这里该断句」的信号。
 */
internal fun contextBefore(cues: List<SubtitleCue>, firstIndex: Int, lines: Int): List<String> {
    if (lines <= 0) return emptyList()
    val result = ArrayList<String>(lines)
    var index = firstIndex - 1
    while (index >= 0 && result.size < lines) {
        cueText(cues, index)?.let { result += it }
        index--
    }
    return result.reversed()
}
