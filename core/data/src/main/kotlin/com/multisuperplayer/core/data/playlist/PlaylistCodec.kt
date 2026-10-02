package com.multisuperplayer.core.data.playlist

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.Playlist
import com.multisuperplayer.core.model.PlaylistItem

/**
 * 播放列表的编码格式：`一条记录一行，字段用 | 分隔`。
 *
 * ```text
 * id|名字|创建时间
 * mediaId|uri|标题|艺术家|时长ms|类型
 * mediaId|uri|标题|艺术家|时长ms|类型
 * ```
 *
 * ## 为什么不用 [com.multisuperplayer.core.data.storage.FieldTextCodec]
 *
 * 它的转义规则是「反斜杠 + 原字符」（`|` → `\|`），对**控制字符**不成立：
 * 把记录分隔符转义成 `\` + 分隔符本身，那个分隔符就**还是原样留在字符串里**，
 * 于是按分隔符切分时又把它切开了——转义等于没做。
 *
 * 所以这里用显式的字符映射表，把控制字符换成**可打印的替代字符**
 * （记录分隔符 → `\e`、换行 → `\n`、回车 → `\R`）。顺带的好处是存进
 * DataStore 的字符串是单行可打印的，翻日志时能直接看懂。
 *
 * ## 解码永不抛异常
 *
 * 拿不准就返回 null，由调用方决定怎么办（现在的策略是：跳过这一条，
 * 其他播放列表照常显示）。存储坏掉时「少显示一个播放列表」是可接受的，
 * 「播放列表页面直接崩」不是。
 */
internal object PlaylistCodec {

    /** 记录分隔符（ASCII RS）。文件名字段里出现它的概率可以忽略，而且它也会被转义。 */
    const val RECORD_SEPARATOR = '\u001E'

    private const val FIELD_SEPARATOR = '|'
    private const val ESCAPE = '\\'

    private const val ESCAPED_RECORD_SEPARATOR = 'e'
    private const val ESCAPED_NEWLINE = 'n'
    private const val ESCAPED_CARRIAGE_RETURN = 'R'

    private const val HEADER_FIELDS = 3
    private const val ITEM_FIELDS = 6

    fun encode(playlist: Playlist): String {
        val records = ArrayList<String>(playlist.items.size + 1)
        records += joinFields(listOf(playlist.id, playlist.name, playlist.createdAtMs.toString()))
        playlist.items.forEach { item ->
            records += joinFields(
                listOf(
                    item.mediaId,
                    item.uri,
                    item.title,
                    item.artist.orEmpty(),
                    item.durationMs.toString(),
                    item.kind.name,
                ),
            )
        }
        return records.joinToString(RECORD_SEPARATOR.toString())
    }

    fun decode(raw: String?): Playlist? {
        if (raw.isNullOrEmpty()) return null

        val records = raw.split(RECORD_SEPARATOR)
        val header = splitFields(records.first())
        // 字段数量不对 = 格式对不上，不是「字段是空的」。缺席的字段会变成
        // 一个空标题、一条永远播不了的记录，而用户只会看到一行什么都没有的条目。
        if (header.size != HEADER_FIELDS) return null

        val id = header[0]
        if (id.isBlank()) return null
        val createdAtMs = header[2].toLongOrNull() ?: return null

        val items = records.drop(1).mapNotNull { record ->
            val fields = splitFields(record)
            if (fields.size != ITEM_FIELDS) return@mapNotNull null
            val mediaId = fields[0]
            val uri = fields[1]
            if (mediaId.isBlank() || uri.isBlank()) return@mapNotNull null
            PlaylistItem(
                mediaId = mediaId,
                uri = uri,
                title = fields[2],
                artist = fields[3].takeIf { it.isNotBlank() },
                durationMs = fields[4].toLongOrNull() ?: 0L,
                kind = kindOf(fields[5]),
            )
        }

        return Playlist(id = id, name = header[1], createdAtMs = createdAtMs, items = items)
    }

    /** 认不出来的类型按 UNKNOWN：`MediaKind` 以后加值时，旧记录仍然能读出来。 */
    private fun kindOf(raw: String): MediaKind =
        MediaKind.entries.firstOrNull { it.name == raw } ?: MediaKind.UNKNOWN

    private fun joinFields(fields: List<String>): String =
        fields.joinToString(FIELD_SEPARATOR.toString()) { escape(it) }

    /** 按字段分隔符切分，同时还原转义。 */
    private fun splitFields(record: String): List<String> {
        val fields = ArrayList<String>(ITEM_FIELDS)
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
