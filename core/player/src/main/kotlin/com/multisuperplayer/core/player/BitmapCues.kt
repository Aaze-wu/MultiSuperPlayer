package com.multisuperplayer.core.player

import android.graphics.Bitmap
import androidx.media3.common.text.Cue

/**
 * 位图字幕（PGS / VobSub / DVB）——**画出来的**字幕。
 *
 * ## 和文本字幕是两条路
 *
 * 文本字幕（SRT/ASS/VTT/…）最后落到 `SubtitleDocument`，由 `SubtitleOverlay` 按
 * 用户的字号/描边/位置**重新排版**；位图字幕是片源里已经排好版的图片，我们能做的
 * 只是把它摆到它自己声明的那个位置上去。所以两者共用一条「什么时候该显示」的
 * 时间轴，却不可能共用渲染代码。
 *
 * ## 为什么不是 `Application/X-PGS` 那种外部文件
 *
 * `.sup` / `.idx+.sub` 这些外挂位图字幕**没有**实现：它们没有时间轴文件可以扫、
 * 也就没有「挂在片源上」的入口。这里处理的只有容器里内嵌的那些。
 *
 * ## 为什么「延迟 / 速率」对它们不生效
 *
 * 字幕延迟和速率是把「播放位置」换算成「查表位置」（见 `subtitleCuePosition`），
 * 前提是手里有**整条轨道的台词表**——那张表是预读出来的（见 [EmbeddedPreReadState]）。
 * 位图轨不能预读：一张 1080p 的字幕图动辄几百 KB，把整轨留在内存里是几百 MB。
 * 于是位图只能「内核给什么就画什么」，用播放器自己的时刻。
 *
 * 这不是个可以糊过去的差异：速率调到 1.1× 播一部两小时的片子，累积漂移有十几分钟，
 * 拿流式到达的图去凑只会显示**错的那一张**（比不调还糟）。所以字面意义上「不支持」，
 * 并且要在界面上说出来。
 */
data class EmbeddedBitmapCue(
    /**
     * 解好的图片。
     *
     * **调用方不要改它、也不要 `recycle()`**：它同时挂在 Media3 的 cue 上，回收会让
     * 内核下一次画它时崩掉。这一层只读。
     */
    val image: Bitmap,
    /** 摆放它需要的几何信息（见 [BitmapCueSpec]）。 */
    val spec: BitmapCueSpec,
)

/**
 * 一张位图字幕的**位置与尺寸**，全部是画面宽/高的**比例**（不是像素、也不是 dp）。
 *
 * 值和 Media3 的 `Cue` 一一对应：容器（PGS 的 `0x16` 段、VobSub 的 `.idx`、DVB 的
 * 页面描述）写的就是「以字幕平面为基准的比例」，而字幕平面就是视频帧。所以拿
 * **画面矩形**去乘这些比例才是对的——这也正是布局算法（`feature:player` 那边的
 * `bitmapCueRect`）的输入。
 *
 * 认不出来的量一律折成 `null`（[size] / [bitmapHeight]），而不是留 Media3 那个
 * `DIMEN_UNSET = Float.MIN_VALUE` 的哨兵值：哨兵传下去之后，「没写」和「极小」
 * 会变成同一个数，而这个文件最不缺的就是「看起来正常的错值」。
 */
data class BitmapCueSpec(
    /** 横向锚点在画面里的位置（0 = 最左，1 = 最右）。没写时是 0（最左）。 */
    val position: Float = 0f,
    /** 横向锚点是 [position] 那一点，还是它的左/右边缘（见 [CueAnchor]）。 */
    val positionAnchor: CueAnchor = CueAnchor.START,
    /** 纵向锚点的位置（0 = 最上，1 = 最下）。没写时是 0（最上）。 */
    val line: Float = 0f,
    /** 纵向锚点的对齐方式。 */
    val lineAnchor: CueAnchor = CueAnchor.START,
    /** 宽度占画面宽的比例。`null` = 容器没写（这种 cue 画不出来，跳过）。 */
    val size: Float? = null,
    /** 高度占画面高的比例。`null` = 容器没写，按图片自己的宽高比推。 */
    val bitmapHeight: Float? = null,
)

/**
 * 锚点的对齐方式——`Cue` 的 `ANCHOR_TYPE_*`。
 *
 * 只有三档，而且**没有**「未设置」这一档：Media3 自己的排版（`SubtitlePainter`）拿到
 * 认不出来的值时一律按起点处理，这里照抄它的取舍，于是「未设置」在映射那一层就被
 * 折成 [START] 了（见 [toBitmapCueSpec]）。留一个 `UNSET` 的话，两个使用方都得再判一次，
 * 而且必然会有一处忘掉。
 */
enum class CueAnchor {
    /** 锚点即矩形的左边缘（横向）/ 上边缘（纵向）。 */
    START,

    /** 锚点是矩形的中心。 */
    MIDDLE,

    /** 锚点即矩形的右边缘（横向）/ 下边缘（纵向）。 */
    END,
}

