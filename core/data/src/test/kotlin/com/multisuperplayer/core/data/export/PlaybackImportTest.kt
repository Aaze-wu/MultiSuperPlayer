package com.multisuperplayer.core.data.export

import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.text.MspText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「从文件读回播放列表」这一层。
 *
 * ## 这一层的错为什么必须靠单测抓
 *
 * 导入是**不可逆**的：用户选了一个文件、我们读错了，东西已经写进他的播放列表了。
 * 而最危险的几种错在界面上全都长得像成功——
 *
 * 1. **整张表错位**。CSV 的表头是本地化的，而且列集合还会变（多集合多一个「列表」列，
 *    播放记录多三列）。按列位置读的话，一个用英文界面导出的文件在中文界面里导入会
 *    每一列都错，**却一条都不报错**。
 * 2. **认不出的文件被当成空文件**。「这个文件不是我们导出的」和「文件里确实没有
 *    可导入的内容」在界面上如果不分开说，用户会去翻一个其实好好的文件。
 * 3. **丢数据而不说**。没有媒体 ID、同一份文件里重复的条目都不该写进去，但
 *    「丢了 12 条」必须说出来，否则用户只会以为功能坏了。
 *
 * ## 断言方式
 *
 * `PlaybackImport` 刻意不碰 `Context`/`Uri`/文件系统，所以这里能纯 JVM 跑；
 * 别名表由 `CsvAliases.of { id -> ... }` 从 `strings.xml` 里现读，
 * **三种语言的表头都从资源里取真值**，而不是在测试里抄一份（抄的那份会和
 * 资源慢慢漂开，最后测的是测试自己）。
 *
 * 注意 `org.junit.Assert.assertEquals` 的参数顺序是「消息、期望、实际」，
 * 与 `kotlin.test` 那个相反。
 */
class PlaybackImportTest {

    // ================================================================ 往返

    @Test
    fun `导出的 CSV 能被原样读回来`() {
        // 两个集合：只有多于一个时导出才会写「列表」列，列表名才在文件里。
        // 单集合的导出刻意不写名字（见 `没有列表列时所有行归到兜底名字`）。
        val collections = listOf(
            PlaybackExportCollection(
                name = "旅行",
                tracks = listOf(
                    track(mediaId = "m1", title = "甲", artist = "歌手", kind = MediaKind.AUDIO),
                    track(mediaId = "m2", title = "乙", uri = "file:///b.mp4", kind = MediaKind.VIDEO),
                ),
            ),
            PlaybackExportCollection(name = "工作", tracks = listOf(track(mediaId = "m3", title = "丙"))),
        )
        val csv = PlaybackExport.csv(collections, ::resolve)

        val imported = PlaybackImport.decode(csv, "兜底", aliases())

        assertEquals("两个列表读回来还是两个，顺序也不变", listOf("旅行", "工作"), imported.map { it.name })
        assertEquals(
            "字段要一一对上；串位（标题进了艺术家列）在这里就会红",
            listOf(
                PlaylistItem("m1", "content://a", "甲", "歌手", 204_000L, MediaKind.AUDIO),
                PlaylistItem("m2", "file:///b.mp4", "乙", null, 204_000L, MediaKind.VIDEO),
            ),
            imported[0].items,
        )
        assertEquals(listOf("m3"), imported[1].items.map { it.mediaId })
        assertTrue(
            "往返不该丢条目",
            imported.all { it.itemsWithoutId == 0 },
        )
    }

    @Test
    fun `导出的 JSON 能被原样读回来`() {
        val collections = listOf(
            PlaybackExportCollection("歌单 A", listOf(track(mediaId = "m1", title = "甲"))),
            PlaybackExportCollection("歌单 B", listOf(track(mediaId = "m2", title = "乙"))),
        )
        val json = PlaybackExport.json(collections, exportedAtMs = 1_700_000_000_000L, resolve = ::resolve)

        val imported = PlaybackImport.decode(json, "兜底", aliases())

        assertEquals(listOf("歌单 A", "歌单 B"), imported.map { it.name })
        assertEquals(listOf("m1"), imported[0].items.map { it.mediaId })
        assertEquals(listOf("m2"), imported[1].items.map { it.mediaId })
    }

    @Test
    fun `播放记录导出的文件也能读（多出来的位置和时间列直接忽略）`() {
        val collection = PlaybackExportCollection(
            name = "播放记录",
            tracks = listOf(
                track(
                    mediaId = "m1",
                    title = "甲",
                    positionMs = 65_000L,
                    playedAtMs = 1_700_000_000_000L,
                ),
            ),
        )
        val csv = PlaybackExport.csv(listOf(collection), ::resolve)

        val imported = PlaybackImport.decode(csv, "兜底", aliases())

        assertEquals(
            "位置/播放时间是「拿出去看」的字段，导入不还原它们，但也不能因此报错",
            listOf("m1"),
            imported.single().items.map { it.mediaId },
        )
    }

