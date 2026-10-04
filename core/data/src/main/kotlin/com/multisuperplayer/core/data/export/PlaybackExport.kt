package com.multisuperplayer.core.data.export

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.RecentPlay
import com.multisuperplayer.core.model.text.MspText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 导出的文件格式。
 *
 * 只有两种，且两种都不是可选项——理由不同：
 *
 * - [CSV]：给**人和表格软件**看的。用户真正会做的是把播放记录丢进 Excel 排序、
 *   数一数哪首歌听得最多。它必须带 UTF-8 BOM，否则在简中环境里双击打开是乱码
 *   （见 [TextExportWriter]）。
 * - [JSON]：给**程序和备份**用的。它保留结构（哪个列表里有哪些曲目）、区分
 *   「0」和「没有」，也不会被表格软件自作主张地改格式（`01` 变成 `1`、
 *   长数字变成科学计数法）。
 *
 * 刻意**不做** M3U8：那是「播放列表」的另一种意思（播放器拿来直接播的清单），
 * 而这里的导出是「把记录拿出去」；混进来会让用户以为导出的文件能在播放器里打开。
 */
enum class PlaybackExportFormat(
    /** 扩展名，不带点。 */
    val extension: String,
    /** 界面上给人选的标签。`CSV` / `JSON` 三个语言里都一样写。 */
    val label: MspText,
    /**
     * 写文件时要不要打 UTF-8 BOM。
     *
     * ⚠️ 这是**格式自己的属性**，不是调用方可选的一个开关。放在这里而不是让
     * 每个调用点写 `withBom = format == CSV`：那样每多一个导出入口就多一处
     * 可能写反的地方，而写反的两种症状都极难查——CSV 少 BOM 是「别人打开是乱码，
     * 我们这边一切正常」，JSON 多 BOM 是「严格遵守 RFC 8259 的解析器在第一个字节
     * 就失败」，两个方向都没有任何本地报错。
     */
    val needsBom: Boolean,
) {
    /** Excel / WPS 双击打开要认得出 UTF-8，否则中文标题全变乱码（简中系统按 GBK 猜）。 */
    CSV("csv", MspText.Res(R.string.msp_export_format_csv), needsBom = true),

    /** RFC 8259 §8.1 要求实现不得加 BOM，`kotlinx.serialization` 自己写了 BOM 也读不回来。 */
    JSON("json", MspText.Res(R.string.msp_export_format_json), needsBom = false),
}

/**
 * 导出里的一条曲目（**与来源无关**的中间形态）。
 *
 * 播放记录来自 [RecentPlay]（字段全、还带播放位置），播放列表快照来自
 * `PlaylistItem`（只有标题/艺术家/时长/类型，可能在库里已经查不到了）。
 * 两个编码器只认这一种形态，于是「CSV 与 JSON 对同一条记录该写什么」只有一处答案。
 */
data class PlaybackExportTrack(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val kind: MediaKind,
    val durationMs: Long,
    /** 播放位置；不属于这个概念时为 null（播放列表没有这一项）。 */
    val positionMs: Long? = null,
    /** 播放时间；不属于这个概念时为 null。 */
    val playedAtMs: Long? = null,
    /**
     * 这条记录指向的文件现在还在不在。
     *
     * ⚠️ **失效的条目也要导出**。播放列表的队列是刻意保留失效项的
     * （见 `PlaylistRows.queue`：库里查不到但文件还在的还能播），
     * 如果导出时把它们悄悄丢掉，导出的文件和「这个播放器实际会播什么」就对不上了——
     * 而用户导出播放列表，八成就是为了知道这件事。丢数据比多一列更糟，
     * 所以这里带一个 [missing] 标记，让接收方自己决定怎么处理。
     */
    val missing: Boolean = false,
) {
    companion object {
        /** 播放记录 → 导出行。 */
        fun of(record: RecentPlay): PlaybackExportTrack {
            val entry = record.entry
            return PlaybackExportTrack(
                mediaId = entry.id,
                uri = entry.uri,
                title = entry.title,
                artist = entry.artist,
                kind = entry.kind,
                durationMs = entry.durationMs,
                positionMs = record.positionMs,
                playedAtMs = record.playedAtMs,
            )
        }
    }
}

/**
 * 一个导出集合：要么「播放记录」，要么某一个播放列表。
 *
 * @param name 集合名。**已经本地化过**（「播放记录」，或者用户给的列表名）——
 *   它会被写进文件，所以必须是用户能读懂的语言，理由见 [PlaybackExport.csv]。
 */
data class PlaybackExportCollection(
    val name: String,
    val tracks: List<PlaybackExportTrack>,
) {
    val missingCount: Int get() = tracks.count { it.missing }
}

