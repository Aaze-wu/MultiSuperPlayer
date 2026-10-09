package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
     *
     * [titleMatchScore] 默认随 [matchScore]（与 `SubtitleSource` 的默认值一致）：
     * 这一层大多测「谁排前面」，需要单独验「够不够格自动挂」的用例自己传，
     * 见「音频旁边的同名字幕会被自动挂上」。
     */
    private fun source(
        fileName: String,
        matchScore: Int,
        titleMatchScore: Int = matchScore,
        format: SubtitleFormat = SubtitleFormat.SRT,
        languageTag: String? = null,
        isForced: Boolean = false,
        isBilingual: Boolean = false,
        trailingTagCount: Int = SubtitleFileNaming.analyze(fileName).trailingTagCount,
        sizeBytes: Long = 1_024L,
        uri: String = "content://media/external/file/$fileName",
    ) = SubtitleSource(
        uri = uri,
        fileName = fileName,
        format = format,
        languageTag = languageTag,
        isForced = isForced,
        isBilingual = isBilingual,
        sizeBytes = sizeBytes,
        matchScore = matchScore,
        titleMatchScore = titleMatchScore,
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
    fun `带发行标记的长文件名也够得着自动挂载门槛`() {
        // 用户报的「长文件名（含中文和空格）无法自动识别字幕」在链路上就是这一条：
        // 分数掉到门槛以下时字幕仍然在手动列表里，但界面上一句话都不会说，
        // 看起来就是「没找到字幕」。装饰段与集号写法是分数的两个坑（见
        // `SubtitleFileNamingTest` 的「发行标记与跨写法」一节）。
        assertTrue(score("【高清影视】某某电影 2023 1080P.mp4", "某某电影.srt") >= AUTO_MATCH_SCORE)
        assertTrue(score("[电影天堂www.dygod.net]流浪地球2.HD1080p.国语中字.mp4", "流浪地球2.chs.srt") >= AUTO_MATCH_SCORE)
    }

    @Test
    fun `排序分比这一档更弱只决定先后，不再决定资格`() {
        // ⚠️ 这条测试原来断言的是「音频旁边的 .srt 分数低于门槛 ⇒ 不该自动挂上」。
        // 那个取舍被用户推翻了：他手上的音乐文件旁边放的就是同名 .srt 歌词，
        // 而**名字一字不差**却被判为不可信，只能每次手动选。
        //
        // 现在「不对口」只表现为排序分低 25（同目录有对口候选时让位），
        // 不再剥夺资格。资格判定见 `SubtitleSource.titleMatchScore`。
        val subtitleForAudio = score("song.mp3", "song.srt", MediaKind.AUDIO, SubtitleFormat.SRT)

        assertTrue("名字一样就是对得上，必须能在手动列表里选到", subtitleForAudio > 0)
        assertTrue(
            "排序分带 -25，目的是让对口的歌词排前面（而不是让这条失去资格）",
            subtitleForAudio < AUTO_MATCH_SCORE,
        )
        assertTrue(
            "同一对文件名，纯名字分够门槛",
            SubtitleFileNaming.matchScore("song.mp3", "song.srt") >= AUTO_MATCH_SCORE,
        )
    }

    @Test
    fun `音频旁边的同名字幕会被自动挂上`() {
        // 用户报的 bug：音乐文件旁边放一份同名字幕（`歌.mp3.srt` / `歌.srt`），
        // 播放时永远不自动挂，只能在列表里手动选。
        //
        // 两个候选的名字分都够门槛（100 与 85），只是 `matchScore` 被
        // 「音频配非歌词字幕 -25」减到 75 与 60。门槛不能再拿含加减分的分数判，
        // 否则用户亲手放进同一个文件夹的那份字幕永远挂不上。
        //
        // 这里走的是**真实链路**：两个分数都从文件名现算，不是手填的假数据——
        // 这两个字段将来要是被人重新合并成一个，这条会红。
        fun candidate(media: String, subtitle: String, format: SubtitleFormat, kind: MediaKind) =
            source(
                fileName = subtitle,
                matchScore = score(media, subtitle, kind, format),
                titleMatchScore = SubtitleFileNaming.matchScore(media, subtitle),
                format = format,
            )

        val plain = candidate("歌.mp3", "歌.srt", SubtitleFormat.SRT, MediaKind.AUDIO)
        val extended = candidate("歌.mp3", "歌.mp3.srt", SubtitleFormat.SRT, MediaKind.AUDIO)

        assertTrue("音频 + 同名 .srt 要够格", plain.isAutoMatchable)
        assertTrue("音频 + 「媒体全名 + 字幕后缀」也要够格", extended.isAutoMatchable)

        // 旁边有对口的歌词时仍然优先挂歌词——那 25 分的意义全在「谁排前面」上。
        val lyrics = candidate("歌.mp3", "歌.lrc", SubtitleFormat.LRC, MediaKind.AUDIO)

        assertEquals("歌.lrc", listOf(extended, lyrics).bestAutoMatch()?.fileName)
        assertEquals("歌.lrc", listOf(extended, plain, lyrics).bestAutoMatch()?.fileName)
    }

    @Test
    fun `名字对不上时连门槛都不沾边`() {
        // 反向锁：放宽门槛不能连带把「不相干的文件」放进来。名字分与排序分同为 0，
        // 因为 `associationScore` 在 base 为 0 时直接返回 0（见 `SubtitleFileNamingTest`
        // 的「无关文件不会被亲和度加分变成匹配」）。
        val unrelated = source(
            fileName = "Another Song.lrc",
            matchScore = score("song.mp3", "Another Song.lrc", MediaKind.AUDIO, SubtitleFormat.LRC),
            titleMatchScore = SubtitleFileNaming.matchScore("song.mp3", "Another Song.lrc"),
            format = SubtitleFormat.LRC,
        )

        assertEquals(0, unrelated.titleMatchScore)
        assertFalse(unrelated.matchesMedia)
        assertFalse(unrelated.isAutoMatchable)
        assertNull(listOf(unrelated).bestAutoMatch())
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

    @Test
    fun `重扫后手选的那条换成新扫描的同名项`() {
        // 手选定下的是「哪一条文件」，不是那份文件当时的体积。体积参与解析缓存的
        // 命中判定（见 ParsedSubtitleCache），沿用旧对象就等于「手选过一次的字幕
        // 之后永远读不到新内容」：重新生成、外部改文件，界面都还是第一次那份结果。
        val chosenEarlier = source("VoiceJa3.asr.srt", matchScore = 100, sizeBytes = 216L)
        val rescanned = listOf(
            source("VoiceJa3.asr.srt", matchScore = 100, sizeBytes = 612L),
            source("VoiceJa.ja.srt", matchScore = 100, languageTag = "ja"),
        )

        val refreshed = rescanned.freshVersionOf(chosenEarlier)

        assertEquals(612L, refreshed.sizeBytes)
        assertSame("必须是这次扫描里的那个对象", rescanned[0], refreshed)
    }

    @Test
    fun `重扫后候选里没有手选的那条时沿用旧的`() {
        // 文件被删/改名了就加载失败并提示，**不能**悄悄换成另一条字幕：
        // 用户明确点过的东西不该被自动逻辑推翻，换成别的比报错更难理解。
        val chosenEarlier = source("UserPicked.srt", matchScore = 100, sizeBytes = 216L)
        val rescanned = listOf(source("Show.chs.srt", matchScore = 100, languageTag = "zh-Hans"))

        val refreshed = rescanned.freshVersionOf(chosenEarlier)

        assertSame(chosenEarlier, refreshed)
        assertEquals("content://media/external/file/UserPicked.srt", refreshed.uri)
    }
}
