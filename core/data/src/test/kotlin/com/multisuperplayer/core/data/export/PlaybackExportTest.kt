package com.multisuperplayer.core.data.export

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.text.MspText
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住「导出成文件」这一层的两条底线：**不丢数据**、**Excel 打得开**。
 *
 * ## 为什么这些断言必须存在（而不是「看起来对」）
 *
 * 导出是单向的：用户按下按钮、文件被写到他自己选的目录，然后我们再也没有机会
 * 纠正它。三件最容易错又最看不出来的事：
 *
 * 1. **CSV 转义**。一个标题里带逗号，那一行就会多出一列，**从这一行往下整张表错位**；
 *    带引号而不翻倍，字段会提前结束；带换行而不加引号，一行变两行。这三种在界面上
 *    都完全正常，只有把文件打开才看得见。
 * 2. **失效条目的去留**。播放列表刻意保留「文件已不在」的条目（见 `PlaylistRows.queue`），
 *    导出时把它们丢掉，导出的文件就和「这个播放器实际会播什么」对不上了——
 *    而用户导出播放列表，多半就是为了知道这件事。
 * 3. **BOM**。CSV 少了 BOM，简中环境的 Excel 按 GBK 解 UTF-8，整张表是乱码；
 *    JSON 多了 BOM，严格遵守 RFC 8259 的解析器直接报错。两个方向都很难查。
 *
 * ## 断言方式
 *
 * 纯 JVM 单测拿不到 `Resources`（`core:data` 没有 Robolectric），所以编码器的
 * `resolve: (MspText) -> String` 在测试里换成一个**按资源 id 查名字**的实现：
 * 先用 `R.string.xxx` 找到它在 `values/strings.xml` 里的名字，再取那份译文。
 * 好处是「编码器里写错了资源 id」会被抓到——写错一个 id 就会渲染成同事的列名，
 * 而不是悄悄变成一句看似合理的话。
 *
 * 注意 `org.junit.Assert.assertEquals` 的参数顺序是「消息、期望、实际」，
 * 与 `kotlin.test` 那个相反（写反了会掉到别的重载上）。
 */
class PlaybackExportTest {

    // ===== CSV =====

    @Test
    fun `CSV 表头是中文且顺序固定`() {
        val csv = PlaybackExport.csv(listOf(collection(RECENT_NAME, track())), ::resolve)

        assertEquals(
            "列顺序就是给人读的顺序；单集合导出不写「列表」列；" +
                "数字列本身不写单位、单位进表头（不然 Excel 没法按它排序）",
            headers(
                "msp_export_column_index",
                "msp_export_column_title",
                "msp_export_column_artist",
                "msp_export_column_kind",
                "msp_export_column_duration",
                "msp_export_column_duration_ms",
                "msp_export_column_file_state",
                "msp_export_column_uri",
                "msp_export_column_media_id",
            ),
            csv.substringBefore(CRLF),
        )
    }

    @Test
    fun `多集合导出才写列表列，且每行都标出属于哪个集合`() {
        val rows = PlaybackExport.csv(
            listOf(
                collection("歌单 A", track(title = "甲")),
                collection("歌单 B", track(title = "乙")),
            ),
            ::resolve,
        ).trimEnd('\r', '\n').split(CRLF)

        assertEquals(
            "多个集合时第一列必须是「列表」，否则两个歌单的行混在一起谁也分不出来",
            label("msp_export_column_list"),
            rows.first().substringBefore(CSV_SEPARATOR),
        )
        assertTrue("第一行要带集合名", rows[1].startsWith("歌单 A,"))
        assertTrue("第二行要带集合名", rows[2].startsWith("歌单 B,"))
    }

    @Test
    fun `字段里的逗号必须加引号，否则整张表从这一行开始错位`() {
        val row = PlaybackExport.csv(
            listOf(collection(RECENT_NAME, track(title = "他说,\"真的\""))),
            ::resolve,
        ).trimEnd('\r', '\n').split(CRLF)[1]

        assertTrue(
            "逗号要加引号、引号要写成两个，实际：$row",
            row.contains("\"他说,\"\"真的\"\"\""),
        )
        // 列数不变才是真正要保证的事：转义错了这里会是 10。
        assertEquals("转义之后这一行仍然是 9 个字段：$row", 9, splitRow(row).size)
    }

