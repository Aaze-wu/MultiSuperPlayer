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
 * 但第一句台词要等播放头走到有字幕的地方才到——当时 `embeddedTrack` 要等到台词
 * 才认领，那一段窗口里它一直是 null（认领时机已经在 `withEmbedded` 里改掉了，
 * 见下一节）。
 *
 * 代价不是难看：用户会去改字幕文件名，而字幕其实好好的，只是还没开口。
 *
 * 所以「片源里有没有字幕轨」必须由**另一个键**表达，这里就把这个分支钉在测试里。
 * 判据用 `embeddedTracks`（片源里有哪些可渲染的文本轨）而不是「哪条被选中了」：
 * 即使那条轨因为语言对不上而没被自动选中，下面候选列表里也列着它、用户可以自己点，
 * 此时说「没有字幕」仍然是错的。
 *
 * ## 那句话后来被改成了「请选一条」
 *
 * 内嵌轨现在**在读到第一句台词之前就认领**（见 `withEmbedded` 的 KDoc），
 * 所以「有轨、但一条都没挂上」不再是「等一下就好」的中间态，而是**等用户动手**：
 * 内核那条保守规则没肯自动选中，用户坐在那里等的是一个永远不会自己发生的事。
 * 文案因此从「片源包含字幕轨，尚未读取到第一句台词。」改成「片源包含字幕轨，
 * 请在上面选一条。」——「还没读到台词」这件事移到了另一句
 * （`msp_player_embedded_lines_pending`，挂在已认领那条轨的详情行上，
 * 由 [embeddedStatusDetails] 决定要不要出现）。
 */
class SubtitleAttachedSummaryTest {

    @Test
    fun `片源里有字幕轨但一条都没挂上时说的是「请在上面选一条」`() {
        assertEquals(
            R.string.msp_player_embedded_choose_one,
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
