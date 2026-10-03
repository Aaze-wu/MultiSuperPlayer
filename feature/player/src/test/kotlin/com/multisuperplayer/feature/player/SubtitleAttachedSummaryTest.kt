package com.multisuperplayer.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「当前挂着哪条」那一格在**什么都没挂**时说哪句话。
 *
 * ## 钉住的是一个真实看到的矛盾
 *
 * 只有一句话的时候，用户在同一个面板上同时看到：
 *
 * - 上面：「当前没有挂上任何字幕。」
 * - 下面：「片源自带的字幕」分区里列着两条可选轨。
 *
 * 这两句话在字面之外还互相拆台——上面那句是**全局断言**（这个片子里没有字幕），
 * 而下面明明列着片源里的字幕。实测（`multi.mkv`，40 秒片段、字幕从第 23 秒起）
 * 这个窗口有 **4 秒多**：内嵌轨在 `onTracksChanged` 时就已经知道（候选列表里有了），
 * 但第一句台词要等播放头走到有字幕的地方才到，其间 `embeddedTrack` 一直是 null。
 *
 * 代价不是难看：用户会去改字幕文件名，而字幕其实好好的，只是还没开口。
 *
 * 所以「片源里有没有字幕轨」必须由**另一个键**表达，这里就把这个分支钉在测试里。
 * 判据用 `embeddedTracks`（片源里有哪些可渲染的文本轨）而不是「哪条被选中了」：
 * 即使那条轨因为语言对不上而没被自动选中，下面候选列表里也列着它、用户可以自己点，
 * 此时说「没有字幕」仍然是错的。
 */
class SubtitleAttachedSummaryTest {

    @Test
    fun `片源里有字幕轨时说的是「还没读到台词」而不是「没有挂上任何字幕」`() {
        assertEquals(
            R.string.msp_player_embedded_no_cues_yet,
            nothingAttachedText(hasEmbeddedTracks = true),
        )
    }

    @Test
    fun `片源里一条可渲染的字幕轨也没有时才是「没有挂上外挂字幕文件」`() {
        assertEquals(
            R.string.msp_player_none_attached,
            nothingAttachedText(hasEmbeddedTracks = false),
        )
    }
}
