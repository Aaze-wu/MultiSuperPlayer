package com.multisuperplayer.core.model

/**
 * 内置文件浏览器里的一个条目：目录，或者文件。
 *
 * ## 为什么不是直接用 [MediaEntry]
 *
 * 文件浏览器要显示的东西比媒体条目**多**也**少**：
 *
 * - **多**：目录本身（`MediaEntry` 表达不了「这是一个可以进去的文件夹」），
 *   以及 `.lrc` / `.txt` 这类`不是媒体但用户要选它`的文件（选字幕文件的场景）。
 * - **少**：不需要 artist / album / 时长 / 封面——那些要额外查询，而文件浏览器
 *   每进一层目录都要列一次，不该为每一行付一次元数据查询。
 *
 * 所以这一层刻意只保留「列目录时顺手就能拿到」的字段。
 *
 * ## [ref] 的含义随来源变化
 *
 * 这是本类唯一需要小心的地方：[ref] 是**不透明句柄**，怎么解释它取决于当前
 * 浏览的 [BrowserSourceKind]`（见 `core:data` 的 `BrowserSourceKind`）：
 *
 * | 来源 | 目录的 ref | 文件的 ref |
 * |---|---|---|
 * | 文件系统 | 绝对路径 | 绝对路径 |
 * | SAF | 目录的 document uri | 文件的 document uri |
 * | 系统媒体库 | 相对路径（`Movies/`） | `content://media/...` |
 *
 * 之所以敢让它随来源变化，是因为**只有对应的那一个 source 会解释它**：
 * 界面只负责把 `ref` 原样传回同一个 source，从不去解析它。一旦让界面按
 * 「看起来像路径」去猜来源，三种来源就会互相喂错东西。
 *
 * 对文件系统与 SAF 来源，文件与目录的 ref **可以直接交给播放器**（`file://` 与
 * document uri 都是 Media3 认的）；媒体库来源的文件 ref 也是 content uri。
 */
data class BrowserEntry(
    /** 不透明句柄：目录用来进入，文件用来打开。含义见类注释。 */
    val ref: String,
    /** 展示名（含后缀）。目录名不带结尾斜杠。 */
    val name: String,
    val isDirectory: Boolean,
    /** provider / 文件系统给不出大小时是 0。目录恒为 0。 */
    val sizeBytes: Long = 0L,
    /** 修改时间，毫秒。给不出时是 0。 */
    val lastModifiedMs: Long = 0L,
    /**
     * 媒体大类；`null` 表示**这不是媒体文件**。
     *
     * 和 [MediaKind.UNKNOWN] 的区别是必须保留的：`UNKNOWN` 是「可能是媒体，
     * 后缀/MIME 认不出，交给内核去探测」（`.mka` 这类冷门容器就在这一档），
     * 而 `null` 是「明确不是媒体」（`.lrc`、`.txt`、`.zip`）。
     *
     * 两者在界面上的待遇**不同**：`UNKNOWN` 可以点开播放，`null` 点了只会失败。
     * 合并成一个值就会让「点了没反应」和「点了说播不了」退化成同一种表现。
     */
    val kind: MediaKind? = null,
    /** provider 上报的 MIME；`null` 与 `application/octet-stream` 都很常见。 */
    val mimeType: String? = null,
) {

    val isFile: Boolean get() = !isDirectory

    /**
     * 能不能交给播放器。
     *
     * 目录不能；明确不是媒体的文件也不能。判据用 [kind] 而不是 [mimeType]：
     * MIME 在 SAF / 媒体库两条路上都可能是 `null` 或 `octet-stream`，
     * 那是「provider 没说」而不是「不能播」。
     */
    val playable: Boolean get() = isFile && kind != null

    /** 全小写的后缀，不含点；没有后缀时是空串。 */
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    /** 以 `.` 开头（文件管理器里默认不显示）。 */
    val hidden: Boolean get() = name.startsWith('.')

    /**
     * 拿去当标题的字符串：去掉后缀。
     *
     * 兜底那条**必须有**：`".nomedia"` 这种「整个名字就是一个后缀」的文件，
     * 去完后缀是空串，标题就变成一行空白（列表里看起来像一行坏数据）。
     * 判据不能用「`name` 里有没有点」，要用「去完之后还剩不剩东西」。
     */
    val displayTitle: String
        get() {
            val ext = extension
            if (ext.isEmpty()) return name
            return name.dropLast(ext.length + 1).ifEmpty { name }
        }

    /**
     * 变成可以交给播放器的媒体条目；不可播（目录 / 明确不是媒体）时返回 `null`。
     *
     * ## id 为什么加 `file:` 前缀
     *
     * `MediaEntry.id` 是播放进度、最近播放这些**持久化记录**的主键。媒体库里的 id
     * 是 MediaStore 的数字 id 或 `saf:` 开头的字符串，而这里的 id 是路径 / document uri
     * ——一条恰好叫 `saf:` 开头的路径（理论上存在）会和媒体库的记录撞上。
     * 加个前缀就等于把「这条记录来自文件浏览器」写进主键，代价是两个字符。
     *
     * ## `relativePath` 故意留空
     *
     * 它唯一的用途是媒体库的「按文件夹分组」和 `MediaStore.Files` 的字幕查找。
     * 文件浏览器的条目**不进媒体库**，而它的相对路径要靠「减去卷根」算出来
     * （`BrowserEntry` 自己不知道自己在哪个卷上），算错了反而会把分组带偏。
     * 需要目录时直接从 [ref] 上取最后一段——那才是它真实的样子。
     */
    fun toMediaEntry(): MediaEntry? {
        if (isDirectory) return null
        val mediaKind = kind ?: return null
        return MediaEntry(
            id = "file:$ref",
            uri = ref,
            title = displayTitle,
            kind = mediaKind,
            source = MediaSource.FILE_SYSTEM,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            displayName = name,
            dateAddedSeconds = if (lastModifiedMs > 0L) lastModifiedMs / 1000L else 0L,
        )
    }
}
