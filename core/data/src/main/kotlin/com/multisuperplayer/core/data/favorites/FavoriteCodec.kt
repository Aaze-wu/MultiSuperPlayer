package com.multisuperplayer.core.data.favorites

import com.multisuperplayer.core.data.browser.BrowserSourceKind
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.PlaylistItem

/**
 * 收藏的编码格式：`一条记录一段，字段用 | 分隔`。
 *
 * ```text
 * file|mediaId|uri|标题|艺术家|时长ms|类型|收藏时间
 * folder|来源|收藏时间|段数|名字1|ref1|名字2|ref2|…
 * ```
 *
 * ## 为什么不是一行一条记录
 *
 * 文件夹的段落数是可变的（`Download/电影/喜剧` 是三段，再深一层就变四段）。
 * 「一行一条」要求每条记录的字段数固定，那就得把整条面包屑拼成一个字符串再存，
 * 于是又多一层转义（目录名里有 `|` 时）。这里直接用「段数」字段表达，
 * 任何一层目录名都可以是任意文本，只有一层转义。
 *
 * ## 为什么不用 JSON
 *
 * `org.json` 在 JVM 单测里返回的是桩（这个模块的 `testOptions` 是
 * `isReturnDefaultValues = true`），编解码就无法用单元测试钉住——而这正是
 * 「写入的格式和读出的格式悄悄对不上」最容易出问题的地方。
 * 这一点和 `PlaylistCodec` 是同一个理由。
 *
 * ## 解码永不抛异常
 *
 * 认不出来的记录返回 null 被跳过：少显示一条收藏是可接受的，
 * 收藏页因为一条坏数据整页崩掉不是。
 */
internal object FavoriteCodec {

    /** 记录分隔符（ASCII RS）。名字字段里出现它的概率可以忽略，而且它也会被转义。 */
    const val RECORD_SEPARATOR = '\u001E'

    private const val FIELD_SEPARATOR = '|'
    private const val ESCAPE = '\\'

    private const val ESCAPED_RECORD_SEPARATOR = 'e'
    private const val ESCAPED_NEWLINE = 'n'
    private const val ESCAPED_CARRIAGE_RETURN = 'R'

    private const val TAG_FILE = "file"
    private const val TAG_FOLDER = "folder"

    /** tag + mediaId/uri/title/artist/duration/kind/addedAt */
    private const val FILE_FIELDS = 8

    /** tag + 来源 + 收藏时间 + 段数，后面是成对的「名字 / ref」。 */
    private const val FOLDER_HEAD_FIELDS = 4

    fun encode(favorites: List<Favorite>): String =
        favorites.joinToString(RECORD_SEPARATOR.toString()) { favorite ->
            when (favorite) {
                is Favorite.File -> encodeFile(favorite)
                is Favorite.Folder -> encodeFolder(favorite)
            }
        }

