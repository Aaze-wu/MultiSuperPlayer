package com.multisuperplayer.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整轨预读里那段**纯逻辑**：目标判定、样本时间折算、样本 → 台词表。
 *
 * ## 这里为什么必须钉这么细
 *
 * 预读的产出是一张**整轨**台词表，它会**整体替换**掉流式表（见 `ExoPlayerController`）。
 * 所以这里错一点点，症状都不是「字幕不显示」，而是「字幕显示的是别的行 / 时间整体偏移」——
 * 用户看不出来是播放器的错，只会觉得这个文件做得烂。而且它发生在后台，没有任何一条日志
 * 会自己冒出来喊。
 *
 * ## 刻意不测的部分
 *
 * `EmbeddedSubtitlePreReader` 本体（解封装器驱动那一层）**没有**单测：它要 `Context`、
 * `DataSource`、真实的 `Extractor`，在 JVM 上跑不起来（本模块的 `unitTests` 开了
 * `isReturnDefaultValues`，`Uri.parse` 之类全都返回 null，测出来的东西没有意义）。
 * 它的正确性只在真机上验证，所以这条路径上的**每一个判断**都被提到了能单测的纯函数里
 * （就是这个文件测的这些东西），剩下的部分只剩下「把字节搬来搬去」。
 *
 * 其中的「cue 包」解码分支（`CueDecoder`）也测不了：它内部是 `android.os.Parcel`。
 * 这条分支要真机才走得到（JVM 上 `Parcel` 只会抛异常），所以真机验证必须覆盖它。
 */
class EmbeddedPreReadTest {

    // ------------------------------------------------------------------ 工具

    private fun textTrack(
        id: String,
        selected: Boolean = false,
        codec: String? = "application/x-subrip",
        language: String? = "zh",
        label: String? = null,
    ) = MspTrackInfo(
        id = id,
        kind = MspTrackKind.TEXT,
        label = label,
        language = language,
        // 真实情形就是这样的：Media3 把从容器解析出来的文本轨一律报成 cue 包 MIME，
        // 真实格式挪到 codecs。
        mimeType = MEDIA3_CUES_MIME,
        codec = codec,
        isSelected = selected,
    )

    private fun audioTrack(id: String, selected: Boolean = false) = MspTrackInfo(
        id = id,
        kind = MspTrackKind.AUDIO,
        mimeType = "audio/ac3",
        codec = "ac-3",
        isSelected = selected,
    )

    private fun sample(startUs: Long, durationUs: Long, vararg texts: String) =
        EmbeddedCueSample(startTimeUs = startUs, durationUs = durationUs, texts = texts.toList())

    private fun cue(vararg lines: String) = Cue.Builder().setText(lines.joinToString("\n")).build()

    // ------------------------------------------------- isPreReadableUri

    @Test
    fun `只有本地可随机读的 uri 才预读`() {
        // 网络流不能为了字幕把整个流下载一遍：那不是「慢一点」，是把一部片子的流量
        // 花在一条可能根本没人看的字幕上。
        assertTrue(isPreReadableUri("content://media/external/video/media/42"))
        assertTrue(isPreReadableUri("file:///storage/emulated/0/Movies/a.mkv"))
        assertTrue(isPreReadableUri("android.resource://com.multisuperplayer.player/raw/sample"))
        assertFalse(isPreReadableUri("http://example.com/a.mkv"))
        assertFalse(isPreReadableUri("https://example.com/a.mkv"))
        assertFalse(isPreReadableUri("rtmp://example.com/a"))
        // 裸路径判 false：这种写法已经不产出了，而「认不出来就当本地」会让预读在
        // 各种奇怪的 scheme 上开跑。
        assertFalse(isPreReadableUri("/storage/emulated/0/Movies/a.mkv"))
        assertFalse(isPreReadableUri(""))
    }

    @Test
    fun `scheme 大小写和空白不影响判定`() {
        assertTrue(isPreReadableUri("Content://media/external/video/media/42"))
        assertTrue(isPreReadableUri("  file:///a.mkv  "))
    }

    // ------------------------------------------------- preReadRequest

    @Test
    fun `没有选中文本轨时不预读`() {
        // 三种情形都必须落到「不适用」（= Off）而不是「失败」：
        // 界面对这两者的画法完全不同（一个是安静地不显示，一个是提示出错）。
        val list = listOf(audioTrack("a:0"), textTrack("t:0", selected = false))
        assertNull(list.preReadRequest("file:///a.mkv"))
        assertNull(emptyList<MspTrackInfo>().preReadRequest("file:///a.mkv"))
    }