    @Test
    fun `带 BOM 的 CSV 也能读`() {
        val collections = listOf(
            PlaybackExportCollection("甲单", listOf(track(mediaId = "m1"))),
            PlaybackExportCollection("乙单", listOf(track(mediaId = "m2"))),
        )
        val csv = PlaybackExport.csv(collections, ::resolve)

        val imported = PlaybackImport.decode("\uFEFF$csv", "兜底", aliases())

        assertEquals(
            "BOM 刚好贴在第一个列名上——多集合导出时那就是「列表」列——而 `trim()` 去不掉它" +
                "（U+FEFF 既不属于小于空格的字符，也不是 `Character.isWhitespace`）。" +
                "BOM 没剔掉时，所有行会撑进同一个兜底列表里：条目数一条不少，只是少了一个列表，" +
                "在界面上完全看不出错",
            listOf("甲单", "乙单"),
            imported.map { it.name },
        )
    }

    // ========================================================= 三种语言的表头

    @Test
    fun `三种语言的导出表头都能认出来`() {
        listOf(DIR_ZH, DIR_EN, DIR_HANT).forEach { dir ->
            val csv = csvOf(
                listOf(
                    label(dir, COL_INDEX),
                    label(dir, COL_TITLE),
                    label(dir, COL_ARTIST),
                    label(dir, COL_KIND),
                    label(dir, COL_DURATION),
                    label(dir, COL_DURATION_MS),
                    label(dir, COL_FILE_STATE),
                    label(dir, COL_URI),
                    label(dir, COL_MEDIA_ID),
                ),
                listOf(
                    "1",
                    "甲",
                    "歌手",
                    label(dir, KIND_AUDIO),
                    "0:03:24",
                    "204000",
                    label(dir, FILE_OK),
                    "file:///a.mp3",
                    "m1",
                ),
            )

            val imported = PlaybackImport.decode(csv, "兜底", aliases())

            assertEquals("$dir 的表头没被认出来", 1, imported.size)
            assertEquals("$dir 里媒体 ID 读错了", "m1", imported.single().items.single().mediaId)
            assertEquals("$dir 里标题读错了", "甲", imported.single().items.single().title)
            assertEquals(
                "$dir 里类型读错了",
                MediaKind.AUDIO,
                imported.single().items.single().kind,
            )
            assertEquals("$dir 里时长读错了", 204_000L, imported.single().items.single().durationMs)
        }
    }

    @Test
    fun `三种语言的类型取值都能认出来`() {
        listOf(DIR_ZH, DIR_EN, DIR_HANT).forEach { dir ->
            assertEquals(
                "$dir 的「音频」没被认出来",
                MediaKind.AUDIO,
                aliases().kindOf(label(dir, KIND_AUDIO)),
            )
            assertEquals(
                "$dir 的「视频」没被认出来",
                MediaKind.VIDEO,
                aliases().kindOf(label(dir, KIND_VIDEO)),
            )
        }
        assertEquals(
            "枚举名也该认（手写的文件里很自然）",
            MediaKind.VIDEO,
            aliases().kindOf("video"),
        )
    }

    @Test
    fun `多集合导出的列表列也被认得`() {
        val csv = PlaybackExport.csv(
            listOf(
                PlaybackExportCollection("歌单 A", listOf(track(mediaId = "m1", title = "甲"))),
                PlaybackExportCollection("歌单 B", listOf(track(mediaId = "m2", title = "乙"))),
            ),
            ::resolve,
        )

        val imported = PlaybackImport.decode(csv, "兜底", aliases())

        assertEquals(
            "有「列表」列时必须按它分组，否则两个歌单的行会合成一个",
            listOf("歌单 A", "歌单 B"),
            imported.map { it.name },
        )
    }

    // ================================================================ 报错

    @Test
    fun `一个列名都不认识时说是认不出的文件`() {
        val error = assertThrows(PlaybackImportException::class.java) {
            PlaybackImport.decode("甲,乙,丙\r\n1,2,3\r\n", "兜底", aliases())
        }

        assertEquals(
            "「不是我们的文件」必须和「缺某一列」分开：两种原因、两种改法",
            MspText.Res(R.string.msp_import_error_unrecognized),
            error.text,
        )
    }

