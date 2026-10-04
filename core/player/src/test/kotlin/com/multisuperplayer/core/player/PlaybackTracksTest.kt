package com.multisuperplayer.core.player

import com.multisuperplayer.core.model.SubtitleDocument
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨道清单的纯逻辑：语言归一化、自动挑轨、内嵌 cue 的累积。
 *
 * 这些函数住在 `core:player` 里，而它们的错误症状都极其安静：
 * 挑错轨是「一打开就冒出一堆看不懂的字幕」，cue 乱序是「字幕显示的是别的行」，
 * 语言码没归一化是「有中文字幕却不自动挂上」。所以这里逐条钉住，
 * 每一组测试对应一个**真实的失败方向**，而不是把实现重抄一遍。
 */
class PlaybackTracksTest {

    // ---------------- normalizeLanguageTag ----------------

    @Test
    fun `语言归一化把 ISO-639-2B 收敛到 ISO-639-1`() {
        // 这是「按语言挑轨」能工作的前提：Matroska 写 `chi`、MP4 写 `zh`，
        // 不归一化的话同一条偏好在两种容器里各匹配一半。
        assertEquals("zh", normalizeLanguageTag("chi"))
        assertEquals("zh", normalizeLanguageTag("zho"))
        assertEquals("zh", normalizeLanguageTag("cmn"))
        // 粤语也归到 `zh`：用户的目标语言是中文，比「不匹配」更接近他要的。
        assertEquals("zh", normalizeLanguageTag("yue"))
        assertEquals("en", normalizeLanguageTag("eng"))
        assertEquals("ja", normalizeLanguageTag("jpn"))
    }

    @Test
    fun `语言归一化只留主语言子标签`() {
        assertEquals("zh", normalizeLanguageTag("zh-Hant-CN"))
        assertEquals("zh", normalizeLanguageTag("zh_Hans"))
        assertEquals("en", normalizeLanguageTag("EN-us"))
    }

    @Test
    fun `语言归一化把未定和空当成没有语言`() {
        // `und` 必须收敛成 null 而不是保留成字符串：拿它去和用户语言比会得到
        // 「所有未标语言的轨都算匹配」，也就是自动挂上随便一条轨。
        assertNull(normalizeLanguageTag(null))
        assertNull(normalizeLanguageTag(""))
        assertNull(normalizeLanguageTag("   "))
        assertNull(normalizeLanguageTag("und"))
        assertNull(normalizeLanguageTag("UND"))
        assertNull(normalizeLanguageTag("-Hant"))
    }

    @Test
    fun `语言归一化对认不出来的码保留原样`() {
        // 保留原样（而不是 null）：至少用户还能在面板里看出容器写了什么。
        assertEquals("qqq", normalizeLanguageTag("qqq"))
        assertEquals("xyz", normalizeLanguageTag("  XYZ  "))
    }

    // ---------------- matchesLanguage ----------------

    @Test
    fun `语言未知一律算不匹配`() {
        val track = MspTrackInfo(id = "a", kind = MspTrackKind.TEXT, language = null)
        assertFalse(track.matchesLanguage(listOf("zh", "en")))
        // 偏好里写 `und` 也不行：它归一化后是 null，而 null 不等于任何语言。
        assertFalse(track.matchesLanguage(listOf("und")))
        assertFalse(track.matchesLanguage(emptyList()))
    }

    @Test
    fun `偏好和轨道的语言用不同的编码也能匹配`() {
        // 轨道那边的 language 已经归一化过（由 ExoPlayerController 保证），
        // 但**偏好**来自界面语言，可能是 `chi` 这种旧写法，所以两边都要归一化。
        val track = MspTrackInfo(id = "a", kind = MspTrackKind.TEXT, language = "zh")
        assertTrue(track.matchesLanguage(listOf("chi")))
        assertTrue(track.matchesLanguage(listOf("en", "zh-Hant")))
        assertFalse(track.matchesLanguage(listOf("en", "ja")))
    }

    // ---------------- bestEmbeddedSubtitleTrack ----------------

