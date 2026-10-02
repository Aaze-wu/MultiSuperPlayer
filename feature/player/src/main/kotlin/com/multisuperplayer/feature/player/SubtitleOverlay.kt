package com.multisuperplayer.feature.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.multisuperplayer.core.model.SubtitleCue

/**
 * 视频画面上的字幕层。
 *
 * 用 [com.multisuperplayer.core.model.SubtitleDocument.cueAt]（空档返回 null）而不是
 * `cueFocusedAt`：这是**字幕**，两句话之间没有台词的时候就该什么都没有。
 * 用 `cueFocusedAt` 会让上一句台词一直挂到下一句出现，静默段落里看起来像是卡住了。
 *
 * 位置固定在画面底部。ASS 的逐句位置（`\pos`/`\an`）在
 * [com.multisuperplayer.core.model.SubtitleCue.position] 里已经解析好了，
 * 但按用户偏好重排是另一件事，和字幕样式一起放到后面做。
 */
@Composable
internal fun SubtitleOverlay(
    state: SubtitleUiState,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    if (!state.isRendering) return

    val document = state.document ?: return
    val cue = document.cueAt(positionMs) ?: return

    val lines = cueLinesFor(cue, state.effectiveMode)
    if (lines.isEmpty) return

    CueTextBlock(
        lines = lines,
        cue = cue,
        positionMs = positionMs,
        // 盖在视频上，所以配色不看主题：白字 + 黑描边在任意画面上都可读，
        // 而跟随主题的 onSurface 在深色画面上会直接消失。可自定义的字幕样式是
        // 后面的事，现在先保证「任何时候都看得清」。
        originalStyle = MaterialTheme.typography.titleMedium.copy(
            shadow = SUBTITLE_SHADOW,
        ),
        originalColor = Color.White,
        // 逐字高亮时未唱的部分要暗一截，否则「高亮」根本看不出来——
        // LRC/ASS 里带逐字标签的段落很多，全都白成一片等于没有这个功能。
        karaokeBaseColor = Color.White.copy(alpha = 0.65f),
        karaokeHighlightColor = Color.White,
        translationStyle = MaterialTheme.typography.bodyLarge.copy(
            shadow = SUBTITLE_SHADOW,
        ),
        translationColor = Color.White.copy(alpha = 0.85f),
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

/**
 * 画 [lines]。字幕层和歌词页共用它，因为「哪几行、逐字高亮到哪」这两个判断
 * 只该有一处实现——两处一定会有一处漏掉逐字高亮或多画一行空白。
 *
 * 颜色分成两组是必要的：[originalColor] 用于**整行同色**的情况（没有逐字信息），
 * [karaokeBaseColor] / [karaokeHighlightColor] 用于有逐字信息的情况。
 * 合成一个参数的话，「未唱的部分要暗一点」和「整行要看得清」会互相冲突。
 *
 * @param cue 有逐字信息时用它算高亮；译文不参与逐字（译文没有时间轴）。
 */
@Composable
internal fun CueTextBlock(
    lines: CueLines,
    cue: SubtitleCue?,
    positionMs: Long,
    originalStyle: TextStyle,
    originalColor: Color,
    karaokeBaseColor: Color,
    karaokeHighlightColor: Color,
    translationStyle: TextStyle,
    translationColor: Color,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        lines.original?.let { original ->
            val pieces = cue?.let { karaokePieces(it, positionMs) }.orEmpty()
            if (pieces.isEmpty()) {
                Text(
                    text = original,
                    style = originalStyle,
                    color = originalColor,
                    textAlign = textAlign,
                )
            } else {
                // 整段文本原样拼回去（片段的 text 合起来就是 cue.text），
                // 只换每段的颜色，所以排版和没有逐字信息时完全一致。
                Text(
                    text = buildKaraokeText(pieces, baseColor = karaokeBaseColor, highlight = karaokeHighlightColor),
                    style = originalStyle,
                    textAlign = textAlign,
                )
            }
        }

        lines.translation?.let { translation ->
            Text(
                text = translation,
                style = translationStyle,
                color = translationColor,
                textAlign = textAlign,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun buildKaraokeText(
    pieces: List<KaraokePiece>,
    baseColor: Color,
    highlight: Color,
): AnnotatedString = buildAnnotatedString {
    pieces.forEach { piece ->
        withStyle(SpanStyle(color = if (piece.highlighted) highlight else baseColor)) {
            append(piece.text)
        }
    }
}

private val SUBTITLE_SHADOW = Shadow(
    color = Color.Black.copy(alpha = 0.85f),
    offset = androidx.compose.ui.geometry.Offset(0f, 1f),
    blurRadius = 6f,
)