    @Test
    fun `缺列时说清缺的是哪一列`() {
        val error = assertThrows(PlaybackImportException::class.java) {
            PlaybackImport.decode(
                csvOf(
                    listOf(label(DIR_ZH, COL_TITLE), label(DIR_ZH, COL_MEDIA_ID)),
                    listOf("甲", "m1"),
                ),
                "兜底",
                aliases(),
            )
        }

        assertEquals(
            "缺的是「路径」列，就得说出「路径」，而且这句话本身还得按语言变（所以参数又是一个 Res）",
            MspText.Res(
                R.string.msp_import_error_missing_column,
                MspText.Res(R.string.msp_export_column_uri),
            ),
            error.text,
        )
    }

    @Test
    fun `行里列数对不上时报出行号`() {
        val error = assertThrows(PlaybackImportException::class.java) {
            PlaybackImport.decode(
                csvOf(
                    listOf(label(DIR_ZH, COL_TITLE), label(DIR_ZH, COL_URI), label(DIR_ZH, COL_MEDIA_ID)),
                    listOf("甲", "file:///a.mp3", "m1"),
                    // 这一行有 4 格（少了一个引号之类），从这一行往下整张表都串位了。
                    listOf("乙", "file:///b.mp3", "m2", "多出来的一格"),
                ),
                "兜底",
                aliases(),
            )
        }

        assertEquals(
            "行号要含表头那一行（用户数是按整份文件数的）",
            MspText.Res(R.string.msp_import_error_broken_row, 3),
            error.text,
        )
    }

    @Test
    fun `引号没配对时不会安静地读成另一个列表，而是指出行号`() {
        val csv = csvOf(
            listOf(label(DIR_ZH, COL_TITLE), label(DIR_ZH, COL_URI), label(DIR_ZH, COL_MEDIA_ID)),
            // 标题里一个落单的引号会让扫描器停在「引号内」，后面所有内容被拼进同一格。
            listOf("他说\"好,x.mp3,m1"),
            listOf("甲", "file:///b.mp3", "m2"),
        )

        val error = assertThrows(PlaybackImportException::class.java) {
            PlaybackImport.decode(csv, "兜底", aliases())
        }

        assertEquals(
            "坏文件必须报出来，不能把后面几行读成一条名叫整段文字的曲子",
            MspText.Res(R.string.msp_import_error_broken_row, 2),
            error.text,
        )
    }

    @Test
    fun `空文件不报错，返回空列表`() {
        assertEquals(emptyList<ImportedPlaylist>(), PlaybackImport.decode("", "兜底", aliases()))
        assertEquals(emptyList<ImportedPlaylist>(), PlaybackImport.decode("  \r\n", "兜底", aliases()))
    }

    @Test
    fun `坏掉的 JSON 报「认不出」而不是崩出去`() {
        val error = assertThrows(PlaybackImportException::class.java) {
            PlaybackImport.decode("""{"collections": [ oops ]}""", "兜底", aliases())
        }

        assertEquals(MspText.Res(R.string.msp_import_error_unrecognized), error.text)
    }

    // ============================================================ JSON 形状

    @Test
    fun `裸数组的 JSON 根也能读`() {
        val json = """[{"name":"甲单","tracks":[{"mediaId":"m1","uri":"u","title":"t"}]}]"""

        val imported = PlaybackImport.decode(json, "兜底", aliases())

        assertEquals(listOf("甲单"), imported.map { it.name })
        assertEquals(listOf("m1"), imported.single().items.map { it.mediaId })
    }

    @Test
    fun `被一层数组包住的 JSON 能读`() {
        val json = """[{"collections":[{"name":"甲单","tracks":[{"mediaId":"m1","uri":"u","title":"t"}]}]}]"""

        val imported = PlaybackImport.decode(json, "兜底", aliases())

        assertEquals(
            "`jq -s .` 的结果会长这样，往里钻一层是必须的",
            listOf("甲单"),
            imported.map { it.name },
        )
        assertEquals(listOf("m1"), imported.single().items.map { it.mediaId })
    }

    @Test
    fun `多元素数组里的集合对象能读`() {
        val json = """
            [{"name":"甲单","tracks":[{"mediaId":"m1","uri":"u","title":"t"}]},
             {"name":"乙单","tracks":[{"mediaId":"m2","uri":"u","title":"t"}]}]
        """.trimIndent()

        val imported = PlaybackImport.decode(json, "兜底", aliases())

        assertEquals(listOf("甲单", "乙单"), imported.map { it.name })
    }