/**
 * 把播放记录 / 播放列表编码成 CSV 或 JSON 文本。
 *
 * ## 为什么两种格式都带中文列名
 *
 * 导出的第一读者是用户自己的眼睛和 Excel。写成 `title,artist` 的英文列名，
 * 中文用户打开表格要先把表头翻译一遍；而 JSON 那边通常只是被备份、被脚本读，
 * 于是两边各取所需。**JSON 里需要机器读的字段名保持英文**（`title` / `durationMs`
 * 这种是数据形状，不是文案），只有「集合名」这种内容随语言走。
 *
 * ## 为什么 Excel 必须能直接打开
 *
 * CSV 是「用户的表格软件的一个文件格式」，不是我们的内部格式。所以：
 * 字段一律 UTF-8 + 带 BOM（见 `TextExportWriter`）、时间写成 `2025-01-01 10:00`
 * 这种双分隔符的写法（`2025/1/1` 在 Excel 里会被当成日期，于是排序按日期排），
 * 数字列**不写单位**、单位进表头（`时长（毫秒）`），这样 Excel 才能把它们当数字
 * 排序——「时长」那一列带个「3.5 分钟」的文字是没法排序的。
 *
 * ## 为什么区分「时长」和「时长（毫秒）」
 *
 * 一个给人看（`05:31`），一个给机器算（`331000`）。只留一个的话，
 * 要么用户看不懂，要么 Excel 把它当文本。多一列的成本远低于一种没法用的导出。
 */
object PlaybackExport {

    /** 集合名「播放记录」。 */
    fun recentName(): MspText = MspText.Res(R.string.msp_export_collection_recent)

    /** 集合名「播放列表」（导出全部时的统称）。 */
    fun allPlaylistsName(): MspText = MspText.Res(R.string.msp_export_collection_playlists)

