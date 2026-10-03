package com.multisuperplayer.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multisuperplayer.core.model.SubtitleCue

/**
 * 视频画面上的字幕层。
 *
 * 用 [com.multisuperplayer.core.model.SubtitleDocument.cueAt]（空档返回 null）而不是
 * `cueFocusedAt`：这是**字幕**，两句话之间没有台词的时候就该什么都没有。
 * 用 `cueFocusedAt` 会让上一句台词一直挂到下一句出现，静默段落里看起来像是卡住了。
 *
 * 位置固定在画面底部（离底边多远由用户的「底部距离」档位决定）。ASS 的逐句位置
 * （`\pos`/`\an`）在 [com.multisuperplayer.core.model.SubtitleCue.position] 里已经解析好了，
 * 但按用户偏好重排是另一件事，还没做。
 */
@Composable
internal fun SubtitleOverlay(
    state: SubtitleUiState,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    if (!state.isRendering) return

    val document = state.document ?: return
    // 查 cue 和逐字高亮必须用**同一个**时刻：两者用不同的时刻会让逐字高亮
    // 跑到下一句上去（唱到一半整行换掉）。所以这里只算一次。
    val cuePositionMs = subtitleCuePosition(
        positionMs = positionMs,
        timelineOffsetMs = state.timelineOffsetMs,
        ratePermille = state.subtitleRatePermille,
    )
    val cue = document.cueAt(cuePositionMs) ?: return

    val lines = cueLinesFor(cue, state.effectiveMode)
    if (lines.isEmpty) return

    val typography = MaterialTheme.typography
    // 原文和译文用各自的主题字级当基准（原文更大一档），档位倍率乘在上面。
    // 两者的**描边宽度**是同一个值（它只取决于档位），所以下面只传原文那一份。
    val original = subtitleGeometry(
        style = state.style,
        baseFontSizeSp = typography.titleMedium.fontSize.value,
        baseLineHeightSp = typography.titleMedium.lineHeight.value,
    )
    val translation = subtitleGeometry(
        style = state.style,
        baseFontSizeSp = typography.bodyLarge.fontSize.value,
        baseLineHeightSp = typography.bodyLarge.lineHeight.value,
    )

    CueTextBlock(
        lines = lines,
        cue = cue,
        positionMs = cuePositionMs,
        // 盖在视频上，所以配色不看主题：白字 + 黑描边在任意画面上都可读，
        // 而跟随主题的 onSurface 在深色画面上会直接消失。用户能调的是**字号 /
        // 行距 / 描边粗细 / 离底边多远**，不包含颜色——「字幕看不清」的解法
        // 不应该包括「选一个刚好能看清的颜色」。
        originalStyle = typography.titleMedium.copy(
            fontSize = original.fontSizeSp.sp,
            lineHeight = original.lineHeightSp.sp,
            shadow = SUBTITLE_SHADOW,
        ),
        originalColor = Color.White,
        // 逐字高亮时未唱的部分要暗一截，否则「高亮」根本看不出来——
        // LRC/ASS 里带逐字标签的段落很多，全都白成一片等于没有这个功能。
        karaokeBaseColor = Color.White.copy(alpha = 0.65f),
        karaokeHighlightColor = Color.White,
        translationStyle = typography.bodyLarge.copy(
            fontSize = translation.fontSizeSp.sp,
            lineHeight = translation.lineHeightSp.sp,
            shadow = SUBTITLE_SHADOW,
        ),
        translationColor = Color.White.copy(alpha = 0.85f),
        textAlign = TextAlign.Center,
        strokeWidthDp = original.strokeWidthDp,
        // 底部距离由样式决定，所以**不能**再留在 `PlayerScreen` 的 `padding(vertical = ...)`
        // 里：那里只该管「水平 16dp」这种与样式无关的留白。
        modifier = modifier.padding(bottom = original.bottomPaddingDp.dp),
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
 * @param strokeWidthDp 描边时给 `Stroke` 的宽度（0 = 不描边），见 [SubtitleGeometry]。
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
    strokeWidthDp: Float = 0f,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        lines.original?.let { original ->
            val pieces = cue?.let { karaokePieces(it, positionMs) }.orEmpty()
            if (pieces.isEmpty()) {
                val text = AnnotatedString(original)
                SubtitleLine(
                    text = text,
                    style = originalStyle,
                    color = originalColor,
                    textAlign = textAlign,
                    strokeWidthDp = strokeWidthDp,
                )
            } else {
                // 整段文本原样拼回去（片段的 text 合起来就是 cue.text），
                // 只换每段的颜色，所以排版和没有逐字信息时完全一致。
                //
                // 描边层要单独拼一份**全黑**的：逐字高亮用的是 AnnotatedString
                // 里的 span 颜色，而 span 颜色会盖过 `Text(color = ...)` 参数，
                // 直接沿用彩色那份会得到一圈「跟着高亮变色」的描边——高亮走到哪里
                // 哪里就看不见边。
                SubtitleLine(
                    text = buildKaraokeText(
                        pieces = pieces,
                        baseColor = karaokeBaseColor,
                        highlight = karaokeHighlightColor,
                    ),
                    style = originalStyle,
                    textAlign = textAlign,
                    strokeWidthDp = strokeWidthDp,
                    outlineText = buildKaraokeText(
                        pieces = pieces,
                        baseColor = SUBTITLE_OUTLINE_COLOR,
                        highlight = SUBTITLE_OUTLINE_COLOR,
                    ),
                )
            }
        }

        lines.translation?.let { translation ->
            SubtitleLine(
                text = AnnotatedString(translation),
                style = translationStyle,
                color = translationColor,
                textAlign = textAlign,
                strokeWidthDp = strokeWidthDp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 一行字幕。描边宽度为 0 时就是一个普通的 [Text]；大于 0 时叠**两层**：
 * 下面一层用 [Stroke] 把同样的文字描黑，上面一层照常填色。
 *
 * ## 为什么要叠两层（而不是 `TextStyle` 上的某个属性）
 *
 * Compose 的 `TextStyle` 没有「描边」这个概念：`drawStyle = Stroke(...)` 是把字形
 * **画成空心轮廓**，不是给实心字加一圈边。所以「实心字 + 一圈边」只能靠同一段文字
 * 画两次——描边那次在下，填色那次在上把内部盖住。
 *
 * 两层必须在**同一个** [Box] 里、用**同一份** style（只差 `drawStyle` / `shadow` / 颜色）：
 * 字号、行高、换行位置因此完全一致，上面那层才能严丝合缝地盖在描边上。
 * 各画各的（比如一个在 Column 里一个在 Box 里）会因为宽度约束不同而在长行换行处错位。
 *
 * @param text 填色层的文字。逐字高亮时它的颜色在 span 里。
 * @param outlineText 描边层的文字，只在有描边时用到。默认与 [text] 相同——
 *   没有逐字信息时两者本来就一样，有逐字信息时调用方必须传一份全黑的（见 [CueTextBlock]）。
 */
@Composable
private fun SubtitleLine(
    text: AnnotatedString,
    style: TextStyle,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    strokeWidthDp: Float = 0f,
    outlineText: AnnotatedString = text,
) {
    // 写成 `!(x > 0f)` 而不是 `x <= 0f`：后者对 NaN 会走到描边分支（NaN 比较一律为假），
    // 而 `Stroke(NaN)` 画出来的东西没人测过。
    if (!(strokeWidthDp > 0f)) {
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = textAlign,
            modifier = modifier,
        )
        return
    }

    val strokePx = with(LocalDensity.current) { strokeWidthDp.dp.toPx() }
    Box(modifier = modifier) {
        Text(
            text = outlineText,
            // 描边层不要再叠阴影：阴影属于「实心字」，两层各投一次会让边发糊。
            style = style.copy(drawStyle = Stroke(width = strokePx), shadow = null),
            color = SUBTITLE_OUTLINE_COLOR,
            textAlign = textAlign,
        )
        Text(
            text = text,
            style = style,
            color = color,
            textAlign = textAlign,
        )
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

/** 描边的颜色。不跟主题：字幕盖在任何画面上，黑边是唯一稳定可读的选择。 */
private val SUBTITLE_OUTLINE_COLOR = Color.Black
