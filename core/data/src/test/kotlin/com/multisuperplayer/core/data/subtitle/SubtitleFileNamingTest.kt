package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.SubtitleFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外挂字幕「自动关联」的规则测试。
 *
 * 这些断言的目的不是覆盖率，而是把**判定过的取舍**钉住。关联规则的每一处放宽
 * 都会带来一类新的错配，而错配的症状（字幕时间轴对不上、台词是别人的）看起来
 * 完全不像「匹配算法的问题」，所以规则一旦改动，这里必须一起改。
 */
class SubtitleFileNamingTest {

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

    // ---------------------------------------------------------------- 基础匹配

    @Test
    fun `片名完全相同时得分最高`() {
        val exact = score("Show.S01E01.1080p.mkv", "Show.S01E01.1080p.srt")
        val shorter = score("Show.S01E01.1080p.mkv", "Show.S01E01.srt")

        assertTrue("完全同名必须匹配", exact > 0)
        assertTrue("字幕名省略分辨率也必须匹配", shorter > 0)
        assertTrue("完全同名要排在省略分辨率之前", exact > shorter)
    }

    @Test
    fun `字幕名比媒体名更长时也能匹配`() {
        assertTrue(score("Show.mkv", "Show.1080p.BluRay.x265.chs.srt") > 0)
    }

    @Test
    fun `语言后缀不影响匹配`() {
        assertTrue(score("Show.S01E01.1080p.mkv", "Show.S01E01.1080p.zh-CN.srt") > 0)
        assertTrue(score("Show.S01E01.1080p.mkv", "Show.S01E01.1080p.chs.ass") > 0)
    }

    @Test
    fun `大小写不同的后缀同样能识别`() {
        assertEquals(SubtitleFormat.SRT, SubtitleFileNaming.formatOf("Movie.SRT"))
        assertEquals("zh-Hans", SubtitleFileNaming.analyze("Movie.CHS.SRT").languageTag)
    }

    // ---------------------------------------------------------------- 集数否决

    @Test
    fun `不同集的字幕绝不匹配`() {
        assertEquals("挂错集比完全没有字幕更糟", 0, score("Show.S01E01.mkv", "Show.S01E02.srt"))
        assertEquals(0, score("Show.1x01.mkv", "Show.1x02.srt"))
        assertEquals(0, score("某剧.第01集.mkv", "某剧.第02集.srt"))
        assertEquals(0, score("Show.S01E03.mkv", "Show.S02E03.srt"))
    }

    @Test
    fun `能识别常见的季集写法`() {
        assertEquals(SubtitleFileNaming.Episode(1, 1), SubtitleFileNaming.episodeOf("Show.S01E01.1080p.mkv"))
        assertEquals(SubtitleFileNaming.Episode(1, 12), SubtitleFileNaming.episodeOf("Show.1x12.mkv"))
        assertEquals(SubtitleFileNaming.Episode(null, 7), SubtitleFileNaming.episodeOf("某剧.第07集.mkv"))
        assertEquals(SubtitleFileNaming.Episode(null, 7), SubtitleFileNaming.episodeOf("某剧 第 7 话.srt"))
        assertEquals(SubtitleFileNaming.Episode(null, 3), SubtitleFileNaming.episodeOf("Show.EP03.mkv"))
        assertNull("1920x1080 不是集号", SubtitleFileNaming.episodeOf("Movie.1920x1080.mkv"))
    }

    @Test
    fun `季号缺失时不拿季号做判断`() {
        val withSeason = SubtitleFileNaming.episodeOf("Show.S01E01.mkv")
        val withoutSeason = SubtitleFileNaming.episodeOf("Show.第01集.srt")

        assertEquals(1, withSeason?.number)
        assertEquals(1, withoutSeason?.number)
        assertNull("第01集 里没有季号，不能凭空认定它不是第 1 季", withoutSeason?.season)
    }

    // ---------------------------------------------------------------- 拒绝模糊匹配

    @Test
    fun `不做模糊匹配——共同前缀再长也不认`() {
        // 这是这套规则里最容易写错的一条。`The.Thing.1982` 与 `The.Thing.2011`
        // 的共同前缀有 8 个字符，任何「共同前缀够长就算同一部」的启发式都会认，
        // 而它们是两部不同的电影。
        assertEquals(0, score("The.Thing.1982.1080p.mkv", "The.Thing.2011.1080p.srt"))
        assertEquals(0, score("abc.mkv", "a.srt"))
        assertEquals(0, score("电影.mkv", "电视.srt"))
    }

    @Test
    fun `无关文件不会被亲和度加分变成匹配`() {
        // 关键回归：如果写成 `base + affinity` 而不先判断 base == NO_MATCH，
        // 一首歌旁边任何一个 .lrc 都会因为 +25 变成「有匹配」，
        // 于是自动挂上一份别人的歌词——而界面上看起来一切正常。
        assertEquals(0, score("song.mp3", "Another Song.lrc", MediaKind.AUDIO, SubtitleFormat.LRC))
        assertEquals(0, score("song.mp3", "其他歌曲.srt", MediaKind.AUDIO, SubtitleFormat.SRT))
    }

    @Test
    fun `片名本身就长得像语言标记时不能把文件弄丢`() {
        // 电影《It》的字幕就叫 It.srt，而 `it` 是意大利语标签。
        // 如果把标记全部剥掉后得到空片名，这条字幕会从候选里静默消失。
        val info = SubtitleFileNaming.analyze("It.srt")
        assertTrue("片名不能为空，否则文件会被静默丢弃", info.title.isNotEmpty())
        assertNull("`it` 只是片名，不该被当成语言", info.languageTag)
        assertTrue(score("It.mkv", "It.srt") > 0)
    }

