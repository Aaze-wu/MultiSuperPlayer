package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动挂哪一条」的挑选规则测试。
 *
 * 自动挂载是整个链路里唯一**用户不会确认**的一步，所以它的门槛值得单独锁住。
 */
class SubtitleSourceSelectionTest {

    private fun score(
        media: String,
        subtitle: String,
        kind: MediaKind = MediaKind.VIDEO,
        format: SubtitleFormat = SubtitleFormat.SRT,
    ): Int = SubtitleFileNaming.associationScore(
        mediaFileName = media,
        subtitleFileName = subtitle,
        subtitleFormat = format,
        mediaKind = kind,
    )

    /**
     * 构造一条候选。
     *
     * [trailingTagCount] 默认**从文件名真实推导**而不是写死 0：写死的话测试里的数据
     * 和真实链路对不上（文件名叫 `Show.chs.srt` 却声称「片名之外没有标记」），
     * 新加的排序规则就会在一堆「看起来很真」的假数据上通过。
     */
    private fun source(
        fileName: String,
        matchScore: Int,
        languageTag: String? = null,
        isForced: Boolean = false,
        isBilingual: Boolean = false,
        trailingTagCount: Int = SubtitleFileNaming.analyze(fileName).trailingTagCount,
    ) = SubtitleSource(
        uri = "content://media/external/file/$fileName",
        fileName = fileName,
        format = SubtitleFormat.SRT,
        languageTag = languageTag,
        isForced = isForced,
        isBilingual = isBilingual,
        sizeBytes = 1_024L,
        matchScore = matchScore,
        trailingTagCount = trailingTagCount,
    )

    @Test
    fun `自动挂载门槛正好是「互为前缀」这一档`() {
        // 85 是「字幕名比媒体名更长」那一档（`Show.mkv` 配 `Show.1080p.BluRay.x265.chs.srt`）。
        //
        // AUTO_MATCH_SCORE 是写在另一个文件里的字面量。不锁住的话，改关联规则的档位时
        // 它不会跟着动，结果就是「自动挂载静默失效」或者「什么都能自动挂上」——
        // 两种都不会报错，只表现为字幕行为变了。
        assertEquals(
            AUTO_MATCH_SCORE,
            score("Show.mkv", "Show.1080p.BluRay.x265.chs.srt"),
        )
    }

    @Test
    fun `比这一档更弱的匹配分数确实低于门槛`() {
        // 反向锁：确认「名字更长的字幕」这一档是**最低的**自动挂载资格，
        // 再弱的关联（比如音频旁边的普通字幕）必须落在门槛之下。
        val subtitleForAudio = score("song.mp3", "song.srt", MediaKind.AUDIO, SubtitleFormat.SRT)

        assertTrue("名字一样就是对得上，必须能在手动列表里选到", subtitleForAudio > 0)
        assertTrue(
            "音频旁边的 .srt 多半是放错了文件夹，不该自动挂上",
            subtitleForAudio < AUTO_MATCH_SCORE,
        )
    }

    @Test
    fun `分数最高的候选被自动选中`() {
        val candidates = listOf(
            source("Show.S01E01.chs.srt", matchScore = 90),
            source("Show.S01E01.1080p.srt", matchScore = 100),
            source("Show.S01E01.eng.srt", matchScore = 90),
        )

        assertEquals("Show.S01E01.1080p.srt", candidates.bestAutoMatch()?.fileName)
    }

    @Test
    fun `同分时挑名字里标记最少的那条`() {
        // 这是实机上真的发生过的一例：三条字幕的关联分**完全相等**
        //（片名都等于媒体名），而按文件名字典序会挑中 `bilingual` 那条——
        // 只因为 `b` 比 `c`、`s` 靠前。名字和片子一模一样的那份才最可能是「这片子的字幕」。
        val candidates = listOf(
            source("TestClip.bilingual.srt", matchScore = 100, isBilingual = true),
            source("TestClip.chs.srt", matchScore = 100, languageTag = "zh-Hans"),
            source("TestClip.srt", matchScore = 100),
        )

        assertEquals("TestClip.srt", candidates.bestAutoMatch()?.fileName)
    }

    @Test
    fun `全片字幕优先于强制字幕`() {
        // forced 只覆盖外语对白那几句，挂上它大部分时间是空屏。
        // 旁边有完整字幕时选 forced 等于「明明有字幕却不显示」。
        val candidates = listOf(
            source("Show.forced.srt", matchScore = 100),
            source("Show.eng.srt", matchScore = 100, languageTag = "en"),
        )

        assertEquals("Show.eng.srt", candidates.bestAutoMatch()?.fileName)
    }

    @Test
    fun `标记更多但分数更高的候选依然优先`() {
        // 第 2 层（标记数）只能在**同分**时起作用。一旦它凌驾于分数之上，
        // 「名字正好等于片名」的杂牌文件就会排在真正匹配的版本前面。
        val candidates = listOf(
            source("Show.srt", matchScore = 90),
            source("Show.1080p.WEB-DL.chs.srt", matchScore = 100, languageTag = "zh-Hans"),
        )

        assertEquals("Show.1080p.WEB-DL.chs.srt", candidates.bestAutoMatch()?.fileName)
    }

    @Test
    fun `面板列表顺序与自动挑选一致`() {
        // 两处各写一份比较器的话，面板第一行可能不是被自动挂上的那条，
        // 用户看到的是「自动选择选了个不是第一项的」，无从理解。
        val candidates = listOf(
            source("Show.bilingual.srt", matchScore = 100, isBilingual = true),
            source("Show.srt", matchScore = 100),
            source("Show.chs.srt", matchScore = 100, languageTag = "zh-Hans"),
            source("Show.other.srt", matchScore = 0),
        )

        assertEquals("Show.srt", candidates.sortedForSelection().first().fileName)
        assertEquals(candidates.sortedForSelection().first(), candidates.bestAutoMatch())
    }

    @Test
    fun `同分同标记时按文件名排序，结果稳定`() {
        // 不稳定的话，MediaStore 每次返回顺序不同就会「每次打开挂上的字幕都不一样」，
        // 而且用户没法理解为什么。
        val candidates = listOf(
            source("Show.S01E01.eng.srt", matchScore = 90, languageTag = "en"),
            source("Show.S01E01.chs.srt", matchScore = 90, languageTag = "zh-Hans"),
        )

        assertEquals("Show.S01E01.chs.srt", candidates.bestAutoMatch()?.fileName)
    }

    @Test
    fun `分数不够的候选不自动挂载`() {
        val weak = listOf(source("Show.Other.srt", matchScore = AUTO_MATCH_SCORE - 1))

        assertTrue("仍然能被用户手动选到", weak.single().matchesMedia)
        assertFalse(weak.single().isAutoMatchable)
        assertNull("但绝不能自动应用", weak.bestAutoMatch())
    }

    @Test
    fun `完全对不上的候选连手动列表都不该出现在首位`() {
        val unrelated = source("完全是另一部片子.srt", matchScore = 0)

        assertFalse(unrelated.matchesMedia)
        assertFalse(unrelated.isAutoMatchable)
    }

    @Test
    fun `完全没有候选时返回空`() {
        assertNull(emptyList<SubtitleSource>().bestAutoMatch())
    }

    @Test
    fun `分数为 0 的候选既不合理也不可用`() {
        val unrelated = source("完全是另一部片子.srt", matchScore = 0)

        assertFalse(unrelated.matchesMedia)
        assertFalse(unrelated.isAutoMatchable)
        assertNull(listOf(unrelated).bestAutoMatch())
    }
}