    @Test
    fun `标题里的换行不能把一行拆成两行`() {
        // ID3 里标题带换行很常见。不加引号的话这一行会被拆开，
        // 之后每一行的列都会被顶偏。
        val csv = PlaybackExport.csv(
            listOf(collection(RECENT_NAME, track(title = "第一行\n第二行"))),
            ::resolve,
        )

        val rows = csv.trimEnd('\r', '\n').split(CRLF)
        assertEquals("表头 + 1 行，换行不能额外拆出一条记录：$rows", 2, rows.size)
        assertTrue("含换行的字段必须整体被引号包住：${rows[1]}", rows[1].contains("\"第一行\n第二行\""))
    }

    @Test
    fun `行尾是 CRLF`() {
        val csv = PlaybackExport.csv(listOf(collection(RECENT_NAME, track())), ::resolve)

        assertTrue("RFC 4180 就是 CRLF，而且老记事本对纯 \\n 的容忍度很差", csv.contains(CRLF))
        assertFalse("不能混用 \\n：那会让某些表格软件把整个文件显示成一行", csv.replace(CRLF, "").contains("\n"))
    }

    @Test
    fun `失效的条目也要导出并标注出来`() {
        // 丢掉它 = 导出的文件比播放器实际会播的少一条，而用户看不出来少了什么。
        val rows = PlaybackExport.csv(
            listOf(
                collection(
                    "歌单",
                    track(title = "还在的"),
                    track(title = "找不到的", missing = true),
                ),
            ),
            ::resolve,
        ).trimEnd('\r', '\n').split(CRLF)

        val ok = rows.firstOrNull { it.contains("还在的") }
        val gone = rows.firstOrNull { it.contains("找不到的") }
        assertTrue("失效行不能被丢掉：$rows", gone != null)
        assertTrue("还在的那一行标「正常」：$ok", ok != null && ok.contains(label("msp_export_file_ok")))
        assertTrue(
            "失效那一行必须标「文件已不在」，否则它看着跟能播的一模一样：$gone",
            gone != null && gone.contains(label("msp_export_file_missing")),
        )
    }

    @Test
    fun `播放记录多出播放位置与播放时间两列，播放列表则完全没有这两列`() {
        val recent = PlaybackExport.csv(
            listOf(collection(RECENT_NAME, track(positionMs = 91_000L, playedAtMs = 1_700_000_000_000L))),
            ::resolve,
        )
        val playlist = PlaybackExport.csv(listOf(collection("歌单", track())), ::resolve)

        assertTrue(
            "有位置数据才出现这一列",
            recent.substringBefore(CRLF).contains(label("msp_export_column_position")),
        )
        assertTrue("位置要给人看", recent.contains(TimeFormat.clock(91_000L)))
        assertTrue("位置还要给机器算", recent.contains("91000"))
        assertTrue("播放时间列", recent.substringBefore(CRLF).contains(label("msp_export_column_played_at")))
        assertTrue("播放时间给人看", recent.contains(TimeFormat.dateTime(1_700_000_000_000L)))

        assertFalse(
            "播放列表没有「播到哪儿」这个概念：整列空的列不该出现在表里",
            playlist.substringBefore(CRLF).contains(label("msp_export_column_position")),
        )
    }

    @Test
    fun `时长同时给人看和给机器算`() {
        val csv = PlaybackExport.csv(listOf(collection(RECENT_NAME, track(durationMs = 331_000L))), ::resolve)

        assertTrue("给人看：05:31", csv.contains(TimeFormat.clock(331_000L)))
        assertTrue("给机器算：331000（这样 Excel 才排得了序）", csv.contains("331000"))
    }

    // ===== JSON =====