    @Test
    fun `选中的是位图字幕时不预读`() {
        // PGS/VobSub 解出来只有图没有字，预读出来我们的层也画不出来（那是 v0.9 的事）。
        // 关键是 codec 里写着 `application/pgs`：只看 mimeType 会看到 cue 包 MIME、
        // 于是误以为它是文本轨。
        val pgs = textTrack("t:0", selected = true, codec = "application/pgs")
        assertFalse(pgs.isTextRenderable())
        assertNull(listOf(pgs).preReadRequest("file:///a.mkv"))
    }

    @Test
    fun `正常情形给出轨序号与文本轨总数`() {
        // 序号是给「两条轨语言和标签完全一样」那种撞车情形分级用的，只数**文本轨**：
        // 把音频轨也算进去会让序号整体偏移。
        val list = listOf(
            audioTrack("a:0", selected = true),
            textTrack("t:0", language = "en"),
            textTrack("t:1", selected = true, language = "zh"),
        )
        val target = list.preReadRequest("file:///a.mkv")
        assertEquals("t:1", target?.track?.id)
        assertEquals(1, target?.ordinal)
        assertEquals(2, target?.textTrackCount)
    }

    @Test
    fun `目标不在清单里时不给目标`() {
        // 调用方拿到的 track 一定来自同一份清单，但清单是被替换的（`_tracks.value = ...`），
        // 传进来一个已经消失的对象时应该是「没有目标」而不是崩溃或猜一条。
        val list = listOf(textTrack("t:0", selected = true))
        assertNull(list.preReadTarget(textTrack("t:9", selected = true)))
    }

    // --------------------------------------------- embeddedCueSampleOf

    @Test
    fun `cue 自己没有时间信息时用样本时间`() {
        // Media3 的 `SubtitleTranscodingTrackOutput` 第一条规则。
        val result = embeddedCueSampleOf(
            cues = listOf(cue("第一行")),
            startTimeUs = C.TIME_UNSET,
            durationUs = 2_000_000L,
            sampleTimeUs = 7_000_000L,
            subsampleOffsetUs = 0L,
        )
        assertEquals(7_000_000L, result?.startTimeUs)
        assertEquals(2_000_000L, result?.durationUs)
    }

    @Test
    fun `cue 时间相对样本时加上样本时间`() {
        // 第二条规则，也是我们的预读链路**实际**走的那条：转码后的字幕样本里
        // `subsampleOffsetUs` 被重置成 `OFFSET_SAMPLE_RELATIVE`，绝对时间写在样本上。
        // 这一条算错，整表就会整体偏移（而偏移量恰好是样本时间，会随播放越错越多）。
        val result = embeddedCueSampleOf(
            cues = listOf(cue("第一行")),
            startTimeUs = 500_000L,
            durationUs = 1_000_000L,
            sampleTimeUs = 7_000_000L,
            subsampleOffsetUs = Format.OFFSET_SAMPLE_RELATIVE,
        )
        assertEquals(7_500_000L, result?.startTimeUs)
    }

    @Test
    fun `其余情形把 subsampleOffsetUs 加到 cue 时间上`() {
        // 第三条规则：cue 的时间是绝对的，`subsampleOffsetUs` 是修正量。
        val result = embeddedCueSampleOf(
            cues = listOf(cue("第一行")),
            startTimeUs = 7_000_000L,
            durationUs = 1_000_000L,
            sampleTimeUs = 0L,
            subsampleOffsetUs = 500_000L,
        )
        assertEquals(7_500_000L, result?.startTimeUs)
    }

    @Test
    fun `只有空白的 cue 不算一条台词`() {
        // 位图字幕解出来就是这样（只有 bitmap、text 为空）。返回 null 而不是
        // 「一条空台词的样本」：后者会在整表里留下一堆空行，而界面按行高排布，
        // 表现是字幕位置上出现莫名其妙的大段空白。
        assertNull(
            embeddedCueSampleOf(
                cues = listOf(Cue.Builder().setText("   ").build()),
                startTimeUs = 1_000_000L,
                durationUs = 1_000_000L,
                sampleTimeUs = 1_000_000L,
                subsampleOffsetUs = 0L,
            ),
        )
        assertNull(
            embeddedCueSampleOf(
                cues = emptyList(),
                startTimeUs = 1_000_000L,
                durationUs = 1_000_000L,
                sampleTimeUs = 1_000_000L,
                subsampleOffsetUs = 0L,
            ),
        )
    }

    // ---------------------------------------------- cuePacketSampleOf