    @Test
    fun `一条画得出来的轨都没有时不自动挂字幕`() {
        assertNull(emptyList<MspTrackInfo>().bestEmbeddedSubtitleTrack(listOf("zh")))
        // 格式认不出来的轨不算：挂上去的症状是「屏幕上一行字都没有，而面板说已自动
        // 选中」——用户只能以为播放器坏了。
        val unknown = listOf(text(id = "x", language = "zh", mimeType = "application/octet-stream"))
        assertNull(unknown.bestEmbeddedSubtitleTrack(listOf("zh")))
        // 只有音频轨时更是什么都不挂。
        assertNull(listOf(audio(id = "a")).bestEmbeddedSubtitleTrack(listOf("zh")))
    }

    @Test
    fun `位图轨也算能自动挂上的字幕`() {
        // v0.9 之后位图由我们自己的位图层画，于是它必须「自动挑得中」且「用户点得动」。
        // `application/pgs` 是 Media3 里那个**真的**值（`MimeTypes.APPLICATION_PGS`）：
        // 这里原来写的是 `application/x-pgs`，那个值在 Media3 里不存在，
        // 于是那条断言（assertNull）即使绿着也从来没验过真东西。
        val bitmap = listOf(
            text(id = "p", language = "zh", mimeType = "application/pgs", isDefault = true),
        )
        assertEquals("p", bitmap.bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
    }

    @Test
    fun `语言匹配胜过默认标记`() {
        // 第 1 层规则的核心：语言对得上时**不看**默认标记。反过来写（默认标记优先）
        // 的症状是「明明有中文字幕，却挂上了标着默认的法语轨」。
        val tracks = listOf(
            text(id = "fr", language = "fr", isDefault = true),
            text(id = "zh", language = "zh"),
        )
        assertEquals("zh", tracks.bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
    }

    @Test
    fun `语言对不上时只认默认标记`() {
        // 片源里只有一条法语字幕、用户看的是中文界面：自动挂上它等于「一打开就
        // 冒出一堆看不懂的字幕」。自动逻辑宁可什么都不做。
        val tracks = listOf(
            text(id = "fr", language = "fr"),
            text(id = "ja", language = "ja"),
        )
        assertNull(tracks.bestEmbeddedSubtitleTrack(listOf("zh")))
        val withDefault = tracks + text(id = "de", language = "de", isDefault = true)
        assertEquals("de", withDefault.bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
    }

    @Test
    fun `强制轨排在非强制轨之后`() {
        // 强制轨只覆盖外语台词那几句，自动挂上它大部分时间是空屏。
        val tracks = listOf(
            text(id = "forced", language = "zh", isForced = true, isDefault = true),
            text(id = "full", language = "zh"),
        )
        assertEquals("full", tracks.bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
        // 只有强制轨可选时还是挂它：空屏也比「什么都没有」更接近用户要的。
        val onlyForced = listOf(text(id = "forced", language = "zh", isForced = true))
        assertEquals("forced", onlyForced.bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
    }

    @Test
    fun `同为非强制时默认轨优先`() {
        // 简繁两条轨都标着 `zh`，容器的默认标记是唯一能分开它们的信号。
        val tracks = listOf(
            text(id = "a", language = "zh"),
            text(id = "b", language = "zh", isDefault = true),
        )
        assertEquals("b", tracks.bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
    }

    @Test
    fun `偏好为空时只剩默认标记这一条路`() {
        val tracks = listOf(
            text(id = "zh", language = "zh"),
            text(id = "en", language = "en", isDefault = true),
        )
        assertEquals("en", tracks.bestEmbeddedSubtitleTrack(emptyList())?.id)
        assertNull(listOf(text(id = "zh", language = "zh")).bestEmbeddedSubtitleTrack(emptyList()))
    }

    // ---------------- embeddedSubtitleTracks / isTextRenderable / isBitmapRenderable ----------------

    @Test
    fun `字幕轨清单收下文本型和位图型 只丢掉音频轨`() {
        // v0.9 之前这里是「排除位图」：容器里的 PGS 没有任何东西能画
        // （唯一能画它的 Media3 `SubtitleView` 在播放页被遮掉了，否则内嵌字幕会
        // 画两遍），而让用户点一条「点了没反应」的轨比不给他点更坏。
        // 现在位图层自己画，于是「能画的」这个集合变宽了——继续排除位图的话，
        // 用户挑不了它、自动挑轨也挑不到它，片源里明明有中文字幕却说没有。
        //
        // 这三个 MIME 是 Media3 1.11.1 里真实存在的值（`javap -constants MimeTypes`）。
        // 原来写成 `application/x-pgs` / `application/x-vobsub` —— 它们在 Media3 里
        // 根本不存在，于是这个测试虽然绿着，却从来没验过真的东西。
        val tracks = listOf(
            text(id = "srt", language = "zh", mimeType = "application/x-subrip"),
            text(id = "pgs", language = "zh", mimeType = "application/pgs"),
            text(id = "vobsub", language = "zh", mimeType = "application/vobsub"),
            text(id = "dvb", language = "zh", mimeType = "application/dvbsubs"),
            MspTrackInfo(id = "audio", kind = MspTrackKind.AUDIO, mimeType = "audio/ac3"),
        )
        assertEquals(
            listOf("srt", "pgs", "vobsub", "dvb"),
            tracks.embeddedSubtitleTracks().map { it.id },
        )
        // 格式认不出来的那条不该混进来（清单不是「所有 TEXT 轨」）。
        assertTrue(listOf(text(mimeType = "application/octet-stream")).embeddedSubtitleTracks().isEmpty())
    }

    @Test
    fun `文本可渲染的判断覆盖文本型和广播字幕`() {
        assertTrue(text(mimeType = "text/vtt").isTextRenderable())
        assertTrue(text(mimeType = "APPLICATION/X-SUBRIP").isTextRenderable())
        assertTrue(text(mimeType = "application/cea-708").isTextRenderable())
        assertTrue(text(mimeType = "application/cea-608").isTextRenderable())
        // 认不出来的 MIME 不算：宁可少一条，也不要给用户一个点了没反应的选项。
        assertFalse(text(mimeType = "application/octet-stream").isTextRenderable())
        assertFalse(text(mimeType = "").isTextRenderable())
        // 位图也不算**文本**可渲染：它的 `Cue.text` 是空的，交给文本层就是一行空白。
        // 这一行曾经靠「`subtitleFormatOf` 认不出 pgs」间接成立，现在那三个 MIME
        // 有映射了，拦住它的是函数第一行那份名单（顺序承重，别把它挪到最后）。
        assertFalse(text(mimeType = "application/pgs").isTextRenderable())
    }

    @Test
    fun `位图判据和文本判据互不包含`() {
        val pgs = text(mimeType = "application/pgs")
        assertTrue(pgs.isBitmapRenderable())
        assertTrue(pgs.isRenderableSubtitle())
        assertFalse(pgs.isTextRenderable())

        val srt = text(mimeType = "application/x-subrip")
        assertTrue(srt.isTextRenderable())
        assertTrue(srt.isRenderableSubtitle())
        assertFalse(srt.isBitmapRenderable())

        // 两个都判 false 的那种轨：它既不该出现在清单里，也不该被自动挂上。
        val nothing = text(mimeType = "application/octet-stream")
        assertFalse(nothing.isTextRenderable())
        assertFalse(nothing.isBitmapRenderable())
        assertFalse(nothing.isRenderableSubtitle())
    }

    @Test
    fun `位图 MIME 只认那三个 不按前缀猜`() {
        // 靠前缀（`application/`）或「名字里有 pgs」去判会误伤：
        // 猜着放进来等于给用户一个点了没反应的选项。
        // 大小写不是问题：`subtitleMimeType()` 已经归一化了（容器里写什么都可能）。
        assertTrue(text(mimeType = "APPLICATION/PGS").isBitmapRenderable())
        assertTrue(text(mimeType = "application/dvbsubs").isBitmapRenderable())
        assertFalse(text(mimeType = "application/pgs2").isBitmapRenderable())
        assertFalse(text(mimeType = "application/x-pgs").isBitmapRenderable())
        assertFalse(text(mimeType = "").isBitmapRenderable())
    }

    // ---------------- Media3 的 cue 包 MIME（内嵌轨的实际情况） ----------------

    @Test
    fun `cue 包 MIME 要按 codecs 还原成真实格式`() {
        // 实测（本项目里 `multi.mkv` 的两条 subrip 轨）：
        //   kind=TEXT mime='application/x-media3-cues' codec=application/x-subrip lang=zh
        // 只看 mimeType 的后果是这两条被判成「未知格式」、从清单里整个消失，而面板
        // 顶部「当前挂着哪条字幕」走的是另一条路（不看格式），照样写着
        // 「已自动选中「zh」」——同一个面板自相矛盾，还一条可选项都没有。
        val mkv = text(
            id = "zh",
            language = "zh",
            mimeType = "application/x-media3-cues",
            codec = "application/x-subrip",
        )
        assertEquals("application/x-subrip", mkv.subtitleMimeType())
        assertEquals(SubtitleFormat.SRT, mkv.subtitleFormat())
        assertTrue(mkv.isTextRenderable())
        assertEquals(listOf("zh"), listOf(mkv).embeddedSubtitleTracks().map { it.id })
        // 自动挑选也必须能看到它，否则「打开就有字幕」这条也一起坏掉。
        assertEquals("zh", listOf(mkv).bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
    }

    @Test
    fun `cue 包 MIME 配位图 codecs 要还原成位图格式`() {
        // media3-extractor 的 `DefaultSubtitleParserFactory` 也处理 pgs/vobsub/dvbsubs，
        // 它们输出的轨同样被重写成 cue 包 MIME，真实格式落在 `codecs` 里。
        // 如果这里把 `codecs` 丢掉（只认得出的格式才采信），`application/pgs` 会退化成
        // cue 包 MIME、被「cue 包一律文本可渲染」那条宽松规则收进文本层，
        // 屏幕上就是一条空白行。
        val pgs = text(
            id = "pgs",
            mimeType = "application/x-media3-cues",
            codec = "application/pgs",
            isDefault = true,
        )
        assertEquals("application/pgs", pgs.subtitleMimeType())
        assertEquals(SubtitleFormat.PGS, pgs.subtitleFormat())
        assertFalse(pgs.isTextRenderable())
        // 但它是**可渲染**的（位图层），而且必须在清单里、也挑得中。
        assertTrue(pgs.isBitmapRenderable())
        assertEquals(listOf("pgs"), listOf(pgs).embeddedSubtitleTracks().map { it.id })
        assertEquals("pgs", listOf(pgs).bestEmbeddedSubtitleTrack(listOf("zh"))?.id)
    }

    @Test
    fun `cue 包 MIME 配认不出的 codecs 仍算可渲染`() {
        // 走到这条分支说明 Media3 认得这个格式、并且已经解成文本了，格式认不出只是
        // 「我们没见过这个写法」，不该把选项整个藏起来。
        val future = text(mimeType = "application/x-media3-cues", codec = "subrip")
        assertEquals("application/x-media3-cues", future.subtitleMimeType())
        assertTrue(future.isTextRenderable())
        // 容器没写 `codecs` 时同理。
        assertTrue(text(mimeType = "application/x-media3-cues").isTextRenderable())
    }

    @Test
    fun `codecs 只在看起来是 MIME 时才被采信`() {
        // 音频/视频轨的 codecs 是 RFC 6381 编解码器名（`mp4a.40.2`），带 `/` 的才是 MIME。
        val audio = MspTrackInfo(
            id = "a",
            kind = MspTrackKind.AUDIO,
            mimeType = "audio/mp4a-latm",
            codec = "mp4a.40.2",
        )
        assertEquals("audio/mp4a-latm", audio.subtitleMimeType())
        // 文本轨自己的 mimeType 已经是格式时，codecs 不再参与：它的值可能是个短名。
        assertEquals("text/vtt", text(mimeType = "text/vtt", codec = "vtt").subtitleMimeType())
        assertEquals(SubtitleFormat.VTT, text(mimeType = "text/vtt", codec = "vtt").subtitleFormat())
    }

    @Test
    fun `MP4 内封 WebVTT 的连字符写法要认得`() {
        // Media3 的 `APPLICATION_MP4VTT` = `application/x-mp4-vtt`（带连字符），
        // 原来只写了没连字符的那个写法，于是这种轨一律显示「未知」。
        assertEquals(SubtitleFormat.VTT, subtitleFormatOf("application/x-mp4-vtt"))
    }

    @Test
    fun `主标题按容器标签 语言 兜底名依次取值`() {
        assertEquals("国语", text(label = "国语", language = "zh").displayLabel("音轨 1"))
        assertEquals("zh", text(language = "zh").displayLabel("音轨 1"))
        // 空白标签要当成没有：容器里写一个空串是常见情况。
        assertEquals("zh", text(label = "   ", language = "zh").displayLabel("音轨 1"))
        assertEquals("字幕 2", text().displayLabel("字幕 2"))
    }

    // ---------------- subtitleFormatOf ----------------

    @Test
    fun `MIME 到字幕格式的映射`() {
        assertEquals(SubtitleFormat.SRT, subtitleFormatOf("application/x-subrip"))
        assertEquals(SubtitleFormat.SRT, subtitleFormatOf("text/srt"))
        assertEquals(SubtitleFormat.VTT, subtitleFormatOf("text/vtt"))
        assertEquals(SubtitleFormat.SSA, subtitleFormatOf("text/x-ssa"))
        assertEquals(SubtitleFormat.ASS, subtitleFormatOf("application/x-ass"))
        assertEquals(SubtitleFormat.TTML, subtitleFormatOf("application/ttml+xml"))
        // 大小写和前后空格都来自容器的自由写法。
        assertEquals(SubtitleFormat.SRT, subtitleFormatOf("  Application/X-SubRip "))
    }

    @Test
    fun `认不出来的 MIME 是未知格式`() {
        // 猜一个格式出来会让用户以为「这文件就是 SRT 吧」，然后拿
        // 「为什么 SRT 渲染出来是乱的」来问。
        assertEquals(SubtitleFormat.UNKNOWN, subtitleFormatOf(null))
        assertEquals(SubtitleFormat.UNKNOWN, subtitleFormatOf(""))
        assertEquals(SubtitleFormat.UNKNOWN, subtitleFormatOf("application/octet-stream"))
        // 位图字幕**认得出来**（v0.9 起）：界面上写「图像字幕」而不是「未知格式」，
        // 用户看到「未知」会以为是我们的兼容性问题。这三个 MIME 和
        // `BITMAP_SUBTITLE_MIMES` 同源，两者必须一起改。
        assertEquals(SubtitleFormat.PGS, subtitleFormatOf("application/pgs"))
        assertEquals(SubtitleFormat.VOBSUB, subtitleFormatOf("application/vobsub"))
        assertEquals(SubtitleFormat.DVB, subtitleFormatOf("application/dvbsubs"))
    }

    @Test
    fun `三个位图 MIME 既是可识别的格式也是位图判据`() {
        // 两处名单分开写是最容易漂的地方（“加了一个忘了另一个”的症状是：清单里写
        // 着「PGS」，点下去什么都不显示）。这里用一条测试把它们钉在一起。
        listOf(
            "application/pgs" to SubtitleFormat.PGS,
            "application/vobsub" to SubtitleFormat.VOBSUB,
            "application/dvbsubs" to SubtitleFormat.DVB,
        ).forEach { (mime, format) ->
            assertEquals(format, subtitleFormatOf(mime))
            val track = text(mimeType = mime)
            assertTrue("$mime 应该是位图可渲染", track.isBitmapRenderable())
            assertFalse("$mime 不该被当成文本可渲染", track.isTextRenderable())
        }
        // 反方向：`SubtitleFormat` 那边「是位图」的集合必须恰好是这三个。多一个会让
        // 外挂字幕的扫盘把一个不该收的后缀收进来（见 `discoverableExtensions`），
        // 少一个就是上面那个「清单里写着、点下去不显示」。
        assertEquals(
            setOf(SubtitleFormat.PGS, SubtitleFormat.VOBSUB, SubtitleFormat.DVB),
            SubtitleFormat.entries.filter { it.isBitmap }.toSet(),
        )
        // MIME 那份名单也一样，只认这三个（`application/dvbsubs` 不是从枚举名推出来的，
        // 所以这里手写而不是拼字符串）。
    }

    // ---------------- 音频轨清单 ----------------

    @Test
    fun `音频清单只留音频轨`() {
        val tracks = listOf(
            audio(id = "a1", isSelected = true),
            text(id = "t1", language = "zh"),
            audio(id = "a2"),
        )
        assertEquals(listOf("a1", "a2"), tracks.audioTracks().map { it.id })
        assertTrue(emptyList<MspTrackInfo>().audioTracks().isEmpty())
    }

    @Test
    fun `只有一条被选中时它就是在听的那条`() {
        val tracks = listOf(audio(id = "a1"), audio(id = "a2", isSelected = true))
        assertEquals("a2", tracks.selectedAudioTrack()?.id)
    }

    @Test
    fun `多条同时选中时不说自己在听哪条`() {
        // DASH/HLS 的同语言多码率会让多条同时报 `isSelected`（内核正在它们之间
        // 自适应）。用 `firstOrNull` 会随便挑一条显示，而那条很可能不是此刻
        // 真正在解码的那条——界面于是显示了一个**假的**当前音轨。
        val tracks = listOf(audio(id = "a1", isSelected = true), audio(id = "a2", isSelected = true))
        assertNull(tracks.selectedAudioTrack())
    }

    @Test
    fun `一条都没选中 或者只有字幕被选中时返回 null`() {
        // 界面把这两种情况和「自适应」画得一样（都写「自动」），所以它们必须走
        // 同一条分支，而不是让其中一种显示成「音轨 1」。
        assertNull(listOf(audio(id = "a1"), audio(id = "a2")).selectedAudioTrack())
        assertNull(emptyList<MspTrackInfo>().selectedAudioTrack())
        assertNull(listOf(text(id = "t1", language = "zh", isSelected = true)).selectedAudioTrack())
    }

    // ---------------- EmbeddedSubtitleState.receive ----------------

    @Test
    fun `同一批的多行合成一条 cue`() {
        // Media3 按「一个 Cue 一行」给。我们的字幕层是「一条 cue 里可以有多行」
        // （`cueLinesFor` 按 \n 切），所以这里必须拼起来，否则屏幕上只会出现
        // 两行里的第一行。
        val state = EmbeddedSubtitleState().receive(1_000L, listOf("第一行", "第二行"))
        assertEquals(1, state.cues.size)
        assertEquals("第一行\n第二行", state.cues[0].text)
        assertEquals(1_000L, state.cues[0].startMs)
        assertEquals(OPEN_CUE_END_MS, state.cues[0].endMs)
        assertEquals(0, state.cues[0].index)
    }

    @Test
    fun `行内空白被裁掉 空行被丢掉`() {
        val state = EmbeddedSubtitleState().receive(0L, listOf("  一句话  ", "", "   "))
        assertEquals(listOf("一句话"), state.cues.map { it.text })
    }

    @Test
    fun `下一批到来时上一批结束`() {
        val state = EmbeddedSubtitleState()
            .receive(1_000L, listOf("第一句"))
            .receive(3_000L, listOf("第二句"))
        assertEquals(1_000L, state.cues[0].startMs)
        assertEquals(3_000L, state.cues[0].endMs)
        assertEquals(OPEN_CUE_END_MS, state.cues[1].endMs)
    }

    @Test
    fun `字幕放完的空批次也会关上上一句`() {
        // 字幕放完时 Media3 给的是一批空内容，那正是「结束了」的信号。
        // 早退必须发生在回填结束时间**之后**，否则最后一句会永远挂在屏幕上。
        val state = EmbeddedSubtitleState()
            .receive(1_000L, listOf("最后一句"))
            .receive(2_500L, emptyList())
        assertEquals(1, state.cues.size)
        assertEquals(2_500L, state.cues[0].endMs)
    }

    @Test
    fun `结束时间永远不会早于开始时间`() {
        // `endMs` 是非空 Long，而 `cueAt` 的区间判断假定 `end >= start`。
        // 「往回拖到上一句开始之前」会让此刻的时刻小于上一句的开始时间。
        val beforeStart = EmbeddedSubtitleState()
            .receive(5_000L, listOf("第五秒"))
            .receive(1_000L, emptyList())
        assertEquals(5_000L, beforeStart.cues[0].endMs)

        val exactlyAtStart = EmbeddedSubtitleState()
            .receive(5_000L, listOf("第五秒"))
            .receive(5_000L, emptyList())
        assertEquals(5_000L, exactlyAtStart.cues[0].endMs)
    }

    @Test
    fun `同一时刻的同一句话不会被重复追加`() {
        // 来回拖进度条会把同一句反复送来。不去重的话字幕层里会出现几十条
        // 一模一样的行，而「已译 N/M」这种计数会一路涨到荒谬的值。
        val state = EmbeddedSubtitleState()
            .receive(1_000L, listOf("同一句"))
            .receive(1_000L, listOf("同一句"))
            .receive(1_000L, listOf("同一句"))
        assertEquals(1, state.cues.size)
        // 但同一时刻的**另一句**是新的（重播时切了轨道、或者换了一行）。
        assertEquals(2, state.receive(1_000L, listOf("另一句")).cues.size)
    }

    @Test
    fun `往回跳到没看过的地方会插入排序并重排下标`() {
        // `SubtitleDocument.cueAt` 是二分查找，列表一乱它就返回错的行；
        // index 也必须连续且从 0 开始（人工译文是按 `index + 原文` 存的）。
        val state = EmbeddedSubtitleState()
            .receive(10_000L, listOf("第十秒"))
            .receive(20_000L, listOf("第二十秒"))
            .receive(5_000L, listOf("第五秒"))
        assertEquals(listOf("第五秒", "第十秒", "第二十秒"), state.cues.map { it.text })
        assertEquals(listOf(5_000L, 10_000L, 20_000L), state.cues.map { it.startMs })
        assertEquals(listOf(0, 1, 2), state.cues.map { it.index })
        // 二分查找在这个列表上要能找到正确的那一行（这才是排序存在的理由）。
        assertEquals("第十秒", state.document().cueAt(15_000L)?.text)
    }

    @Test
    fun `负的开始时间被夹到零`() {
        val state = EmbeddedSubtitleState().receive(-500L, listOf("片头"))
        assertEquals(0L, state.cues[0].startMs)
    }

    @Test
    fun `空批次在没有开场白时是空操作`() {
        val state = EmbeddedSubtitleState().receive(1_000L, emptyList())
        assertTrue(state.cues.isEmpty())
    }

    /**
     * 已知瑕疵（钉住现状，不是期望）。
     *
     * 往回跳过一条**结束时间还没回填**的 cue 时，它会用「现在的时刻」关掉——而现在的
     * 时刻早于它的开始时间，于是它变成一条零长度的 cue（`end == start`），
     * `cueAt` 永远不会选中它。
     *
     * 后果被限制在**记账**上：显示不会出错（上面那条测试里 `cueAt(15s)` 仍然挑到了
     * 正确的那一行），这一条只是让「已译 N/M」和导出文档里多出一行永远不显示的
     * 重复文本。修它需要给「结束时间未知」一个比零长度更诚实的状态，属于字幕层
     * 内部模型的改动，不在本版范围内。
     */
    @Test
    fun `往回跳过未结束的 cue 会留下一条零长度的行`() {
        val state = EmbeddedSubtitleState()
            .receive(10_000L, listOf("第十秒"))
            .receive(20_000L, listOf("第二十秒"))
            .receive(5_000L, listOf("第五秒"))
        val last = state.cues.last()
        assertEquals(20_000L, last.startMs)
        assertEquals(last.startMs, last.endMs)
        // 文本还在（所以「已译 N/M」会把它算进去），但它永远不会显示出来。
        assertEquals("第二十秒", last.text)
        assertNull(state.document().cueAt(20_000L))
    }

    // ---------------- helpers ----------------

    /**
     * `EmbeddedSubtitleState` 自己只有一堆 cue；查「此刻该显示哪一行」是
     * [SubtitleDocument.cueAt] 的事（界面用 `embeddedDocumentOf` 包一层，
     * 那在 `feature:player` 里，本模块够不到）。这里按同样的形状包一下，
     * 好让上面两条测试走的是**生产路径上那个**二分查找。
     */
    private fun EmbeddedSubtitleState.document(): SubtitleDocument =
        SubtitleDocument(track = SubtitleTrack(id = "embedded"), cues = cues)

    private fun text(
        id: String = "t",
        label: String? = null,
        language: String? = null,
        mimeType: String = "application/x-subrip",
        codec: String? = null,
        isSelected: Boolean = false,
        isDefault: Boolean = false,
        isForced: Boolean = false,
    ) = MspTrackInfo(
        id = id,
        kind = MspTrackKind.TEXT,
        label = label,
        language = language,
        mimeType = mimeType,
        codec = codec,
        isSelected = isSelected,
        isDefault = isDefault,
        isForced = isForced,
    )

    private fun audio(
        id: String = "a",
        language: String? = null,
        isSelected: Boolean = false,
    ) = MspTrackInfo(
        id = id,
        kind = MspTrackKind.AUDIO,
        language = language,
        mimeType = "audio/ac3",
        isSelected = isSelected,
    )
}
