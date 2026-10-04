package com.multisuperplayer.core.data.export

import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.data.playlist.PlaylistRules
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.PlaylistItem
import com.multisuperplayer.core.model.text.MspText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从文件里读回来的一个播放列表。
 *
 * [name] 已经过 [PlaylistRules.sanitizeName]；[items] 里每一条都至少有一个非空的
 * `mediaId`（没有 id 的条目在解析时就被丢掉了，理由见 [PlaybackImport]）。
 *
 * 里面可能有**空的**列表（文件里有、但一条可用条目都没有）——要不要跳过它、
 * 跳过几个，是编排（`PlaylistImport`）的决定，不在这里做：
 * 解析这一层的职责是「文件里写了什么」，不是「该拿它怎么办」。
 *
 * [itemsWithoutId] 是**被丢掉**的条数，必须带出去而不是只记个日志：
 * 「文件里 12 条导进来 0 条」和「文件里空空如也」在界面上如果不分开说，
 * 用户只会以为功能坏了（而真相是他的文件被拿去过滤过了）。
 */
data class ImportedPlaylist(
    val name: String,
    val items: List<PlaylistItem>,
    /** 这个列表里因为「没有媒体 ID」而被丢掉的条数。 */
    val itemsWithoutId: Int = 0,
)

/**
 * 读不了/不是我们的文件时抛这个。
 *
 * 带一句 [MspText] 而不是一个字符串：这一层住在 `core:data`，不知道用户选的是
 * 哪种语言，而「缺少『标题』列」这句话里那句「标题」还得跟着语言变
 * （所以它内部又是一个 [MspText.Res]，指向列名本身的资源）。
 */
class PlaybackImportException(val text: MspText) : Exception(text.toString())

/**
 * CSV 里认得出来的列。
 *
 * [labelId] 只用于报错：告诉用户缺的是哪一列时，得用**当前语言**说出那个列名，
 * 而列名本来就是导出时按语言写进文件的（见 [PlaybackExport] 的注释）。
 */
enum class CsvColumn(val labelId: Int) {
    List(R.string.msp_export_column_list),
    Title(R.string.msp_export_column_title),
    Artist(R.string.msp_export_column_artist),
    Kind(R.string.msp_export_column_kind),
    Uri(R.string.msp_export_column_uri),
    MediaId(R.string.msp_export_column_media_id),
    DurationMs(R.string.msp_export_column_duration_ms),
}

/**
 * 读 CSV 时要认出来的「写法」：列名，以及「类型」列里的取值。
 *
 * ## 为什么不是「按列的位置读」
 *
 * 我们**写出去**的 CSV 表头是本地化的（`标题` / `Title` / `標題`），
 * 而且列集合还会变（多集合导出时多一个「列表」列，播放记录多两列）。
 * 按位置读的话，一个用英文界面导出的文件在中文界面里导入会整张表串位——
 * 标题被当成艺术家、路径被当成媒体 id，**每一列都错，却一条都不报错**。
 *
 * ## 为什么要认三种语言，而不是只认当前语言
 *
 * 文件是「上一次导出」的产物，而那次导出可能是英文界面做的。用户的界面语言
 * 和文件的表头语言没有关系（换语言、换设备、别人给的文件），所以别名表里
 * 三种已发布语言的写法都要有。
 *
 * 别名表本身在 `strings.xml` 的 `string-array` 里（`translatable="false"`，
 * 一个数组里就是三种语言的写法），由 [of] 装进来——加了语言而忘了往数组里补，
 * 会被 [PlaybackImportTest] 的资源守卫用例抓住。
 */
