package com.multisuperplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字高亮时间轴的补全规则测试。
 *
 * 这里锁的是「最后一段时长为 0」——两个解析器（LRC / WebVTT）都会产出它，
 * 处理不好的症状是每行末尾几个字永远不高亮，而且前面每句都正常，极难发现。
 */
class KaraokeTimelineTest {

    private fun cue(
        startMs: Long,
        endMs: Long,
        vararg segments: Triple<Long, Long, String>,
    ) = SubtitleCue(
        index = 0,
        startMs = startMs,
        endMs = endMs,
        text = segments.joinToString("") { it.third },
        karaoke = segments.map { (segStart, segDuration, text) ->
            KaraokeSegment(startMs = segStart, durationMs = segDuration, text = text)
        },
    )

    @Test
    fun `最后一段时长为 0 时用整句结束时间补全`() {
        // `[00:10.00]<00:10.00>Hello <00:11.00>world` —— 第二段没有时长。
        val timeline = cue(
            10_000L, 13_000L,
            Triple(10_000L, 1_000L, "Hello "),
            Triple(11_000L, 0L, "world"),
        ).karaokeTimeline()

        assertEquals(1_000L, timeline[0].durationMs)
        assertEquals("最后一段扫到整句结束，而不是永远停在 0", 2_000L, timeline[1].durationMs)
        assertEquals(13_000L, timeline[1].endMs)
    }

    @Test
    fun `中间一段时长为 0 时用下一段的开始时间补全`() {
        val timeline = cue(
            0L, 5_000L,
            Triple(0L, 1_000L, "a"),
            Triple(1_000L, 0L, "b"),
            Triple(2_000L, 2_000L, "c"),
        ).karaokeTimeline()

        assertEquals(1_000L, timeline[1].durationMs)
        assertEquals(2_000L, timeline[1].endMs)
    }

    @Test
    fun `本来就有时长的片段原样保留`() {
        val original = cue(
            0L, 9_000L,
            Triple(0L, 3_000L, "a"),
            Triple(3_000L, 4_000L, "b"),
        )

        assertEquals(original.karaoke, original.karaokeTimeline())
    }

    @Test
    fun `整句也没有结束时间时保持 0，而不是变成负数`() {
        // 坏文件/坏行：endMs 早于 startMs 或者相等。
        val timeline = cue(
            5_000L, 5_000L,
            Triple(5_000L, 0L, "唯一一段"),
        ).karaokeTimeline()

        assertEquals(0L, timeline.single().durationMs)
        assertTrue("绝不能出现负时长，渲染层会拿它做除法", timeline.all { it.durationMs >= 0L })
    }

    @Test
    fun `没有逐字标记的普通字幕返回空列表`() {
        val plain = SubtitleCue(index = 0, startMs = 0L, endMs = 1_000L, text = "整行高亮")

        assertTrue(plain.karaokeTimeline().isEmpty())
    }

    @Test
    fun `补全后的时间轴首尾相接`() {
        // 渲染层会按「已唱完 / 正在唱 / 还没唱」三段来画，中间有洞就会出现
        // 一闪而过的未高亮字符。
        val timeline = cue(
            1_000L, 8_000L,
            Triple(1_000L, 2_000L, "第一"),
            Triple(3_000L, 0L, "第二"),
            Triple(5_000L, 0L, "第三"),
        ).karaokeTimeline()

        timeline.zipWithNext { current, next ->
            assertEquals("片段之间不能有空档", current.endMs, next.startMs)
        }
    }
}