/**
 * 此刻该显示的那几张位图字幕。
 *
 * ## 为什么是「几张」而不是「一张」
 *
 * DVB 的一条字幕可以由**多个区域**拼成（画面上一块角标 + 一行台词），Media3 会把它们
 * 放在**同一批** cue 里送过来。只留一张的话，那种片源上会稳定地少画一块。
 *
 * ## 为什么只有「此刻」
 *
 * 因为它是内核直接给的：`onCues` 的语义就是「现在该显示什么」，字幕放完时给的是一批
 * 空 cue。于是这个状态**不需要**开始/结束时间，也就不会出现「上一张忘记收走」——
 * 那种错在屏幕上表现为「字幕糊成一片」，而没有任何一行日志会提到它。
 */
data class EmbeddedBitmapState(
    val cues: List<EmbeddedBitmapCue> = emptyList(),
)

/**
 * 从一批 cue 里挑出位图字幕。
 *
 * 文本 cue 会被自然跳过（它们的 `bitmap` 是 null），所以这个函数可以无条件地作用在
 * `CueGroup.cues` 上，不必先问「这条轨是不是位图轨」。
 *
 * 顺带说一句：`Cue` 里 `text` 和 `bitmap` **互斥**（`setBitmap` 会把 `text` 置空，
 * 反之亦然），所以不存在「一张既有字又有图的 cue」这种需要取舍的情况。
 */
internal fun List<Cue>.toEmbeddedBitmapCues(): List<EmbeddedBitmapCue> =
    mapNotNull { cue -> cue.toEmbeddedBitmapCue() }

/** 这一条 cue 是位图字幕就映射出来，否则 null（文本 cue、空 cue 都是 null）。 */
internal fun Cue.toEmbeddedBitmapCue(): EmbeddedBitmapCue? {
    val bitmap = bitmap ?: return null
    // 宽或高为 0 的图直接被 Media3 丢弃过（`PgsParser.CueBuilder.build` 里有这个判断），
    // 但别的解析器（DVB）不保证，而 `drawImage` 拿到 0 尺寸时的行为是「不画」——
    // 与其留一张画不出来的图在列表里，不如在这里就摘掉，让「列表里有几张」等于
    // 「屏幕上该有几张」。
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    return EmbeddedBitmapCue(image = bitmap, spec = toBitmapCueSpec())
}

/**
 * 把 `Cue` 的几何量映射成 [BitmapCueSpec]。
 *
 * 只读几何，不碰 `bitmap` —— 于是这个函数可以在纯 JVM 单测里跑（`Cue.Builder` 是纯
 * Java，构造它不需要一个真的 `Bitmap`）。摆放算法那边（`feature:player` 的
 * `bitmapCueRect`）也是纯的，
 * 两者加起来才能把「位图字幕画在哪儿」钉在测试里，否则只能靠眼睛在模拟器上看一遍。
 */
internal fun Cue.toBitmapCueSpec(): BitmapCueSpec = BitmapCueSpec(
    // `DIMEN_UNSET`（= `Float.MIN_VALUE`）折成 0：Media3 的排版公式里它乘出来就是
    // 「一个约等于 0 的比例」，所以折成 0 与它的行为一致，但下游拿到的是一句
    // 说得通的话（「锚点在左上角」）而不是一个 1.4E-45 的怪数。
    position = dimensionOrZero(position),
    positionAnchor = anchorOf(positionAnchor),
    line = dimensionOrZero(line),
    lineAnchor = anchorOf(lineAnchor),
    // 尺寸这两个**保留** null：一个宽 0 的矩形和「没写宽度」在下游是同一种处置
    // （跳过），但它们在「文件到底说了什么」上是两回事，而排查位图字幕的问题时
    // 唯一能看的就是「文件说了什么」。
    size = dimensionOrNull(size),
    bitmapHeight = dimensionOrNull(bitmapHeight),
)

/** `DIMEN_UNSET` 和「没写」是同一个意思，见 `Cue.DIMEN_UNSET`。 */
private fun dimensionOrNull(value: Float): Float? =
    value.takeIf { it != Cue.DIMEN_UNSET }

private fun dimensionOrZero(value: Float): Float =
    if (value == Cue.DIMEN_UNSET || value.isNaN()) 0f else value

/**
 * `ANCHOR_TYPE_*` → [CueAnchor]。
 *
 * **认不出来的一律按起点**（含 Media3 的 `TYPE_UNSET = 0`），这是 `SubtitlePainter`
 * 自己的取舍：它的三元表达式只判 END / MIDDLE 两种，其余全落到「从锚点往右/往下长」。
 * 换一种取舍就会和「同一份文件在别的播放器里长什么样」对不上。
 */
private fun anchorOf(value: Int): CueAnchor = when (value) {
    Cue.ANCHOR_TYPE_MIDDLE -> CueAnchor.MIDDLE
    Cue.ANCHOR_TYPE_END -> CueAnchor.END
    else -> CueAnchor.START
}
