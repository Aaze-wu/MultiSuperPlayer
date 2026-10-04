package com.multisuperplayer.feature.player

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.player.EmbeddedPreReadReason
import com.multisuperplayer.core.player.EmbeddedPreReadState
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspTrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内嵌字幕轨的**文案**里那一格「格式」。
 *
 * ## 为什么值得单独钉住
 *
 * 设备上实测（`multi.mkv`，两条 subrip 轨）Media3 报的是：
 *
 * ```
 * kind=TEXT mime='application/x-media3-cues' codec=application/x-subrip lang=zh
 * ```
 *
 * 也就是说 `mimeType` 是 Media3 自己的 cue 包，格式在 `codecs` 里。谁要是直接
 * 用 `subtitleFormatOf(mimeType)`（不认识的 MIME → [SubtitleFormat.UNKNOWN]），
 * 屏幕上就会写「未知 · zh · 默认」——这一格是用户判断「要不要挂这条」的依据，
 * 写「未知」等于把它抽掉了。
 *
 * 断言方式是直接和**同一套拼接函数**拼出来的期望值比：这样钉住的是
 * 「格式那一格是哪一条」，而不是重抄一遍实现里的字符串顺序。
 *
 * ## 另外两处（同一块面板上的另外两行）
 *
 * 标题位 [embeddedTitle] 与「还没读到台词」那行小字 [embeddedStatusDetails] 也在这里，
 * 因为这三处都在回答同一个问题：**用户凭什么判断「要不要挂这条轨」**。
 * 三处各有各的坑：格式那格会写成「未知」（MIME 在 codecs 里）、标题位会写成 `zh`
 * （语言标签顶替了名字）、小字那句会在读到台词之后还留着（进行时被当成了状态）。
 */
class SubtitleEmbeddedTrackTextTest {