    @Test
    @OptIn(UnstableApi::class)
    fun `cue 包的起点就是样本时刻`() {
        // 真机踩过的 bug：`decode` 的起点参数填成了样本时刻，于是
        // `sampleTimeUs + startTimeUs` 把同一段时间加了两遍（`CueDecoder.decode` 的
        // Javadoc 说得很清楚：`startTimeUs` 只用来填 `CuesWithTiming.startTimeUs`）。
        // 一分钟的片子，播放到 46.88 秒时画面上写着「第 23 秒的台词」。
        val sample = cuePacketSampleOf(
            cues = CuesWithTiming(listOf(cue("第一行")), 46_880_000L, 1_000_000L),
            sampleTimeUs = 46_880_000L,
        )
        // 两倍的话这里会得到 93_760_000。
        assertEquals(46_880_000L, sample?.startTimeUs)
        assertEquals(listOf("第一行"), sample?.texts)
        assertEquals(1_000_000L, sample?.durationUs)
    }

    @Test
    @OptIn(UnstableApi::class)
    fun `cue 包的起点不随包内字段走`() {
        // 包内那个 `startTimeUs` 是**我们**传给 `decode` 的（包里根本没编码它），
        // 所以这里即使填上一个离谱的值，结果也必须只认样本时刻——
        // 两个来源各说各话时，取「解封装器折进去的那个」。
        val sample = cuePacketSampleOf(
            cues = CuesWithTiming(listOf(cue("第一行")), 999_000_000L, 500_000L),
            sampleTimeUs = 7_000_000L,
        )
        assertEquals(7_000_000L, sample?.startTimeUs)
    }

    // ---------------------------------------------- collectEmbeddedCues

    @Test
    fun `样本自带时长时用它当结束时间`() {
        // 这是整轨表比流式表强的地方：结束时间是真的，不需要「等下一批到来才回填」。
        val cues = collectEmbeddedCues(
            listOf(
                sample(startUs = 2_000_000L, durationUs = 1_500_000L, "你好"),
                sample(startUs = 4_000_000L, durationUs = 1_000_000L, "世界"),
            ),
        )
        assertEquals(2, cues.size)
        assertEquals(2_000L, cues[0].startMs)
        assertEquals(3_500L, cues[0].endMs)
        assertEquals("你好", cues[0].text)
        assertEquals(4_000L, cues[1].startMs)
        assertEquals(5_000L, cues[1].endMs)
    }

    @Test
    fun `没有时长时用下一条的开始时间`() {
        val cues = collectEmbeddedCues(
            listOf(
                sample(startUs = 1_000_000L, durationUs = 0L, "上一句"),
                sample(startUs = 3_000_000L, durationUs = 2_000_000L, "下一句"),
            ),
        )
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
    }

    @Test
    fun `最后一条没有时长时用兜底时长而不是无限`() {
        // 🔴 这一条正是整个功能的起点：流式表把最后一条 cue 的结束时间写成
        // `Long.MAX_VALUE`，于是「按表换算位置」在**调快**的方向上是空操作
        // （换算落回同一条），只有调慢才有效。整轨表绝不能再这么写。
        val cues = collectEmbeddedCues(
            listOf(sample(startUs = 1_000_000L, durationUs = 0L, "最后一句")),
        )
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(1_000L + EMBEDDED_CUE_TAIL_MS, cues[0].endMs)
        assertTrue(cues[0].endMs < Long.MAX_VALUE)
    }

    @Test
    fun `兜底时长可以被调用方改`() {
        val cues = collectEmbeddedCues(
            listOf(sample(startUs = 0L, durationUs = 0L, "唯一一句")),
            tailDurationMs = 1_500L,
        )
        assertEquals(1_500L, cues[0].endMs)
    }

    @Test
    fun `结束时间至少比开始时间晚一毫秒`() {
        // 时长不足 1ms（或写成 0）时，`endMs == startMs` 的 cue 在 `SubtitleDocument.cueAt`
        // 里永远不成立（它判 `positionMs >= cue.endMs` 就往下走）——等于白读一行，
        // 而且是**静默**白读：表里有这一条，屏幕上永远没有。
        val cues = collectEmbeddedCues(
            listOf(sample(startUs = 1_000_000L, durationUs = 999L, "一闪而过")),
        )
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(1_001L, cues[0].endMs)
    }