    /**
     * 建议的文件名。`<基础名>-<时间戳>.<扩展名>`。
     *
     * 时间戳用 `yyyyMMdd-HHmmss` 而不是更漂亮的中文日期：文件名会被脚本、
     * 同步盘、云盘当成排序键，而 `20250101-100000` 的字典序就是时间序。
     *
     * @param base 基础名。来自列表名或资源文案，**都可能是任意字符串**，
     *   所以这里必须洗一遍（见 [sanitize]）。
     */
    fun suggestedFileName(base: String, format: PlaybackExportFormat): String {
        val stamp = SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date())
        val clean = sanitize(base)
        return if (clean.isEmpty()) {
            "$stamp.${format.extension}"
        } else {
            "$clean-$stamp.${format.extension}"
        }
    }

    /**
     * CSV 文本。**行尾用 `\r\n`**——RFC 4180 就是那么写的，而且 Windows 上的
     * Excel 对纯 `\n` 的容忍度不如 `\r\n`（记事本老版本会把整个文件显示成一行）。
     */
    fun csv(
        collections: List<PlaybackExportCollection>,
        resolve: (MspText) -> String,
    ): String {
        val labels = Labels(resolve)
        val columns = buildColumns(
            // 只有一个集合时不写「列表」这一列：单集合导出（播放记录、导出某个列表）
            // 里那一列每一行都一样，纯噪音。
            withCollection = collections.size > 1,
            // 整列缺数据的列干脆不出现在表里（见 [buildColumns]）。
            withPosition = collections.any { collection ->
                collection.tracks.any { it.positionMs != null }
            },
            withPlayedAt = collections.any { collection ->
                collection.tracks.any { it.playedAtMs != null }
            },
        )

        val builder = StringBuilder()
        builder.append(columns.joinToString(CSV_SEPARATOR) { Csv.escape(resolve(it.header)) })
        builder.append(CRLF)
        collections.forEach { collection ->
            collection.tracks.forEachIndexed { index, track ->
                val row = Row(collection.name, index, track, labels)
                builder.append(
                    columns.joinToString(CSV_SEPARATOR) { Csv.escape(it.value(row)) },
                )
                builder.append(CRLF)
            }
        }
        return builder.toString()
    }

    /**
     * JSON 文本。
     *
     * 形状刻意「一层就是一层的意义」：顶层是**集合的数组**而不是单个集合，
     * 于是「导出全部播放列表」和「导出某一个」是同一个形状，
     * 接收方不需要为两种情况写两套解析。
     */
    fun json(
        collections: List<PlaybackExportCollection>,
        exportedAtMs: Long,
        resolve: (MspText) -> String,
    ): String {
        val labels = Labels(resolve)
        val root = JsonObject(
            linkedMapOf(
                "exportedAt" to JsonPrimitive(isoTime(exportedAtMs)),
                // 集合名是内容，跟用户的语言走；键名是形状，固定英文。
                "collections" to JsonArray(
                    collections.map { collection ->
                        JsonObject(
                            linkedMapOf(
                                "name" to JsonPrimitive(collection.name),
                                "tracks" to JsonArray(
                                    collection.tracks.map { track ->
                                        JsonObject(
                                            linkedMapOf(
                                                "title" to JsonPrimitive(track.title),
                                                // 缺失的字段写 `null` 而不是空串：
                                                // 「没有艺术家」和「艺术家是空字符串」
                                                // 在备份/脚本那边是两件事，写空串就分不开了。
                                                "artist" to track.artist.json(),
                                                "kind" to JsonPrimitive(labels.kind(track.kind)),
                                                "durationMs" to JsonPrimitive(track.durationMs),
                                                // 不属于这个集合的字段（比如播放列表里的
                                                // 播放位置）**不写 null，直接不出现**——
                                                // 一个大半是 null 的形状会让接收方以为
                                                // 这些字段有意义但没采到。
                                                "uri" to JsonPrimitive(track.uri),
                                                "mediaId" to JsonPrimitive(track.mediaId),
                                                "missing" to JsonPrimitive(track.missing),
                                            ) + positionFields(track),
                                        )
                                    },
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
        return JSON_FORMAT.encodeToString(JsonObject.serializer(), root)
    }

    /** 只在确实属于这个概念时才出现的字段。 */
    private fun positionFields(track: PlaybackExportTrack): Map<String, JsonPrimitive> {
        val fields = linkedMapOf<String, JsonPrimitive>()
        track.positionMs?.let { fields["positionMs"] = JsonPrimitive(it) }
        track.playedAtMs?.let { fields["playedAtMs"] = JsonPrimitive(it) }
        return fields
    }

    /**
     * 列的定义：**表头与取值成对出现**。
     *
     * 写成两串列表（一串表头、一行一串值）的话，加一列时漏改一处就会出现
     * 「标题那一列里装着时长」——表头全是中文，肉眼扫一遍看不出来，
     * 而导出的文件已经交到用户手上了。
     *
     * 名字不叫 `columns`：`[csv]` 里有一个同名局部变量装着它的结果，
     * 同名会让「到底是函数还是那个列表」在阅读时变成一道谜题。
     */
    private fun buildColumns(
        withCollection: Boolean,
        withPosition: Boolean,
        withPlayedAt: Boolean,
    ): List<Column> = buildList {
        if (withCollection) add(Column(MspText.Res(R.string.msp_export_column_list)) { it.collection })
        add(Column(MspText.Res(R.string.msp_export_column_index)) { (it.index + 1).toString() })
        add(Column(MspText.Res(R.string.msp_export_column_title)) { it.track.title })
        add(Column(MspText.Res(R.string.msp_export_column_artist)) { it.track.artist.orEmpty() })
        add(Column(MspText.Res(R.string.msp_export_column_kind)) { it.labels.kind(it.track.kind) })
        add(Column(MspText.Res(R.string.msp_export_column_duration)) {
            TimeFormat.clock(it.track.durationMs)
        })
        add(Column(MspText.Res(R.string.msp_export_column_duration_ms)) {
            it.track.durationMs.toString()
        })
        if (withPosition) {
            add(Column(MspText.Res(R.string.msp_export_column_position)) {
                it.track.positionMs?.let(TimeFormat::clock).orEmpty()
            })
            add(Column(MspText.Res(R.string.msp_export_column_position_ms)) {
                it.track.positionMs?.toString().orEmpty()
            })
        }
        if (withPlayedAt) {
            add(Column(MspText.Res(R.string.msp_export_column_played_at)) {
                it.track.playedAtMs?.let(TimeFormat::dateTime).orEmpty()
            })
        }
        add(Column(MspText.Res(R.string.msp_export_column_file_state)) {
            it.labels.fileState(it.track.missing)
        })
        add(Column(MspText.Res(R.string.msp_export_column_uri)) { it.track.uri })
        add(Column(MspText.Res(R.string.msp_export_column_media_id)) { it.track.mediaId })
    }

    // ── 下面都是实现细节 ────────────────────────────────────────────────

    /** 一行里能取到的全部东西。列的取值函数只认它。 */
    private class Row(
        val collection: String,
        val index: Int,
        val track: PlaybackExportTrack,
        val labels: Labels,
    )

    /** 一处把「资源文案」解析成字符串的地方，避免每个单元格都去查一次资源。 */
    private class Labels(private val resolve: (MspText) -> String) {
        fun kind(kind: MediaKind): String = resolve(
            when (kind) {
                MediaKind.AUDIO -> MspText.Res(R.string.msp_export_kind_audio)
                MediaKind.VIDEO -> MspText.Res(R.string.msp_export_kind_video)
                MediaKind.UNKNOWN -> MspText.Res(R.string.msp_export_kind_unknown)
            },
        )

        fun fileState(missing: Boolean): String = resolve(
            if (missing) {
                MspText.Res(R.string.msp_export_file_missing)
            } else {
                MspText.Res(R.string.msp_export_file_ok)
            },
        )
    }

    private class Column(val header: MspText, val value: (Row) -> String)

    private fun String?.json() = if (this.isNullOrBlank()) JsonNull else JsonPrimitive(this)

    private fun isoTime(ms: Long): String =
        SimpleDateFormat(ISO_PATTERN, Locale.US).format(Date(ms))

    /**
     * 洗掉文件系统不接受的字符。
     *
     * SAF 的 `CreateDocument` 拿到的名字**本来**会被 DocumentsUI 过滤一遍，
     * 但那是别人的实现细节：用户输入的列表名可以带 `/`、`:`、`*`（Windows 上
     * 这些字符连同步盘都存不下），而 `/` 在 Android 上会被当成路径分隔符，
     * 于是保存框里出现一个并不存在的目录。自己先洗一遍，交给谁都不会出事。
     */
    private fun sanitize(raw: String): String = raw
        .map { if (it in ILLEGAL_NAME_CHARS || it.code < 0x20) '_' else it }
        .joinToString("")
        .trim()
        .trim('.')
        .take(MAX_NAME_LENGTH)
        // 截断可能把一个 emoji 的代理对切成两半，留下一个孤立的高代理字符，
        // 它写进文件系统时会变成一个问号（或者直接被拒）。
        .trimEnd(Char::isHighSurrogate)

    private const val CSV_SEPARATOR = ","
    private const val CRLF = "\r\n"
    private const val MAX_NAME_LENGTH = 60
    private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"
    private const val ISO_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX"
    private const val ILLEGAL_NAME_CHARS = "\\/:*?\"<>|"

    private val JSON_FORMAT = Json { prettyPrint = true }
}

/**
 * CSV 字段的转义与还原（RFC 4180）。
 *
 * 规则只有三条，但它们全都不是可选的：
 *
 * - 含逗号的字段必须加引号（否则一行会多出一列，**整张表从此错位**）；
 * - 含引号的字段必须加引号并把引号写成两个（`"` → `""`）；
 * - 含换行的字段**必须**加引号，否则一条标题里的换行会把一行拆成两行。
 *   标题里带换行不是臆想：ID3 标签里换行很常见。
 *
 * 转义（[escape]）和还原（[parse]）刻意放在同一个对象里：它们是一对反函数，
 * 只改一边就会出现「我们写出来的文件自己读不回来」，而那种错在导出时完全看不出来。
 */
internal object Csv {
    fun escape(field: String): String {
        val needsQuotes = field.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        if (!needsQuotes) return field
        return '"' + field.replace("\"", "\"\"") + '"'
    }

    /**
     * 把整段 CSV 拆成「记录 × 字段」。
     *
     * 逐字符扫描而不是 `split(",")` / `split("\n")`：后者对上面的三条规则一条
     * 都处理不了，而且**不会报错**——标题里一个逗号就让整行多一列，
     * 之后每一列都串位（标题跑到路径列里），读出来的是一个「语法完全合法、
     * 内容全错」的播放列表。
     *
     * 两处刻意放宽（都是因为我们读的是**别人可能手改过**的文件）：
     * - 记录分隔符 `\r\n` 和 `\n` 都认（手改过的文件几乎都是 LF，而我们写的是 CRLF）；
     * - 不成对出现的引号当成普通字符，而不是抛异常（手改过的文件里很常见）。
     *
     * 末尾的空行会被丢掉：导出的文件以 CRLF 结尾，扫完必然剩下一条空记录。
     */
    fun parse(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var started = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i += 2
                }

                c == '"' -> {
                    quoted = !quoted
                    started = true
                    i++
                }

                !quoted && c == ',' -> {
                    fields.add(field.toString())
                    field.clear()
                    started = true
                    i++
                }

                !quoted && (c == '\r' || c == '\n') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    i++
                    // 一条「一个字符都没有」的记录是文件末尾那个换行，丢掉。
                    if (started || field.isNotEmpty() || fields.isNotEmpty()) {
                        fields.add(field.toString())
                        records.add(fields)
                    }
                    fields = ArrayList()
                    field.clear()
                    started = false
                }

                else -> {
                    field.append(c)
                    started = true
                    i++
                }
            }
        }
        if (started || field.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(field.toString())
            records.add(fields)
        }
        return records
    }
}