    @Test
    fun `JSON 的字段名是英文、集合名是用户语言`() {
        val json = PlaybackExport.json(
            collections = listOf(collection("歌单 A", track(mediaId = "42"))),
            exportedAtMs = 1_700_000_000_000L,
            resolve = ::resolve,
        )

        // 键名是形状（给程序），集合名是内容（给人）。
        val root = Json.parseToJsonElement(json).jsonObject
        assertTrue(
            "导出时间要写成带时区的 ISO 时间：${root.getValue("exportedAt")}",
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$""")
                .matches(root.getValue("exportedAt").jsonPrimitive.content),
        )
        val first = firstCollection(json)
        assertEquals("歌单 A", first.getValue("name").jsonPrimitive.content)
        val t = firstTrack(json)
        assertEquals("42", t.getValue("mediaId").jsonPrimitive.content)
        assertEquals(331_000L, t.getValue("durationMs").jsonPrimitive.long)
        assertFalse("能用（没失效）的条目就是 false", t.getValue("missing").jsonPrimitive.boolean)
    }

    @Test
    fun `JSON 省略不属于这个集合的字段，而不是写一堆 null`() {
        val playlist = PlaybackExport.json(
            collections = listOf(collection("歌单", track())),
            exportedAtMs = 1L,
            resolve = ::resolve,
        )
        val recent = PlaybackExport.json(
            collections = listOf(collection(RECENT_NAME, track(positionMs = 1_000L, playedAtMs = 2_000L))),
            exportedAtMs = 1L,
            resolve = ::resolve,
        )

        assertNull(
            "播放列表里没有「播到哪儿」：写 null 会让接收方以为这个字段有意义但没采到",
            firstTrack(playlist)["positionMs"],
        )
        assertNull("同上，播放时间也不属于播放列表", firstTrack(playlist)["playedAtMs"])
        assertEquals(1_000L, firstTrack(recent).getValue("positionMs").jsonPrimitive.long)
    }

    @Test
    fun `JSON 里没有艺术家写 null 而不是空串`() {
        val json = PlaybackExport.json(
            collections = listOf(collection(RECENT_NAME, track(artist = null))),
            exportedAtMs = 1L,
            resolve = ::resolve,
        )

        // 「没有艺术家」和「艺术家是空字符串」是两件事，备份/脚本那边必须分得开。
        assertEquals(JsonNull, firstTrack(json).getValue("artist"))
    }

    @Test
    fun `JSON 是能被解析的合法文本`() {
        // 打错一个括号在界面上完全看不出来：文件照样写成功，用户双击才发现打不开。
        val json = PlaybackExport.json(
            collections = listOf(collection("歌单", track(title = "带\"引号\"和\\反斜杠"))),
            exportedAtMs = 1L,
            resolve = ::resolve,
        )

        val parsed: JsonObject = Json.parseToJsonElement(json).jsonObject
        assertEquals(1, parsed.getValue("collections").jsonArray.size)
        assertFalse("不能往 JSON 里写 BOM（RFC 8259 不允许，解析器会直接报错）", json.startsWith("\uFEFF"))
    }

    // ===== 文件名 =====

    @Test
    fun `文件名带时间戳和扩展名，并洗掉文件系统不接受的字符`() {
        val name = PlaybackExport.suggestedFileName("a/b:c*d?e\"f<g>h|i\\j", PlaybackExportFormat.CSV)

        assertTrue("非法字符要洗掉：$name", name.none { it in "\\/:*?\"<>|" })
        assertTrue("扩展名要对：$name", name.endsWith(".csv"))
        assertTrue("要带时间戳（字典序就是时间序）：$name", STAMP_TAIL.containsMatchIn(name))
    }

    @Test
    fun `点名开头的名字不能留下前导点（那会变成隐藏文件）`() {
        val name = PlaybackExport.suggestedFileName("...", PlaybackExportFormat.JSON)

        assertFalse("只剩点的时候不能拿它当基名：$name", name.startsWith("."))
        assertTrue(name.endsWith(".json"))
    }

    @Test
    fun `空名字只用时间戳`() {
        val name = PlaybackExport.suggestedFileName("   ", PlaybackExportFormat.CSV)

        assertTrue("空白名不能变成「-20250101-...」这种开头是横杠的名字：$name", name.first().isDigit())
    }

    @Test
    fun `超长名字被截断且不留下半个 emoji`() {
        // 59 个字 + emoji：截断正好落在代理对中间，会留下一个孤立的高代理字符。
        val name = PlaybackExport.suggestedFileName("字".repeat(59) + "🎮", PlaybackExportFormat.CSV)
        val base = name.removeRange(STAMP_TAIL.find(name)!!.range)

        assertTrue("基名要截断，否则某些文件系统直接拒绝：${base.length}", base.length <= 60)
        assertFalse(
            "孤立的高代理字符写进文件系统会变成一个问号（甚至直接被拒）",
            base.lastOrNull()?.isHighSurrogate() == true,
        )
    }

    // ===== BOM =====

    @Test
    fun `BOM 只给 CSV 加，JSON 绝不加`() {
        val csv = textBytes("标题\r\n", withBom = true)
        val json = textBytes("""{"a":1}""", withBom = false)

        assertEquals(
            "CSV 必须带 BOM，否则简中环境里 Excel 双击打开是乱码",
            listOf(0xEF, 0xBB, 0xBF),
            csv.take(3).map { it.toInt() and 0xFF },
        )
        assertEquals(
            "JSON 绝不能带 BOM：RFC 8259 不允许，kotlinx.serialization 也会解析失败",
            0x7B,
            json.first().toInt() and 0xFF,
        )
    }

    @Test
    fun `BOM 的方向由格式自己决定，不靠调用方记着`() {
        // 上面那条测的是「BOM 这个机制对不对」，这条测的是「哪个格式该加」。
        // 两者都得有：把开关放到调用点（`withBom = format == CSV`）时，
        // 每多一个导出入口就多一处可能写反的地方，而写反的两个方向在本地
        // 都不报错——CSV 少 BOM 是「别人打开是乱码」，JSON 多 BOM 是
        // 「别人的解析器在第一个字节就失败」。
        assertTrue("CSV 要 BOM（替 Excel 打的补丁）", PlaybackExportFormat.CSV.needsBom)
        assertFalse("JSON 不要 BOM（RFC 8259 §8.1）", PlaybackExportFormat.JSON.needsBom)
        // 两个方向都断言：只有一条 true 的测试在「全都返回 true」时也会通过。
        assertEquals(
            "两种格式的扩展名必须能直接用在小写文件名上",
            listOf("csv", "json"),
            PlaybackExportFormat.entries.map { it.extension },
        )
    }

    // ===== 资源守卫 =====

    @Test
    fun `三种语言的导出键集必须一致`() {
        val zh = readResource("values").keys.sorted()
        listOf("values-en", "values-b+zh+Hant").forEach { dir ->
            assertEquals("$dir 与 values 的导出键不一致", zh, readResource(dir).keys.sorted())
        }
    }

    @Test
    fun `英文资源里不能残留中文`() {
        readResource("values-en").forEach { (key, value) ->
            assertTrue(
                "$key 的英文文案里有中文字符：$value",
                value.none { it.code in 0x4E00..0x9FFF || it.code in 0x3000..0x303F || it.code in 0xFF00..0xFFEF },
            )
        }
    }

    @Test
    fun `缺失条目的计数`() {
        assertEquals(2, collection("歌单", track(missing = true), track(), track(missing = true)).missingCount)
        assertEquals(0, collection("歌单", track()).missingCount)
    }

    // ===== 下面都是给上面那些断言用的工具 =====

    private fun collection(name: String, vararg tracks: PlaybackExportTrack) =
        PlaybackExportCollection(name = name, tracks = tracks.toList())

    private fun track(
        title: String = "夜曲",
        artist: String? = "周杰伦",
        kind: MediaKind = MediaKind.AUDIO,
        durationMs: Long = 331_000L,
        positionMs: Long? = null,
        playedAtMs: Long? = null,
        missing: Boolean = false,
        mediaId: String = "7",
        uri: String = "content://media/external/audio/media/7",
    ) = PlaybackExportTrack(
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

    /** 导出里的第一个集合。 */
    private fun firstCollection(json: String): JsonObject =
        Json.parseToJsonElement(json).jsonObject.getValue("collections").jsonArray.first().jsonObject

    /** 导出里的第一条曲目。 */
    private fun firstTrack(json: String): JsonObject =
        firstCollection(json).getValue("tracks").jsonArray.first().jsonObject

    /**
     * 数一行里有几个字段（按 RFC 4180 解析：引号内的逗号不算分隔符，
     * `""` 表示一个字面引号）。**必须真的解析**：直接 `split(",")` 无论转义对错
     * 都会给出同样的结果，那种「验证」等于没验。
     */
    private fun splitRow(row: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < row.length) {
            val c = row[i]
            when {
                inQuotes && c == '"' && i + 1 < row.length && row[i + 1] == '"' -> {
                    current.append('"')
                    i += 2
                }

                c == '"' -> {
                    inQuotes = !inQuotes
                    i++
                }

                c == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                    i++
                }

                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        fields += current.toString()
        return fields
    }

    /** 期望里的表头：按资源**名字**写，再换成译文，顺序错一个就会被抓到。 */
    private fun headers(vararg names: String): String = names.joinToString(CSV_SEPARATOR) { label(it) }

    private fun label(name: String): String = resources.getValue(name)

    /**
     * 把一个 [MspText] 换成真实译文。
     *
     * 通过 `R.string.xxx` 反查它在 `strings.xml` 里的名字（见 [NAME_BY_ID]）：
     * 编码器里写错一个资源 id，这里渲染出来的就是同事的列名，断言会立刻不成立。
     * 没登记的 id 直接报错，这样「新加一列忘了登记」不会被当成无关紧要的笔误。
     */
    private fun resolve(text: MspText): String = when (text) {
        is MspText.Plain -> text.text
        is MspText.Res -> {
            val name = NAME_BY_ID[text.id]
                ?: error("测试没登记这个资源 id：${text.id}（新增文案时要在这里登记它的名字）")
            // 导出用的这些标签都不带参数（它们只是列名/枚举名）。带参数说明有人把
            // 需要填充的文案塞进了单元格——那样文件里会留下一个没填的 `%1$s`。
            check(text.args.isEmpty()) { "$name 在导出里带了参数，文件里会留下没填的占位符" }
            resources.getValue(name)
        }
    }

    /** 读某个语言的 `strings.xml` 里属于导出的那些键。 */
    private fun readResource(dir: String): Map<String, String> {
        val file = File(repoRoot(), "core/data/src/main/res/$dir/strings.xml")
        assertTrue("找不到资源文件 $file", file.isFile)
        val all = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
            .filterKeys { it.startsWith("msp_export_") }
        // 正则漏掉一条（例如以后加了 `translatable="false"` 这类属性）时，
        // 「键集一致」那条测试会跟着一起变绿，所以这里留一个下限。
        assertTrue("$dir 里几乎读不到导出键，正则可能跟不上资源写法了：${all.size}", all.size >= 20)
        return all
    }

    private companion object {
        const val CRLF = "\r\n"
        const val CSV_SEPARATOR = ","
        const val RECENT_NAME = "播放记录"

        /** 文件名尾巴上的「-时间戳.扩展名」。 */
        val STAMP_TAIL = Regex("""-\d{8}-\d{6}\.[a-z]+$""")

        /** 资源 id → `strings.xml` 里的名字。导出用到的全部文案都在这里。 */
        val NAME_BY_ID: Map<Int, String> = mapOf(
            R.string.msp_export_format_csv to "msp_export_format_csv",
            R.string.msp_export_format_json to "msp_export_format_json",
            R.string.msp_export_collection_recent to "msp_export_collection_recent",
            R.string.msp_export_collection_playlists to "msp_export_collection_playlists",
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

        /** `values/strings.xml`（简中）里的全部键值：断言里出现的那份译文。 */
        val resources: Map<String, String> by lazy {
            val file = File(repoRoot(), "core/data/src/main/res/values/strings.xml")
            Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .associate { it.groupValues[1] to it.groupValues[2].trim() }
        }
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