    @Test
    fun `下一条的开始时间没有推进时不算结束时间`() {
        // 同一时刻起了两条（容器里的重复行）：拿它当上一条的结束时间会得到
        // 「零长度 cue」，又回到上面那个静默失效。此时退回兜底时长。
        val cues = collectEmbeddedCues(
            listOf(
                sample(startUs = 5_000_000L, durationUs = 0L, "甲"),
                sample(startUs = 5_000_000L, durationUs = 0L, "乙"),
            ),
        )
        assertEquals(2, cues.size)
        assertEquals(5_000L + EMBEDDED_CUE_TAIL_MS, cues[0].endMs)
        assertEquals(5_000L + EMBEDDED_CUE_TAIL_MS, cues[1].endMs)
    }

    @Test
    fun `样本乱序时先排序再算结束时间`() {
        // 容器里的样本顺序**不一定**是时间顺序（尤其是 seek 之后重建的那一段）。
        // 不排序的后果不是「顺序难看」，而是「每一条的结束时间都取自错误的下一条」，
        // 于是整表的时间轴是碎的——而表本身看起来完全正常。
        val cues = collectEmbeddedCues(
            listOf(
                sample(startUs = 4_000_000L, durationUs = 0L, "后面那句"),
                sample(startUs = 1_000_000L, durationUs = 0L, "前面那句"),
            ),
        )
        assertEquals(listOf("前面那句", "后面那句"), cues.map { it.text })
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
    }

    @Test
    fun `重排后的下标从零开始连续`() {
        // `index` 是给「高亮第几行 / 跳转到第几行」用的，必须和下标的含义一致。
        val cues = collectEmbeddedCues(
            listOf(
                sample(startUs = 3_000_000L, durationUs = 1_000_000L, "丙"),
                sample(startUs = 1_000_000L, durationUs = 1_000_000L, "甲"),
                sample(startUs = 2_000_000L, durationUs = 1_000_000L, "乙"),
            ),
        )
        assertEquals(listOf("甲", "乙", "丙"), cues.map { it.text })
        assertEquals(listOf(0, 1, 2), cues.map { it.index })
    }

    @Test
    fun `一个样本里的多行用换行拼成一条 cue`() {
        // 我们的字幕层是「一条 cue 可以有多行」，Media3 是「一个 Cue 一行」。
        // 这里不合并的话，一句话会变成好几条独立 cue，速率换算与高亮全都会错位。
        val cues = collectEmbeddedCues(
            listOf(sample(startUs = 0L, durationUs = 1_000_000L, "第一行", "第二行")),
        )
        assertEquals(1, cues.size)
        assertEquals("第一行\n第二行", cues[0].text)
    }

    @Test
    fun `没有文本的样本被丢掉且不留空行`() {
        val cues = collectEmbeddedCues(
            listOf(
                sample(startUs = 0L, durationUs = 1_000_000L, "有内容"),
                sample(startUs = 1_000_000L, durationUs = 1_000_000L, "   "),
                sample(startUs = 2_000_000L, durationUs = 1_000_000L),
                sample(startUs = 3_000_000L, durationUs = 1_000_000L, "又有内容"),
            ),
        )
        assertEquals(listOf("有内容", "又有内容"), cues.map { it.text })
        // 丢掉的那两条后面还有内容，所以它们**不能**影响后面那条的结束时间推导。
        assertEquals(3_000L, cues[1].startMs)
    }

    @Test
    fun `微秒到毫秒是截断而不是四舍五入`() {
        // 与流式那条路（`presentationTimeUs / 1000`）保持一致：两处用不同的取整方式，
        // 会让「预读表」和「流式表」在同一条 cue 上差出 1ms，切换时字幕会跳一下。
        val cues = collectEmbeddedCues(
            listOf(sample(startUs = 1_500_999L, durationUs = 250_999L, "不整的毫秒")),
        )
        assertEquals(1_500L, cues[0].startMs)
        assertEquals(1_750L, cues[0].endMs)
    }

    @Test
    fun `空样本表得到空台词表`() {
        assertTrue(collectEmbeddedCues(emptyList()).isEmpty())
    }

    // ---------------------------------------------- pickPreReadCandidate

    @Test
    fun `文件里一条文本轨时直接选它`() {
        // 只有一条就没有歧义，连语言都不用比：容器常常把语言写错或者不写，
        // 用语言去否掉唯一一条轨会让「明明有字幕却预读不了」。
        assertEquals(
            0,
            pickPreReadCandidate(
                candidates = listOf(EmbeddedTrackKey(language = "en", label = null)),
                target = EmbeddedTrackKey(language = "zh", label = null),
                targetOrdinal = 0,
                ordinalsConsistent = true,
            ),
        )
    }

