package com.multisuperplayer.core.translate

import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleDocument

/**
 * 这份字幕里「值得翻译」的行。
 *
 * 与 [planTranslationBatches] 的口径**必须一致**（都走 [cueText]）：
 * 进度条的分母、缓存条数、"还有几行没翻"的提示，全都靠这个列表算出来。
 * 两处口径一旦不同，用户就会看到「已译 300/300」但列表里还有几条是空的。
 */
fun SubtitleDocument.translatableIndices(): List<Int> = cues.indices.filter { cueText(cues, it) != null }

/**
 * 把译文合并进字幕。**纯函数，不改原对象**。
 *
 * ## 三态语义（这是关键）
 *
 * - map 里**不含**这个下标 ⇒ 保持原样（"我没动它"）；
 * - map 里含这个下标但值为空/空白 ⇒ **清掉**已有译文（"用户把它删了"）；
 * - map 里含且有内容 ⇒ 替换。
 *
 * 把「不含」和「含空串」混成一种，就会出现「用户手动删掉一条译文、
 * 一刷新又回来了」——因为"空"被当成"没设置"。三种状态需要三种形状。
 */
fun SubtitleDocument.withTranslations(translations: Map<Int, String>): SubtitleDocument {
    if (translations.isEmpty()) return this

    var changed = false
    val merged = cues.mapIndexed { index, cue ->
        if (!translations.containsKey(index)) {
            cue
        } else {
            val text = translations.getValue(index).trim()
            val next = text.ifEmpty { null }
            if (next == cue.translation) cue else {
                changed = true
                cue.copy(translation = next)
            }
        }
    }

    if (!changed) return this
    // 轨道元信息跟着更新 cueCount 之外没有别的变化；译文不改变轨道身份，
    // 所以这里不动 track —— 动它会让「正在挂的字幕轨道」看起来换了一条。
    return copy(cues = merged)
}

/** 已经有译文的行数（用于「已译 N/M」）。 */
fun List<SubtitleCue>.translatedCount(): Int = count { !it.translation.isNullOrBlank() }