    @Test
    fun `多元素数组包着的 collections 不会被当成完整答案`() {
        val json = """
            [{"collections":[{"name":"甲单","tracks":[{"mediaId":"m1","uri":"u","title":"t"}]}]},
             {"collections":[{"name":"乙单","tracks":[{"mediaId":"m2","uri":"u","title":"t"}]}]}]
        """.trimIndent()

        val imported = PlaybackImport.decode(json, "兜底", aliases())

        assertTrue(
            "只在「恰好一个元素」时才往里钻一层。多元素的数组钻进任何一个都等于把" +
                "「一半的答案」当成完整的提交，而外面看起来一切正常——" +
                "所以宁可读成两个空列表（用户会看到「没有可用条目」），" +
                "也不能偷偷只导入其中一半。",
            imported.all { it.items.isEmpty() },
        )
    }

    @Test
    fun `JSON 里缺失的可选字段是 null 时不会被读成字符串`() {
        val json = """
            {"collections":[{"name":"甲单","tracks":[
              {"mediaId":"m1","uri":"u","title":"t","artist":null,"durationMs":null,"kind":null}
            ]}]}
        """.trimIndent()

        val item = PlaybackImport.decode(json, "兜底", aliases()).single().items.single()

        assertNull(
            "`JsonNull` 是 `JsonPrimitive` 的子类、`content` 就是字符串 \"null\"，" +
                "漏判会把「没有艺术家」读成一个叫 null 的艺术家",
            item.artist,
        )
        assertEquals("缺时长按 0 算", 0L, item.durationMs)
        assertEquals("缺类型退回未知", MediaKind.UNKNOWN, item.kind)
    }

    @Test
    fun `JSON 缺 mediaId 的条目被丢掉并计数`() {
        val json = """
            {"collections":[{"name":"甲单","tracks":[
              {"mediaId":"m1","uri":"u","title":"t"},
              {"uri":"u","title":"没有 id"},
              "这一条压根不是对象"
            ]}]}
        """.trimIndent()

        val imported = PlaybackImport.decode(json, "兜底", aliases()).single()

        assertEquals(listOf("m1"), imported.items.map { it.mediaId })
        assertEquals(
            "「文件里 3 条、导进来 1 条」必须能说出来，否则用户以为功能坏了",
            2,
            imported.itemsWithoutId,
        )
    }

    @Test
    fun `JSON 里没有名字的集合用文件名当名字`() {
        val json = """{"collections":[{"tracks":[{"mediaId":"m1","uri":"u","title":"t"}]}]}"""

        assertEquals(listOf("兜底"), PlaybackImport.decode(json, "兜底", aliases()).map { it.name })
    }

    // ============================================================= CSV 细节

    @Test
    fun `没有列表列时所有行归到兜底名字`() {
        val csv = csvOf(
            listOf(label(DIR_ZH, COL_TITLE), label(DIR_ZH, COL_URI), label(DIR_ZH, COL_MEDIA_ID)),
            listOf("甲", "u", "m1"),
            listOf("乙", "u", "m2"),
        )

        val imported = PlaybackImport.decode(csv, "播放记录", aliases())

        assertEquals(
            "单集合导出没有「列表」列，整份文件就是一个列表，名字只能从文件名来",
            listOf("播放记录"),
            imported.map { it.name },
        )
        assertEquals(2, imported.single().items.size)
    }

    @Test
    fun `CSV 里同名的列表被读成两个条目，由编排去合并`() {
        val csv = csvOf(
            listOf(
                label(DIR_ZH, COL_LIST),
                label(DIR_ZH, COL_TITLE),
                label(DIR_ZH, COL_URI),
                label(DIR_ZH, COL_MEDIA_ID),
            ),
            listOf("旅行", "甲", "u", "m1"),
            listOf("旅行", "乙", "u", "m2"),
        )

        val imported = PlaybackImport.decode(csv, "兜底", aliases())

        assertEquals("分组交给编排：解析层只说「文件里写了什么」", listOf("旅行"), imported.map { it.name })
        assertEquals(listOf("m1", "m2"), imported.single().items.map { it.mediaId })
    }