    fun decode(raw: String?): List<Favorite> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(RECORD_SEPARATOR).mapNotNull { record ->
            when {
                record.isEmpty() -> null
                else -> decodeRecord(record)
            }
        }
    }

    private fun decodeRecord(record: String): Favorite? {
        val fields = splitFields(record)
        // 第一个字段是 tag。用 `when` 而不是 `if`：以后加一类收藏（比如「网络地址」）时，
        // 旧版本读到它会走 `else` 跳过，而不是把新格式当成文件收藏读出乱码。
        return when (fields.firstOrNull()) {
            TAG_FILE -> decodeFile(fields)
            TAG_FOLDER -> decodeFolder(fields)
            else -> null
        }
    }

    private fun encodeFile(favorite: Favorite.File): String = joinFields(
        listOf(
            TAG_FILE,
            favorite.item.mediaId,
            favorite.item.uri,
            favorite.item.title,
            favorite.item.artist.orEmpty(),
            favorite.item.durationMs.toString(),
            favorite.item.kind.name,
            favorite.addedAtMs.toString(),
        ),
    )

    private fun encodeFolder(favorite: Favorite.Folder): String {
        val fields = ArrayList<String>(FOLDER_HEAD_FIELDS + favorite.crumbs.size * 2)
        fields += TAG_FOLDER
        fields += favorite.kind.name
        fields += favorite.addedAtMs.toString()
        fields += favorite.crumbs.size.toString()
        favorite.crumbs.forEach { crumb ->
            fields += crumb.label
            fields += crumb.ref
        }
        return joinFields(fields)
    }

    private fun decodeFile(fields: List<String>): Favorite.File? {
        if (fields.size != FILE_FIELDS) return null
        val mediaId = fields[1]
        val uri = fields[2]
        // id 和 uri 是这一条唯一**必须有**的东西：没有 id 就无法去重、无法找同目录字幕；
        // 没有 uri 就永远播不了。标题为空只是一行没名字的记录，仍然可以显示。
        if (mediaId.isBlank() || uri.isBlank()) return null
        val addedAtMs = fields[7].toLongOrNull() ?: return null
        return Favorite.File(
            item = PlaylistItem(
                mediaId = mediaId,
                uri = uri,
                title = fields[3],
                artist = fields[4].takeIf { it.isNotBlank() },
                durationMs = fields[5].toLongOrNull() ?: 0L,
                kind = kindOf(fields[6]),
            ),
            addedAtMs = addedAtMs,
        )
    }

    private fun decodeFolder(fields: List<String>): Favorite.Folder? {
        if (fields.size < FOLDER_HEAD_FIELDS) return null
        val kind = sourceKindOf(fields[1]) ?: return null
        val addedAtMs = fields[2].toLongOrNull() ?: return null
        val count = fields[3].toIntOrNull() ?: return null
        // 段数必须和实际剩下的字段对得上：只按「剩下的除以 2」去读的话，
        // 一条被截断的记录会被读成一条少了几层的合法面包屑，
        // 打开它就落在错误的目录里（比读不出来更糟）。
        if (count <= 0 || fields.size != FOLDER_HEAD_FIELDS + count * 2) return null
        val crumbs = ArrayList<FavoriteCrumb>(count)
        for (index in 0 until count) {
            val label = fields[FOLDER_HEAD_FIELDS + index * 2]
            val ref = fields[FOLDER_HEAD_FIELDS + index * 2 + 1]
            if (ref.isBlank()) return null
            crumbs += FavoriteCrumb(label = label, ref = ref)
        }
        // 文件系统与 SAF 的树根 ref 一定非空；这里不校验「像不像一个路径」:
        // 路径的形状是系统的事，`ref` 在这一层按不透明句柄对待。
        return Favorite.Folder(kind = kind, crumbs = crumbs, addedAtMs = addedAtMs)
    }

    /** 认不出来的类型按 UNKNOWN：`MediaKind` 以后加值时旧记录仍然能读出来。 */
    private fun kindOf(raw: String): MediaKind =
        MediaKind.entries.firstOrNull { it.name == raw } ?: MediaKind.UNKNOWN

    /**
     * 认不出来的来源返回 null（整条跳过），**不**退化成文件系统。
     *
     * 退化的代价是往一个路径里塞一条 `content://…` 的 document uri（或者反过来），
     * 打开收藏时会得到一个「目录读不出来」的空页面，而用户看到的是一份好端端的收藏。
     * 认不出来的来源只可能来自更新版本写的文件，跳过它是唯一不会说谎的选择。
     */
    private fun sourceKindOf(raw: String): BrowserSourceKind? =
        BrowserSourceKind.entries.firstOrNull { it.name == raw }

    private fun joinFields(fields: List<String>): String =
        fields.joinToString(FIELD_SEPARATOR.toString()) { escape(it) }

    /** 按字段分隔符切分，同时还原转义。（与 `PlaylistCodec` 同一套规则。） */
    private fun splitFields(record: String): List<String> {
        val fields = ArrayList<String>(FILE_FIELDS)
        val current = StringBuilder()
        var index = 0
        while (index < record.length) {
            val char = record[index]
            when {
                char == ESCAPE && index + 1 < record.length -> {
                    // 未在表里的转义序列按字面量收下：以后加一个转义字符时，
                    // 旧版本读到新格式不会整条记录作废。
                    current.append(unescapeChar(record[index + 1]))
                    index += 2
                }

                char == FIELD_SEPARATOR -> {
                    fields += current.toString()
                    current.setLength(0)
                    index += 1
                }

                else -> {
                    current.append(char)
                    index += 1
                }
            }
        }
        fields += current.toString()
        return fields
    }

    private fun escape(value: String): String {
        if (value.none { it == ESCAPE || it == FIELD_SEPARATOR || it == RECORD_SEPARATOR || it == '\n' || it == '\r' }) {
            return value
        }
        val builder = StringBuilder(value.length + 4)
        for (char in value) {
            when (char) {
                ESCAPE, FIELD_SEPARATOR -> builder.append(ESCAPE).append(char)
                RECORD_SEPARATOR -> builder.append(ESCAPE).append(ESCAPED_RECORD_SEPARATOR)
                '\n' -> builder.append(ESCAPE).append(ESCAPED_NEWLINE)
                '\r' -> builder.append(ESCAPE).append(ESCAPED_CARRIAGE_RETURN)
                else -> builder.append(char)
            }
        }
        return builder.toString()
    }

    private fun unescapeChar(char: Char): Char = when (char) {
        ESCAPED_RECORD_SEPARATOR -> RECORD_SEPARATOR
        ESCAPED_NEWLINE -> '\n'
        ESCAPED_CARRIAGE_RETURN -> '\r'
        else -> char
    }
}