    @Test
    fun `格式取自 codecs 而不是显示未知`() {
        val track = embedded(
            mimeType = "application/x-media3-cues",
            codec = "application/x-subrip",
            isDefault = true,
        )

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    // `SubRip` 而不是 `MspText.unknown()`：差别就是这一条测试的意义。
                    SubtitleFormat.SRT.label(),
                    MspText.Plain("zh"),
                    MspText.Res(R.string.msp_player_detail_default_track),
                ),
            ),
            track.describeDetails(),
        )
    }

    @Test
    fun `codecs 认不出时照实写未知`() {
        // cue 包 MIME 配一个我们没见过的 codecs：可渲染（Media3 认得），
        // 但格式那一格必须照实写「未知」——猜一个格式出来会让用户拿
        // 「为什么 SRT 渲染出来是乱的」来问。
        val track = embedded(mimeType = "application/x-media3-cues", codec = null)

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    SubtitleFormat.UNKNOWN.label(),
                    MspText.Plain("zh"),
                ),
            ),
            track.describeDetails(),
        )
    }

    @Test
    fun `内嵌文档的格式也取自 codecs`() {
        // 这里和上面是两处独立的取值：副标题在 `SubtitleIssueText`，文档在
        // `SubtitleViewModel.embeddedDocumentOf`。只修一处的话，界面上写着 SubRip、
        // 而导出/翻译那条路拿到的还是「未知格式」。
        val track = embedded(
            mimeType = "application/x-media3-cues",
            codec = "application/x-subrip",
        )

        val document = embeddedDocumentOf(track = track, cues = emptyList(), mediaUri = "file:///m.mkv")

        assertEquals(SubtitleFormat.SRT, document.track.format)
    }

    @Test
    fun `还没读到台词时在格式后面补一句进行时`() {
        // 内嵌轨是**边播边读**的：轨知道了、第一句台词还没到。这一段里界面上必须说
        // 「还没读到」而不是断言什么也没有——用户看到的是一个会自己变的状态，
        // 不是一句要他自己动手的提示（那一句在 `nothingAttachedText` 里，另一个位置）。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/x-subrip")

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    track.describeDetails(),
                    MspText.Res(R.string.msp_player_embedded_lines_pending),
                ),
            ),
            embeddedStatusDetails(track, cueCount = 0),
        )
    }

    @Test
    fun `一读到台词那句话就必须消失`() {
        // 补的话是进行时，不是状态：「尚未读到台词」在多读一行之后继续显示的话，
        // 每一帧都在说一件已经不成立的事。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/x-subrip")

        assertEquals(track.describeDetails(), embeddedStatusDetails(track, cueCount = 1))
        assertEquals(track.describeDetails(), embeddedStatusDetails(track, cueCount = 42))
    }

    @Test
    fun `整轨预读跑着的时候说正在预读而不是尚未读到`() {
        // 两句话说的是两件事，而用户的动作完全不同：
        // 「尚未读到台词」= 现在还没有（而它自己在变）；
        // 「正在预读字幕」= 我们在读整个文件（大文件要读一会儿）。
        // 后者不能没有——没有它那几秒看起来就是「字幕坏了」。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/x-subrip")

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    track.describeDetails(),
                    MspText.Res(R.string.msp_player_embedded_prereading),
                ),
            ),
            embeddedStatusDetails(track, cueCount = 0, preRead = EmbeddedPreReadState.Reading),
        )
    }

    @Test
    fun `预读失败时即使已经读到台词也要说一句`() {
        // 这一句不只是安慰：字幕速率是按**整表**换算查询位置的
        // （见 `subtitleCuePosition`），预读失败时用的是越播越少、末尾悬在
        // `Long.MAX_VALUE` 的流式表——「调快没反应」就是从这里来的。
        // 不说这一句，用户只能自己猜为什么调了没反应。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/x-subrip")

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    track.describeDetails(),
                    MspText.Res(R.string.msp_player_embedded_preread_failed),
                ),
            ),
            embeddedStatusDetails(
                track,
                cueCount = 120,
                preRead = EmbeddedPreReadState.Failed(EmbeddedPreReadReason.READ_ERROR),
            ),
        )
    }

    @Test
    fun `预读成功时回到原来的样子不多说一句`() {
        // `Ready` 之后「尚未读到台词」必须消失，而且**不能**换成另一句：
        // 整表已经在手，面板上没有任何要解释的事。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/x-subrip")

        assertEquals(
            track.describeDetails(),
            embeddedStatusDetails(
                track,
                cueCount = 1,
                preRead = EmbeddedPreReadState.Ready(listOf(cue(0))),
            ),
        )
    }

    private fun cue(index: Int) = SubtitleCue(
        index = index,
        startMs = index * 1_000L,
        endMs = index * 1_000L + 900L,
        text = "line $index",
    )

    @Test
    fun `标题位不写语言标签`() {
        // 设备上实测（`embedded-test.mkv`，一条 `chi` 轨）：`label` 是 null、`language`
        // 是 `zh`，而 `displayLabel` 的兜底会把它原样写到标题位。结果是
        //
        //     zh
        //     SubRip · zh · 默认 · 尚未读到台词
        //
        // 标题位重复小字里的语言，而且给的是语言**标签**这种代码——看起来像
        // 「这个界面不知道这条轨叫什么」。语言归小字那行，标题位说「第几条」。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/x-subrip")

        assertEquals(
            MspText.Res(R.string.msp_player_embedded_track, 1),
            track.embeddedTitle(),
        )
    }

    @Test
    fun `序号从一开始数是给用户看的那一个`() {
        // `indexInGroup` 是 0 基的（Media3 给的原始下标），界面上必须 1 基：
        // 「内嵌字幕 0」是程序员才看得懂的编号。
        val track = embedded(
            mimeType = "application/x-media3-cues",
            codec = "application/x-subrip",
            indexInGroup = 2,
        )

        assertEquals(MspText.Res(R.string.msp_player_embedded_track, 3), track.embeddedTitle())
    }

    @Test
    fun `轨自带名字时用它的名字`() {
        // 有些 mkv 会给轨道写 label（「简体中文」）——那是别人给这条轨起的名字，
        // 比序号有用得多。
        val track = embedded(
            mimeType = "application/x-media3-cues",
            codec = "application/x-subrip",
            label = "简体中文",
        )

        assertEquals(MspText.Plain("简体中文"), track.embeddedTitle())
    }

    @Test
    fun `名字是空白时退回序号`() {
        // 空白 label 与没有 label 是一回事，别在标题位上留一格空白。
        val track = embedded(
            mimeType = "application/x-media3-cues",
            codec = "application/x-subrip",
            label = "   ",
        )

        assertEquals(MspText.Res(R.string.msp_player_embedded_track, 1), track.embeddedTitle())
    }

    // ------------------------------------------------------------ 位图轨（v0.9）

    @Test
    fun `位图轨不说尚未读到台词`() {
        // 这一句是 v0.9 之前真正会出现在屏幕上的东西：PGS / VobSub / DVB 轨的
        // `cueCount` **恒为 0**（内核在位图这条路上给的是「此刻该显示什么」，
        // 放完就空，不是逐条累积），于是「尚未读到台词」会一直挂在那里不消失——
        // 而画面里字幕正在正常显示。用户会去查一个根本没坏的东西。
        //
        // 位图轨在这一格该说的是它自己的事：时间轴 / 速率那两根滑块就在同一块面板
        // 下面，拖了不会有任何变化。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/pgs")

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    track.describeDetails(),
                    MspText.Res(R.string.msp_player_embedded_bitmap_note),
                ),
            ),
            embeddedStatusDetails(track, cueCount = 0),
        )
    }

    @Test
    fun `位图那一条要排在预读分支前面`() {
        // 顺序在这里是**承重**的，和 `isTextRenderable` 里那两行的道理一样。
        // 位图轨不会预读（预读的门槛是 `isTextRenderable()`），所以「位图轨 +
        // 预读中」是一个不会出现的组合——正因为不会出现，把位图分支挪到 `when`
        // 后面时没有任何东西会报错，只有这一条会红。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/vobsub")

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    track.describeDetails(),
                    MspText.Res(R.string.msp_player_embedded_bitmap_note),
                ),
            ),
            embeddedStatusDetails(track, cueCount = 0, preRead = EmbeddedPreReadState.Reading),
        )
    }

    @Test
    fun `位图轨的格式取自 codecs 且不是未知`() {
        // 位图 MIME 现在同时被两条路认：文本层用它**排掉**位图轨
        // （`isTextRenderable` 第一行），格式那一格用它**认出**格式。
        // 只改前一处的话，位图轨会被排掉、又在格式格里写「未知」——
        // 而它明明是可渲染的，屏幕上什么都不会提示。
        val track = embedded(mimeType = "application/x-media3-cues", codec = "application/pgs")

        assertEquals(
            MspText.join(
                SUBTITLE_DETAIL_SEPARATOR,
                listOf(
                    SubtitleFormat.PGS.label(),
                    MspText.Plain("zh"),
                ),
            ),
            track.describeDetails(),
        )
        assertTrue("位图轨必须在可选清单里", track.isRenderableSubtitle())
        assertFalse("但不能走文本层", track.isTextRenderable())
    }

    private fun embedded(
        mimeType: String,
        codec: String?,
        isDefault: Boolean = false,
        label: String? = null,
        indexInGroup: Int = 0,
    ) = MspTrackInfo(
        id = "TEXT:3:0",
        kind = MspTrackKind.TEXT,
        label = label,
        language = "zh",
        mimeType = mimeType,
        codec = codec,
        indexInGroup = indexInGroup,
        isDefault = isDefault,
    )
}
