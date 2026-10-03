package com.multisuperplayer.feature.player

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspTrackKind
import org.junit.Assert.assertEquals
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

    private fun embedded(
        mimeType: String,
        codec: String?,
        isDefault: Boolean = false,
    ) = MspTrackInfo(
        id = "TEXT:3:0",
        kind = MspTrackKind.TEXT,
        language = "zh",
        mimeType = mimeType,
        codec = codec,
        isDefault = isDefault,
    )
}
