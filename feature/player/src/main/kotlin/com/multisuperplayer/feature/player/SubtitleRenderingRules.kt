package com.multisuperplayer.feature.player

import com.multisuperplayer.core.data.settings.SubtitleDisplayMode
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.karaokeTimeline

/**
 * 一条 cue 在给定显示模式下**实际要画的两行**。
 *
 * `null` 表示这一行不画。之所以要有个类型来回答这个问题，而不是让每个渲染点
 * 自己判断：判断散落在字幕覆盖层、歌词页、小窗预览三处时，一定会有一处漏掉
 * 「没有译文」的分支，症状就是**屏幕上出现一片空白**，而用户从界面上完全看不出
 * 这是「这条字幕没有译文」还是「字幕加载失败」。
 */
internal data class CueLines(
    val original: String?,
    val translation: String?,
) {
    val isEmpty: Boolean get() = original == null && translation == null
}

/**
 * 决定哪几行要画。
 *
 * 三条规则，按优先级：
 *
 * 1. [SubtitleDisplayMode.OFF] 一律不画。
 * 2. 选了「仅译文」但**这一条**没有译文 → 画原文。注意是逐条的：
 *    一份字幕可能只翻译了一半，那半边也得有东西看。
 * 3. 有译文时，「双语」画两行，「仅译文」只画译文。
 *
 * 这个函数是纯的，而且要一直保持纯——它是「不能出现空白字幕」这条规则唯一的
 * 落点，纯函数才能把它钉进测试。
 */
internal fun cueLinesFor(cue: SubtitleCue, mode: SubtitleDisplayMode): CueLines {
    if (mode == SubtitleDisplayMode.OFF) return CueLines(null, null)

    val original = cue.text
    val translation = cue.translation?.takeIf { it.isNotBlank() }

    return when (mode) {
        SubtitleDisplayMode.ORIGINAL_ONLY -> CueLines(original, null)

        // 没有译文就退回原文，绝不返回一行空白。
        SubtitleDisplayMode.TRANSLATION_ONLY -> CueLines(
            original = if (translation == null) original else null,
            translation = translation,
        )

        SubtitleDisplayMode.BILINGUAL -> CueLines(original, translation)

        SubtitleDisplayMode.OFF -> CueLines(null, null)
    }
}

/** 逐字高亮切出来的一个片段。[highlighted] 表示这部分已经唱过了。 */
internal data class KaraokePiece(
    val text: String,
    val highlighted: Boolean,
)

/**
 * 把一行歌词按当前播放位置切成「已唱 / 未唱」的片段。
 *
 * 返回空列表 = 这一行没有逐字信息，整行同色渲染。
 *
 * ## 精度说明（为什么不做帧级平滑）
 *
 * 增强型 LRC 的 `<mm:ss.xx>` 本身就是**逐字**时间，相邻两字常常只差一两百毫秒。
 * 播放位置本身就按 200ms 上报（见 `MspPlaybackState` 的说明），所以再做一个
 * 每帧插值的平滑循环，收益只有「一次 tick 之内的那一小段」，代价是引入一个
 * 帧循环 + 绕过组合的可变状态。这里选择不做，等真的有人觉得卡再说——
 * 但每个片段**内部**仍然按已过时间比例切开，所以一个拖长的音节不会一直不亮。
 */
internal fun karaokePieces(cue: SubtitleCue, positionMs: Long): List<KaraokePiece> {
    val timeline = cue.karaokeTimeline()
    if (timeline.isEmpty()) return emptyList()

    return timeline.flatMap { segment ->
        splitByProgress(segment.text, sungProgressOf(segment.startMs, segment.durationMs, positionMs))
    }
}

/** 这一段唱了多少（0..1）。时长为 0 的片段没有「唱到一半」这个状态。 */
private fun sungProgressOf(startMs: Long, durationMs: Long, positionMs: Long): Float = when {
    durationMs <= 0L -> if (positionMs >= startMs) 1f else 0f
    positionMs <= startMs -> 0f
    positionMs >= startMs + durationMs -> 1f
    else -> (positionMs - startMs).toFloat() / durationMs
}

/**
 * 按比例把一段文本切成「已唱 / 未唱」两片。
 *
 * 按**字符数**而不是渲染宽度切：宽度的真实值要测量过才知道，而这里每 200ms 算一次；
 * 对歌词这种字符宽度接近的文字（中文）几乎完全准确，对拉丁文也只是首尾差一两个字母。
 */
private fun splitByProgress(text: String, progress: Float): List<KaraokePiece> = when {
    text.isEmpty() -> emptyList()
    progress >= 1f -> listOf(KaraokePiece(text, true))
    progress <= 0f -> listOf(KaraokePiece(text, false))
    else -> {
        var cut = (text.length * progress).toInt().coerceIn(0, text.length)
        // 不要从代理对中间切开：切出来的半个字符会渲染成「�」，
        // 而它只会在带 emoji 的那一行出现，属于「偶尔见到一次乱码」。
        if (cut > 0 && cut < text.length &&
            text[cut].isLowSurrogate() && text[cut - 1].isHighSurrogate()
        ) {
            cut -= 1
        }
        listOf(
            KaraokePiece(text.substring(0, cut), true),
            KaraokePiece(text.substring(cut), false),
        ).filter { it.text.isNotEmpty() }
    }
}