    @Test
    fun `没有媒体 ID 的行被丢掉并记在它自己的列表名下`() {
        val csv = csvOf(
            listOf(
                label(DIR_ZH, COL_LIST),
                label(DIR_ZH, COL_TITLE),
                label(DIR_ZH, COL_URI),
                label(DIR_ZH, COL_MEDIA_ID),
            ),
            listOf("甲单", "甲", "u", "m1"),
            listOf("乙单", "乙", "u", ""),
            listOf("乙单", "丙", "u", ""),
        )

        val imported = PlaybackImport.decode(csv, "兜底", aliases())

        assertEquals(
            "名字要在「媒体 ID 为空」之前算出来，否则「这个列表 2 条全都不能用」" +
                "会连一句提示都没有",
            listOf("甲单", "乙单"),
            imported.map { it.name },
        )
        assertEquals(0, imported[0].itemsWithoutId)
        assertEquals(2, imported[1].itemsWithoutId)
        assertTrue("全丢光的列表以空列表的形式带出去，由编排决定怎么说话", imported[1].items.isEmpty())
    }

    @Test
    fun `类型认不出来时退回未知而不是报错`() {
        val csv = csvOf(
            listOf(
                label(DIR_ZH, COL_TITLE),
                label(DIR_ZH, COL_KIND),
                label(DIR_ZH, COL_URI),
                label(DIR_ZH, COL_MEDIA_ID),
            ),
            listOf("甲", "天知道是什么", "u", "m1"),
        )

        assertEquals(
            "类型纯粹是显示用的，为一个认不出来的值判整份文件坏掉，代价远大于收益",
            MediaKind.UNKNOWN,
            PlaybackImport.decode(csv, "兜底", aliases()).single().items.single().kind,
        )
    }

    @Test
    fun `时长不是数字时按 0 处理`() {
        val csv = csvOf(
            listOf(
                label(DIR_ZH, COL_TITLE),
                label(DIR_ZH, COL_DURATION_MS),
                label(DIR_ZH, COL_URI),
                label(DIR_ZH, COL_MEDIA_ID),
            ),
            listOf("甲", "三分二十四秒", "u", "m1"),
        )

        assertEquals(0L, PlaybackImport.decode(csv, "兜底", aliases()).single().items.single().durationMs)
    }

    @Test
    fun `标题为空时用媒体 ID 顶上`() {
        val csv = csvOf(
            listOf(label(DIR_ZH, COL_TITLE), label(DIR_ZH, COL_URI), label(DIR_ZH, COL_MEDIA_ID)),
            listOf("", "u", "m1"),
        )

        assertEquals(
            "宁可显示一行看不懂的东西，也不要一行空白（空白看起来像「导入丢了内容」）",
            "m1",
            PlaybackImport.decode(csv, "兜底", aliases()).single().items.single().title,
        )
    }

    @Test
    fun `列名首尾的空白容忍`() {
        val csv = csvOf(
            listOf(" ${label(DIR_ZH, COL_TITLE)} ", label(DIR_ZH, COL_URI), label(DIR_ZH, COL_MEDIA_ID)),
            listOf("甲", "u", "m1"),
        )

        assertEquals(
            "手改过的文件里表头带空格很常见，为此判整份文件坏掉不值得",
            listOf("m1"),
            PlaybackImport.decode(csv, "兜底", aliases()).single().items.map { it.mediaId },
        )
    }

    // ============================================================ Csv 解析

    @Test
    fun `引号里的逗号不算分隔符`() {
        assertEquals(
            listOf(listOf("他说,真的", "u")),
            Csv.parse("\"他说,真的\",u"),
        )
    }

    @Test
    fun `两个引号表示一个字面引号`() {
        assertEquals(
            listOf(listOf("他说\"真的\"", "u")),
            Csv.parse("\"他说\"\"真的\"\"\",u"),
        )
    }

    @Test
    fun `引号里的换行算同一格`() {
        assertEquals(
            listOf(listOf("第一行\n第二行", "u")),
            Csv.parse("\"第一行\n第二行\",u"),
        )
    }

    @Test
    fun `只有 LF 的文件也能读`() {
        assertEquals(
            listOf(listOf("甲", "u"), listOf("乙", "u")),
            Csv.parse("甲,u\n乙,u\n"),
        )
    }

    @Test
    fun `末尾的空行不会多出一条记录`() {
        assertEquals(
            "导出的文件以 CRLF 结尾，扫完必然剩下一条空记录，丢掉它",
            listOf(listOf("甲", "u")),
            Csv.parse("甲,u\r\n"),
        )
    }

    @Test
    fun `中间的空行被跳过`() {
        assertEquals(
            listOf(listOf("甲", "u"), listOf("乙", "u")),
            Csv.parse("甲,u\r\n\r\n乙,u\r\n"),
        )
    }

    @Test
    fun `空字段保留成空串`() {
        assertEquals(
            "空字段丢掉的话列数就变了，整份文件会被判成「列数对不上」",
            listOf(listOf("甲", "", "")),
            Csv.parse("甲,,\r\n"),
        )
    }