class CsvAliases private constructor(
    private val columnNames: Map<CsvColumn, Set<String>>,
    private val kindNames: Map<MediaKind, Set<String>>,
) {

    /** 这一格是不是某个列名（首尾空白容忍，手改过的文件很常见）。 */
    fun columnOf(header: String): CsvColumn? {
        val value = header.trim()
        if (value.isEmpty()) return null
        columnNames.forEach { (column, names) -> if (value in names) return column }
        return null
    }

    /**
     * 「类型」列 / JSON 的 `kind` 字段里的值是什么意思。
     *
     * 认不出返回 null（调用方退回 [MediaKind.UNKNOWN]）：这个字段纯粹是显示用的，
     * 为一个认不出来的类型把整份文件判成坏的，代价远大于收益。
     * 除了三种语言的写法，也认**枚举名**（`AUDIO`/`audio`）：手写的文件里很自然。
     */
    fun kindOf(cell: String): MediaKind? {
        val value = cell.trim()
        if (value.isEmpty()) return null
        kindNames.forEach { (kind, names) -> if (value in names) return kind }
        return MediaKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

    companion object {
        /**
         * 用「取一个字符串」「取一个字符串数组」两个函数装出别名表。
         *
         * 传函数而不是 `Resources`：这样这一层不需要 `Context`，
         * 单测里可以用假的两张表构造出**完全一样的**对象（也能构造出
         * 故意错位的表来验证判断逻辑本身）。
         */
        fun of(items: (Int) -> List<String>): CsvAliases = CsvAliases(
            columnNames = COLUMN_ARRAYS.mapValues { (_, id) -> items(id).map(String::trim).toSet() },
            kindNames = KIND_ARRAYS.mapValues { (_, id) -> items(id).map(String::trim).toSet() },
        )

        /** 哪个数组是哪一列的写法。 */
        private val COLUMN_ARRAYS: Map<CsvColumn, Int> = mapOf(
            CsvColumn.List to R.array.msp_import_alias_list,
            CsvColumn.Title to R.array.msp_import_alias_title,
            CsvColumn.Artist to R.array.msp_import_alias_artist,
            CsvColumn.Kind to R.array.msp_import_alias_kind,
            CsvColumn.Uri to R.array.msp_import_alias_uri,
            CsvColumn.MediaId to R.array.msp_import_alias_media_id,
            CsvColumn.DurationMs to R.array.msp_import_alias_duration_ms,
        )

        /** 哪个数组是哪一种类型的写法。 */
        private val KIND_ARRAYS: Map<MediaKind, Int> = mapOf(
            MediaKind.AUDIO to R.array.msp_import_alias_kind_audio,
            MediaKind.VIDEO to R.array.msp_import_alias_kind_video,
            MediaKind.UNKNOWN to R.array.msp_import_alias_kind_unknown,
        )
    }
}

/**
 * 把导出的文件读回播放列表。
 *
 * 与 [PlaybackExport] 是一对反函数，**两种格式都认**：
 *
 * - JSON 是机器形状（字段名固定英文），读起来没有歧义；
 * - CSV 的表头和「类型」列是**本地化**的，所以得靠 [CsvAliases] 按名字认列，
 *   而且认三种语言（理由在那个类的注释里）。
 *
 * ## 为什么是纯函数
 *
 * 这一层不碰 `Context`、不碰 `Uri`、不碰文件系统：读文件在 `TextImportReader`，
 * 落盘在 `PlaylistStore`。这样「表头认不认得出」「缺列怎么办」「带引号的换行」
 * 这些真正容易写错的地方全都能在纯 JVM 单测里跑（`core:data` 的单测没有
 * Robolectric，构造不出 `Context`）。
 *
 * ## 两种格式都要能读回播放记录吗
 *
 * 不。播放记录（`PlaybackExportTrack.positionMs/playedAtMs`）的导出让用户
 * 「拿出去看」，导入只针对播放列表（见 `PlaylistImport`）。所以这里读到的
 * 位置/时间字段一律忽略，但**不会因此报错**：同一份文件两种格式的形状是一样的。
 *
 * ## 丢弃的条目
 *
 * `mediaId` 为空的条目直接丢掉：媒体 id 是这条记录的身份——去重、按当前媒体库
 * 解析最新元数据（`PlaylistItem.resolvedBy`）都靠它，没有它的条目既排不了重
 * 也认不出是谁。而 `uri` 可以为空（那样这一条会显示成「文件已不在」）。
 */
object PlaybackImport {

    /**
     * 从文件名推一个兜底列表名，用于「文件里没有『列表』列」的单集合导出。
     *
     * 顺着导出时的命名规则反着来：`<名字>-<时间戳>.csv` → `<名字>`。
     * 不去掉那个时间戳的话，导入后的列表会叫「播放列表-20261004-111548」——
     * 每一次导入都多一个几乎一样、只差时间戳的名字。
     *
     * 公开而不是 private：界面层要在**读文件之前**就算出这个名字，
     * 因为 SAF 返回的文件名只有那一次回调里有（之后去查 provider 有可能拿到
     * 不一样的结果，甚至拿不到）。它住在 `core:data` 而调用方在 `feature:library`，
     * 所以**不能是 `internal`**——模块作用域的可见性在这里等于 private，
     * 调用点会直接编译不过。
     *
     * @param default 洗完之后什么也不剩时用的名字（由界面层提供，要能翻译）。
     */
    fun fallbackNameOf(displayName: String?, default: String): String {
        val raw = displayName.orEmpty()
        val withoutExtension = raw.substringBeforeLast('.', raw)
        val withoutStamp = STAMP_TAIL.replace(withoutExtension, "")
        return PlaylistRules.sanitizeName(withoutStamp).ifEmpty { default }
    }

    /**
     * 解析整份文件。
     *
     * 格式靠**内容**判断（第一个非空白字符是 `{` 或 `[` 就是 JSON），而不是靠
     * 扩展名：SAF 给回来的名字并不可靠（用户可能把 `.json` 存成了 `.txt`，
     * 而 provider 也可能报一个不一样的名字），而文件内容骗不了人。
     *
     * @param fallbackName 文件里没有列表名时用的名字（见 [fallbackNameOf]）。
     * @throws PlaybackImportException 读不了、缺列、行数对不上。
     */
    fun decode(raw: String, fallbackName: String, aliases: CsvAliases): List<ImportedPlaylist> {
        // 读文件那一步已经剥过 BOM 了，这里再剥一次：纯解析这一层不能假设调用方
        // 剥过——一份带 BOM 的文本直接进到这里，第一个列名会变成 "\uFEFF标题"，
        // 于是整份文件报「缺少『标题』列」，看起来像文件坏了。
        val text = raw.removePrefix(BOM_STRING).trimStart()
        if (text.isEmpty()) return emptyList()
        return if (text.startsWith("{") || text.startsWith("[")) {
            decodeJson(text, fallbackName, aliases)
        } else {
            decodeCsv(text, fallbackName, aliases)
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun decodeJson(text: String, fallbackName: String, aliases: CsvAliases): List<ImportedPlaylist> {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            throw notOurs()
        } catch (error: IllegalArgumentException) {
            throw notOurs()
        }
        return collectionsOf(root).map { obj -> collectionOf(obj, fallbackName, aliases) }
    }

    /**
     * 取出「集合」那一层。
     *
     * 认三种形状，都是见过的：
     * - `{exportedAt, collections: [...]}`：我们导出的原样；
     * - `[...]`：用户拿 `jq` 之类取过一层，或者手工拼的；
     * - `[{collections: [...]}]`：被**恰好一层**数组包起来的上面那个
     *   （`jq -s .` 的结果）。只认单元素，理由见下面。
     */
    private fun collectionsOf(root: JsonElement): List<JsonObject> {
        val raw: List<JsonElement> = when (root) {
            is JsonObject -> (root["collections"] as? JsonArray)?.toList() ?: throw notOurs()
            is JsonArray -> {
                // 只在「恰好一个元素」时才往里钻一层。多元素的数组**故意不钻**：
                // `[volA, volB]` 那样钻进去等于把「一半的答案」当成全部提交，
                // 而外面看起来一切正常。
                val single = root.singleOrNull() as? JsonObject
                ((single?.get("collections") as? JsonArray)?.toList()) ?: root.toList()
            }

            else -> throw notOurs()
        }
        return raw.map { it as? JsonObject ?: throw notOurs() }
    }

    private fun collectionOf(
        obj: JsonObject,
        fallbackName: String,
        aliases: CsvAliases,
    ): ImportedPlaylist {
        val name = PlaylistRules.sanitizeName(obj["name"].asString()).ifEmpty { fallbackName }
        val tracks = (obj["tracks"] as? JsonArray).orEmpty()
        val items = tracks.mapNotNull { element ->
            (element as? JsonObject)?.let { trackOf(it, aliases) }
        }
        // 「没有 id 的」和「压根不是对象的」算在一起：对用户来说都是
        // 「这个列表在文件里有 N 条，但一条都用不上」，而两种原因都不需要他做什么
        // （文件已经坏了/被改过，他只能换一个文件）。
        return ImportedPlaylist(name = name, items = items, itemsWithoutId = tracks.size - items.size)
    }

    private fun trackOf(obj: JsonObject, aliases: CsvAliases): PlaylistItem? {
        val mediaId = obj["mediaId"].asString().trim()
        if (mediaId.isEmpty()) return null
        return PlaylistItem(
            mediaId = mediaId,
            uri = obj["uri"].asString().trim(),
            // 没有标题时用媒体 id 顶上：宁可显示一行看不懂的东西，
            // 也不要一行空白（空白看起来像「导入丢了内容」）。
            title = obj["title"].asString().trim().ifEmpty { mediaId },
            artist = obj["artist"].asString().trim().takeIf { it.isNotEmpty() },
            durationMs = obj["durationMs"].asLong() ?: 0L,
            kind = aliases.kindOf(obj["kind"].asString()) ?: MediaKind.UNKNOWN,
        )
    }

    // ------------------------------------------------------------------- CSV

    private fun decodeCsv(
        text: String,
        fallbackName: String,
        aliases: CsvAliases,
    ): List<ImportedPlaylist> {
        val records = Csv.parse(text)
        if (records.isEmpty()) return emptyList()

        val header = records.first()
        val columns = header.map { aliases.columnOf(it) }
        // 一个列名都不认识 → 不是我们的文件。这一条必须和「缺某一列」分开：
        // 两种原因，两种改法（换一个文件 vs 这个文件被改坏了）。
        if (columns.all { it == null }) throw notOurs()

        val titleIndex = columns.indexOf(CsvColumn.Title)
        val uriIndex = columns.indexOf(CsvColumn.Uri)
        val mediaIdIndex = columns.indexOf(CsvColumn.MediaId)
        if (titleIndex < 0) throw missing(CsvColumn.Title)
        if (uriIndex < 0) throw missing(CsvColumn.Uri)
        if (mediaIdIndex < 0) throw missing(CsvColumn.MediaId)

        // 可选列：老的/手写的文件里可能没有。没有就是「不知道」，不是错误。
        val listIndex = columns.indexOf(CsvColumn.List)
        val artistIndex = columns.indexOf(CsvColumn.Artist)
        val kindIndex = columns.indexOf(CsvColumn.Kind)
        val durationIndex = columns.indexOf(CsvColumn.DurationMs)

        val groups = LinkedHashMap<String, MutableList<PlaylistItem>>()
        // 名字 → 这个列表里因为「没有媒体 ID」被丢掉的条数。
        val withoutId = HashMap<String, Int>()
        records.drop(1).forEachIndexed { offset, fields ->
            // 列数对不上就直接失败，而不是「跳过这一行」：行数对不上说明这张表
            // 已经被改坏（少了/多了分隔符），继续读下去会把**串位**的内容当成
            // 真的数据写进播放列表。报出行号，用户至少能去看那一行。
            if (fields.size != header.size) {
                throw PlaybackImportException(
                    MspText.Res(R.string.msp_import_error_broken_row, offset + 2),
                )
            }
            // 名字先算出来：没有媒体 id 的那些行也要记到**它所属的列表**名下，
            // 否则「这个列表 12 条全都不能用」会连一句提示都没有。
            val name = if (listIndex >= 0) {
                PlaylistRules.sanitizeName(fields[listIndex]).ifEmpty { fallbackName }
            } else {
                fallbackName
            }
            val bucket = groups.getOrPut(name) { ArrayList() }
            val mediaId = fields[mediaIdIndex].trim()
            // 没有媒体 id 的行丢掉（理由见类注释）。整份都丢光 = 一个空列表，
            // 由编排决定要不要说话。
            if (mediaId.isEmpty()) {
                withoutId[name] = (withoutId[name] ?: 0) + 1
                return@forEachIndexed
            }
            val item = PlaylistItem(
                mediaId = mediaId,
                uri = fields[uriIndex].trim(),
                title = fields[titleIndex].trim().ifEmpty { mediaId },
                artist = if (artistIndex >= 0) fields[artistIndex].trim().takeIf { it.isNotEmpty() } else null,
                durationMs = if (durationIndex >= 0) fields[durationIndex].trim().toLongOrNull() ?: 0L else 0L,
                kind = if (kindIndex >= 0) aliases.kindOf(fields[kindIndex]) ?: MediaKind.UNKNOWN else MediaKind.UNKNOWN,
            )
            bucket.add(item)
        }
        return groups.map { (name, items) ->
            ImportedPlaylist(
                name = name,
                items = items,
                itemsWithoutId = withoutId[name] ?: 0,
            )
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun notOurs(): PlaybackImportException =
        PlaybackImportException(MspText.Res(R.string.msp_import_error_unrecognized))

    private fun missing(column: CsvColumn): PlaybackImportException = PlaybackImportException(
        MspText.Res(R.string.msp_import_error_missing_column, MspText.Res(column.labelId)),
    )

    /**
     * 取一个 JSON 字段的文本。
     *
     * **必须先判 [JsonNull]**：`JsonNull` 是 `JsonPrimitive` 的子类，而它的
     * `content` 是字符串 `"null"`。漏掉这一条的话，「没有艺术家」会被读成
     * 一个叫 null 的艺术家。
     */
    private fun JsonElement?.asString(): String = when (this) {
        null, is JsonNull -> ""
        is JsonPrimitive -> content
        else -> ""
    }

    /** 取一个 JSON 字段的数字。容忍数字写成字符串（手写的文件里常见）。 */
    private fun JsonElement?.asLong(): Long? = when (this) {
        null, is JsonNull -> null
        is JsonPrimitive -> content.toLongOrNull()
        else -> null
    }

    /** 文件名尾巴上的「-时间戳」，与 `PlaybackExport.suggestedFileName` 是对应的。 */
    private val STAMP_TAIL = Regex("""-\d{8}-\d{6}$""")

    /** U+FEFF。字符串形态是为了配合 `removePrefix`。 */
    private const val BOM_STRING = "\uFEFF"
}