    // ---------------------------------------------------------------- 语言标签

    @Test
    fun `语言标签能归一化到 BCP-47`() {
        fun tag(name: String) = SubtitleFileNaming.analyze(name).languageTag

        assertEquals("zh-Hans", tag("Movie.chs.srt"))
        assertEquals("zh-Hans", tag("Movie.zh-CN.srt"))
        assertEquals("zh-Hans", tag("Movie.简体.srt"))
        assertEquals("zh-Hans", tag("Movie.chi.srt"))
        assertEquals("zh-Hant", tag("Movie.cht.srt"))
        assertEquals("zh-Hant", tag("Movie.繁中.srt"))
        assertEquals("zh-Hant", tag("Movie.zh-TW.srt"))
        assertEquals("en", tag("Movie.eng.srt"))
        assertEquals("ja", tag("Movie.日语.srt"))
        assertNull(tag("Movie.srt"))
    }

    @Test
    fun `方括号包裹的标记同样能识别`() {
        assertEquals("zh-Hans", SubtitleFileNaming.analyze("Movie.[CHS].srt").languageTag)
        assertEquals("zh-Hans", SubtitleFileNaming.analyze("Movie.【简中】.srt").languageTag)
    }

    @Test
    fun `强制与双语标记能被识别`() {
        val forced = SubtitleFileNaming.analyze("Movie.chs.forced.srt")
        assertTrue("forced 说明这是给外语台词用的强制字幕", forced.isForced)
        assertEquals("zh-Hans", forced.languageTag)

        assertTrue(SubtitleFileNaming.analyze("Movie.中英.srt").isBilingual)
        assertTrue(SubtitleFileNaming.analyze("Movie.chs&eng.srt").isBilingual)

        val compound = SubtitleFileNaming.analyze("Movie.chi-eng.srt")
        assertTrue("chi-eng 说明文件里两种语言都有", compound.isBilingual)
    }

    // ---------------------------------------------------------------- 标记数（同分排序用）

    @Test
    fun `片名之外的标记会被数出来`() {
        fun tags(name: String) = SubtitleFileNaming.analyze(name).trailingTagCount

        // 这一列是「这条字幕比片名多说了点什么」，只用于同分候选之间的先后。
        // 片子旁边的三条字幕关联分完全相等时，就靠它分出「哪条最像本色出演」。
        assertEquals(0, tags("Movie.srt"))
        assertEquals(1, tags("Movie.chs.srt"))
        assertEquals(1, tags("Movie.forced.srt"))
        assertEquals(2, tags("Movie.chs.forced.srt"))
        // 认不出来的词归属于片名，不算标记（`1080p` 是分辨率，不是字幕变体）。
        assertEquals(0, tags("Movie.1080p.srt"))
    }

    @Test
    fun `标记数不影响关联分`() {
        // 两者必须分开：标记数是「同样都对时挑哪个」，分数是「对不对」。
        // 掺在一起会让「名字更长」变成一种减分，而名字长本身不是错误。
        assertEquals(
            score("Movie.mkv", "Movie.srt"),
            score("Movie.mkv", "Movie.chs.forced.srt"),
        )
    }

    // ---------------------------------------------------------------- 音视频亲和度

    @Test
    fun `音频配歌词、视频配字幕，但不对口只降权不排除`() {
        val lyricForAudio = score("song.mp3", "song.lrc", MediaKind.AUDIO, SubtitleFormat.LRC)
        val subtitleForAudio = score("song.mp3", "song.srt", MediaKind.AUDIO, SubtitleFormat.SRT)
        assertTrue("歌词应该排在普通字幕前面", lyricForAudio > subtitleForAudio)

        // 不对口只是降权。视频旁边只放了一个 .lrc 时依然要能用——
        // 排除掉的话用户只能手选，而手选列表里也不会出现它。
        assertTrue(score("Show.mkv", "Show.lrc", MediaKind.VIDEO, SubtitleFormat.LRC) > 0)
        assertTrue(score("song.mp3", "song.srt", MediaKind.AUDIO, SubtitleFormat.SRT) > 0)
    }

    // ---------------------------------------------------------------- 后缀与格式

    @Test
    fun `由后缀推断格式`() {
        assertEquals(SubtitleFormat.SRT, SubtitleFileNaming.formatOf("Movie.chs.srt"))
        assertEquals(SubtitleFormat.ASS, SubtitleFileNaming.formatOf("Movie.ass"))
        assertEquals(SubtitleFormat.LRC, SubtitleFileNaming.formatOf("song.lrc"))
        assertEquals(SubtitleFormat.TTML, SubtitleFileNaming.formatOf("Movie.ttml"))
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFileNaming.formatOf("Movie.mp4"))
        assertEquals(SubtitleFormat.UNKNOWN, SubtitleFileNaming.formatOf("没有后缀"))
    }

    @Test
    fun `位图字幕不进候选列表`() {
        // VobSub / PGS 是图片，解析器在它们身上必然失败。
        // 放进候选列表的后果是用户点了一个「永远读不出内容」的结果。
        val extensions = SubtitleFileNaming.discoverableExtensions

        assertFalse("idx 是 VobSub 的索引文件", extensions.contains("idx"))
        assertFalse("sub 是 VobSub 的数据文件", extensions.contains("sub"))
        assertFalse("sup 是 PGS 位图字幕", extensions.contains("sup"))

        assertTrue(extensions.contains("srt"))
        assertTrue(extensions.contains("ass"))
        assertTrue(extensions.contains("vtt"))
        assertTrue(extensions.contains("lrc"))
        assertTrue(extensions.contains("ttml"))
    }
}