    @Test
    fun `转义和还原是一对反函数`() {
        listOf(
            "普通",
            "有,逗号",
            "有\"引号\"",
            "有\n换行",
            "有\r\n回车换行",
            "",
            "逗号,引号\",换行\n一起来",
        ).forEach { raw ->
            assertEquals(
                "写出去再读回来必须还是同一个字符串：$raw",
                listOf(listOf(raw, "x")),
                Csv.parse(Csv.escape(raw) + ",x"),
            )
        }
    }

    // ============================================================== 文件名

    @Test
    fun `从导出文件名里去掉时间戳`() {
        assertEquals(
            "不去掉的话每导入一次就多一个只差时间戳的名字",
            "播放列表",
            PlaybackImport.fallbackNameOf("播放列表-20261004-111548.csv", "兜底"),
        )
        assertEquals(
            "扩展名也去掉",
            "播放记录",
            PlaybackImport.fallbackNameOf("播放记录.json", "兜底"),
        )
        assertEquals(
            "时间戳不在结尾时不动它（那是名字的一部分）",
            "20261004-111548 的记录",
            PlaybackImport.fallbackNameOf("20261004-111548 的记录.csv", "兜底"),
        )
    }

    @Test
    fun `文件名洗完之后什么也不剩时用兜底名字`() {
        listOf(
            null,
            "",
            "   ",
            ".csv",
            "-20261004-111548.csv",
        ).forEach { name ->
            assertEquals(
                "「$name」洗完之后没有可用字符，得给一个能翻译的兜底名字（空名字建出来的列表用户看不见也删不掉）",
                "导入的播放列表",
                PlaybackImport.fallbackNameOf(name, "导入的播放列表"),
            )
        }
    }

    @Test
    fun `只取最后一个点之前的部分`() {
        assertEquals(
            "`substringBeforeLast` 而不是 `substringBefore`：名字里带点的文件（专辑、版本号）很多" +
                "，从第一个点切断会把名字吃掉一大截",
            "专辑 2024.remaster",
            PlaybackImport.fallbackNameOf("专辑 2024.remaster.csv", "兜底"),
        )
    }

    @Test
    fun `超长的文件名被截断到名字上限`() {
        val name = PlaybackImport.fallbackNameOf("甲".repeat(200) + ".csv", "兜底")

        assertEquals(
            "列表名有长度上限，超出的部分必须在这里就截掉（不然截断会发生在别处，" +
                "而后缀「 (2)」就没地方加了）",
            80,
            name.length,
        )
    }

    // ============================================================ 资源守卫

    @Test
    fun `别名表里必须能找到三种语言的全部导出列名`() {
        val table = aliases()
        listOf(DIR_ZH, DIR_EN, DIR_HANT).forEach { dir ->
            listOf(
                COL_LIST to CsvColumn.List,
                COL_TITLE to CsvColumn.Title,
                COL_ARTIST to CsvColumn.Artist,
                COL_KIND to CsvColumn.Kind,
                COL_URI to CsvColumn.Uri,
                COL_MEDIA_ID to CsvColumn.MediaId,
                COL_DURATION_MS to CsvColumn.DurationMs,
            ).forEach { (key, column) ->
                assertEquals(
                    "$dir 的「${label(dir, key)}」没在别名表里——" +
                        "导出写出去的列名认不回来，就是「自己的文件自己读不了」",
                    column,
                    table.columnOf(label(dir, key)),
                )
            }
        }
    }

    @Test
    fun `别名表里没有导出列之外的列名`() {
        val table = aliases()
        listOf(DIR_ZH, DIR_EN, DIR_HANT).forEach { dir ->
            listOf(COL_INDEX, COL_DURATION, COL_POSITION, COL_POSITION_MS, COL_PLAYED_AT, COL_FILE_STATE)
                .forEach { key ->
                    assertNull(
                        "$dir 的「${label(dir, key)}」不该被认成某一列：它是给人看的，" +
                            "让它参与认列只会把「标题」和「时长」搞混",
                        table.columnOf(label(dir, key)),
                    )
                }
        }
    }

    @Test
    fun `导入文案在三种语言里键集一致`() {
        val zh = importKeys(DIR_ZH)
        assertTrue("几乎读不到导入文案，正则可能跟不上资源写法了：$zh", zh.size >= 12)
        listOf(DIR_EN, DIR_HANT).forEach { dir ->
            assertEquals("$dir 与简中的导入键不一致", zh.sorted(), importKeys(dir).sorted())
        }
    }