    @Test
    fun `序号对不上时宁可失败也不猜`() {
        // 🔴 负向用例，这一条是这里存在的理由：两条轨语言和标签完全一样时只能靠序号，
        // 而播放器那份清单会丢掉它不支持的轨（两边条数不同 ⇒ 序号整体错位）。
        // 猜错的后果是**悄悄显示另一条轨的台词**，用户完全看不出来。
        assertNull(
            pickPreReadCandidate(
                candidates = listOf(
                    EmbeddedTrackKey(language = "zh", label = null),
                    EmbeddedTrackKey(language = "zh", label = null),
                ),
                target = EmbeddedTrackKey(language = "zh", label = null),
                targetOrdinal = 1,
                ordinalsConsistent = false,
            ),
        )
    }

    @Test
    fun `序号一致时按序号选`() {
        assertEquals(
            1,
            pickPreReadCandidate(
                candidates = listOf(
                    EmbeddedTrackKey(language = "zh", label = null),
                    EmbeddedTrackKey(language = "zh", label = null),
                ),
                target = EmbeddedTrackKey(language = "zh", label = null),
                targetOrdinal = 1,
                ordinalsConsistent = true,
            ),
        )
    }

    @Test
    fun `序号越界时失败而不是夹到范围内`() {
        // 夹到范围内 = 猜。`coerceIn(0, last)` 在这里就是那个陷阱。
        assertNull(
            pickPreReadCandidate(
                candidates = listOf(
                    EmbeddedTrackKey(language = "zh", label = null),
                    EmbeddedTrackKey(language = "zh", label = null),
                ),
                target = EmbeddedTrackKey(language = "zh", label = null),
                targetOrdinal = 5,
                ordinalsConsistent = true,
            ),
        )
    }

    @Test
    fun `文件里没有文本轨时失败`() {
        assertNull(
            pickPreReadCandidate(
                candidates = emptyList(),
                target = EmbeddedTrackKey(language = "zh", label = null),
                targetOrdinal = 0,
                ordinalsConsistent = true,
            ),
        )
    }

    // ---------------------------------------------- embeddedTrackKeyOf

    @Test
    fun `轨道身份把语言归一化和标签空白一起处理掉`() {
        // 两边的键必须用同一套归一化，否则「同一条轨」在两边算出来不一样，
        // 匹配直接失败（症状是预读永远报「找不到轨」，而字幕明明就在那儿）。
        val format = Format.Builder()
            .setSampleMimeType(MEDIA3_CUES_MIME)
            .setLanguage("chi")
            .setLabel("   ")
            .build()
        assertEquals(EmbeddedTrackKey(language = "zh", label = null), embeddedTrackKeyOf(format))

        val track = textTrack("t:0", language = "zh", label = "国语")
        assertEquals(EmbeddedTrackKey(language = "zh", label = "国语"), embeddedTrackKeyOf(track))

        // 未标语言的轨（`und`）归一化成 null，两边一致。
        val und = textTrack("t:1", language = "und")
        assertEquals(EmbeddedTrackKey(language = null, label = null), embeddedTrackKeyOf(und))
    }

    // ---------------------------------------------- describe

    @Test
    fun `预读状态的日志描述不打印整表`() {
        // `Ready` 里可能有几千条 cue，而这条日志就在每次切轨都会走的路径上。
        // 直接 `"$state"` 会打出一条几兆的日志（还会拖慢界面）。
        val ready = EmbeddedPreReadState.Ready(
            collectEmbeddedCues(listOf(sample(startUs = 0L, durationUs = 1_000L, "甲"))),
        )
        // `sample()` 的时间单位是微秒（1_000 µs = 1 ms）。
        assertEquals("Ready（1 行，0ms → 1ms）", ready.describe())
        assertFalse(ready.describe().contains("甲"))
        assertEquals("Off（不适用）", EmbeddedPreReadState.Off.describe())
        assertEquals("Reading", EmbeddedPreReadState.Reading.describe())
        assertEquals(
            "Failed（NO_CUES）",
            EmbeddedPreReadState.Failed(EmbeddedPreReadReason.NO_CUES).describe(),
        )
    }

    @Test
    fun `预读状态的日志带出时间范围`() {
        // 只报条数的话，**时刻整体翻倍**这种错误看起来完全正常（实测踩过：一分钟的片子
        // 46.88 秒播的是「第 23 秒的台词」）。范围能把这类错误立刻指出来。
        val ready = EmbeddedPreReadState.Ready(
            collectEmbeddedCues(
                listOf(
                    sample(startUs = 20_000_000L, durationUs = 1_000_000L, "甲"),
                    sample(startUs = 48_000_000L, durationUs = 1_000_000L, "乙"),
                ),
            ),
        )
        assertEquals("Ready（2 行，20000ms → 49000ms）", ready.describe())
    }
}