    @Test
    fun `英文资源里没有残留中文`() {
        // 只查英文。繁体中文那一份**本来就该是中文**，把 CJK 当「残留」会
        // 直接把一整份正确的资源判成错的——这种守卫写错了比没写更坏。
        val cjk = Regex("[\\u4e00-\\u9fff]")
        stringsOf(DIR_EN)
            .filterKeys { it.startsWith("msp_import_") && !it.startsWith("msp_import_alias_") }
            .forEach { (key, value) ->
                assertFalse(
                    "$DIR_EN 里的 $key 带着中文：$value",
                    cjk.containsMatchIn(value),
                )
            }
    }

    @Test
    fun `别名数组只写在 values 里且不可翻译`() {
        val source = File(repoRoot(), "core/data/src/main/res/$DIR_ZH/strings.xml").readText()
        assertTrue(
            "别名表本身就是三种语言的写法，再按语言翻译一遍只会把它拆碎",
            source.contains("<string-array name=\"msp_import_alias_list\" translatable=\"false\">"),
        )
        listOf(DIR_EN, DIR_HANT).forEach { dir ->
            val text = File(repoRoot(), "core/data/src/main/res/$dir/strings.xml").readText()
            assertFalse(
                "$dir 里不该有别名数组（它是 translatable=\"false\" 的，翻译平台上不会有它）",
                text.contains("msp_import_alias_"),
            )
        }
    }

    @Test
    fun `别名数组里的每一条都非空`() {
        readArrays(DIR_ZH).forEach { (name, items) ->
            assertTrue("$name 是空的，那一列就永远认不出来", items.isNotEmpty())
            assertTrue("$name 里有空条目", items.none { it.isBlank() })
        }
    }

    // ================================================================ 工具

    private fun aliases(): CsvAliases = aliasesFrom(DIR_ZH)

    /** 期望里的资源 id → `values/` 里的名字。导出用到的全部文案都在这里。 */
    private fun track(
        mediaId: String,
        uri: String = "content://a",
        title: String = "标题",
        artist: String? = null,
        kind: MediaKind = MediaKind.UNKNOWN,
        durationMs: Long = 204_000L,
        positionMs: Long? = null,
        playedAtMs: Long? = null,
        missing: Boolean = false,
    ): PlaybackExportTrack = PlaybackExportTrack(
        mediaId = mediaId,
        uri = uri,
        title = title,
        artist = artist,
        kind = kind,
        durationMs = durationMs,
        positionMs = positionMs,
        playedAtMs = playedAtMs,
        missing = missing,
    )

    /**
     * 把一个 [MspText] 换成真实译文。
     *
     * 通过 `R.string.xxx` 反查它在 `strings.xml` 里的名字（见 [NAME_BY_ID]）：
     * 写错一个资源 id 会渲染成另一句话，断言立刻不成立；没登记的 id 直接报错，
     * 这样「新加一列忘了登记」不会被当成无关紧要的笔误。
     */
    private fun resolve(text: MspText): String = when (text) {
        is MspText.Plain -> text.text
        is MspText.Res -> {
            val name = NAME_BY_ID[text.id]
                ?: error("测试没登记这个资源 id：${text.id}")
            check(text.args.isEmpty()) { "$name 在导出里带了参数，文件里会留下没填的占位符" }
            stringsOf(DIR_ZH).getValue(name)
        }
    }

    private companion object {
        const val DIR_ZH = "values"
        const val DIR_EN = "values-en"
        const val DIR_HANT = "values-b+zh+Hant"

        const val COL_LIST = "msp_export_column_list"
        const val COL_INDEX = "msp_export_column_index"
        const val COL_TITLE = "msp_export_column_title"
        const val COL_ARTIST = "msp_export_column_artist"
        const val COL_KIND = "msp_export_column_kind"
        const val COL_DURATION = "msp_export_column_duration"
        const val COL_DURATION_MS = "msp_export_column_duration_ms"
        const val COL_POSITION = "msp_export_column_position"
        const val COL_POSITION_MS = "msp_export_column_position_ms"
        const val COL_PLAYED_AT = "msp_export_column_played_at"
        const val COL_FILE_STATE = "msp_export_column_file_state"
        const val COL_URI = "msp_export_column_uri"
        const val COL_MEDIA_ID = "msp_export_column_media_id"
        const val KIND_AUDIO = "msp_export_kind_audio"
        const val KIND_VIDEO = "msp_export_kind_video"
        const val FILE_OK = "msp_export_file_ok"

        const val CRLF = "\r\n"

        /** `R.array.*` → `strings.xml` 里的名字。 */
        val ARRAY_NAME_BY_ID: Map<Int, String> = mapOf(
            R.array.msp_import_alias_list to "msp_import_alias_list",
            R.array.msp_import_alias_title to "msp_import_alias_title",
            R.array.msp_import_alias_artist to "msp_import_alias_artist",
            R.array.msp_import_alias_kind to "msp_import_alias_kind",
            R.array.msp_import_alias_uri to "msp_import_alias_uri",
            R.array.msp_import_alias_media_id to "msp_import_alias_media_id",
            R.array.msp_import_alias_duration_ms to "msp_import_alias_duration_ms",
            R.array.msp_import_alias_kind_audio to "msp_import_alias_kind_audio",
            R.array.msp_import_alias_kind_video to "msp_import_alias_kind_video",
            R.array.msp_import_alias_kind_unknown to "msp_import_alias_kind_unknown",
        )

        /** 资源 id → `strings.xml` 里的名字（导出往返用得到的那些）。 */
        val NAME_BY_ID: Map<Int, String> = mapOf(
            R.string.msp_export_column_list to "msp_export_column_list",
            R.string.msp_export_column_index to "msp_export_column_index",
            R.string.msp_export_column_title to "msp_export_column_title",
            R.string.msp_export_column_artist to "msp_export_column_artist",
            R.string.msp_export_column_kind to "msp_export_column_kind",
            R.string.msp_export_column_duration to "msp_export_column_duration",
            R.string.msp_export_column_duration_ms to "msp_export_column_duration_ms",
            R.string.msp_export_column_position to "msp_export_column_position",
            R.string.msp_export_column_position_ms to "msp_export_column_position_ms",
            R.string.msp_export_column_played_at to "msp_export_column_played_at",
            R.string.msp_export_column_file_state to "msp_export_column_file_state",
            R.string.msp_export_column_uri to "msp_export_column_uri",
            R.string.msp_export_column_media_id to "msp_export_column_media_id",
            R.string.msp_export_kind_audio to "msp_export_kind_audio",
            R.string.msp_export_kind_video to "msp_export_kind_video",
            R.string.msp_export_kind_unknown to "msp_export_kind_unknown",
            R.string.msp_export_file_ok to "msp_export_file_ok",
            R.string.msp_export_file_missing to "msp_export_file_missing",
        )

        /**
         * 某语言的**全部**字符串键值。
         *
         * 缓存起来：每个用例都会读好几次，而不缓存的话一条测试里读同一个文件
         * 十几遍纯属浪费。
         */
        val stringsCache: MutableMap<String, Map<String, String>> = HashMap()

        fun stringsOf(dir: String): Map<String, String> = stringsCache.getOrPut(dir) {
            val file = File(repoRoot(), "core/data/src/main/res/$dir/strings.xml")
            check(file.isFile) { "找不到资源文件 $file" }
            STRING_PATTERN.findAll(file.readText())
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
        }

        /** 只取导入那一段的键（`msp_import_` 前缀）。 */
        fun importKeys(dir: String): List<String> =
            stringsOf(dir).keys.filter { it.startsWith("msp_import_") }

        fun label(dir: String, name: String): String = stringsOf(dir).getValue(name)

        /** 某语言的 `string-array`。 */
        fun readArrays(dir: String): Map<String, List<String>> {
            val file = File(repoRoot(), "core/data/src/main/res/$dir/strings.xml")
            check(file.isFile) { "找不到资源文件 $file" }
            return ARRAY_PATTERN.findAll(file.readText()).associate { match ->
                match.groupValues[1] to ITEM_PATTERN.findAll(match.groupValues[2])
                    .map { it.groupValues[1].trim() }
                    .toList()
            }
        }

        fun aliasesFrom(dir: String): CsvAliases {
            val arrays = readArrays(dir)
            return CsvAliases.of { id ->
                arrays[ARRAY_NAME_BY_ID.getValue(id)]
                    ?: error("$dir 里没有 ${ARRAY_NAME_BY_ID.getValue(id)}")
            }
        }

        /** 拼一张 CSV。字段不做转义：这些用例里的内容本来就没有需要转义的东西。 */
        fun csvOf(vararg rows: List<String>): String =
            rows.joinToString(CRLF) { it.joinToString(",") } + CRLF

        val STRING_PATTERN = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val ARRAY_PATTERN = Regex(
            """<string-array name="([^"]+)"[^>]*>(.*?)</string-array>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val ITEM_PATTERN = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
    }
}

/** 从测试的工作目录往上找仓库根（认得 `settings.gradle.kts`）。 */
private fun repoRoot(): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("找不到仓库根（settings.gradle.kts）")
}
