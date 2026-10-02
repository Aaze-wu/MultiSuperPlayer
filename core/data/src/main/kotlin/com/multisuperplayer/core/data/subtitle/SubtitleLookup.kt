package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaSource
import java.io.File

/**
 * 「这条媒体的外挂字幕该去哪儿找」。
 *
 * ## 为什么把这个问题单独抽出来
 *
 * 三条来源问的是三个**不同的地方**，用错一条不会报错，只会安静地说「没有字幕」：
 *
 * | 来源 | 去哪儿找 | 凭什么 |
 * |---|---|---|
 * | [MediaSource.MEDIA_STORE] | `MediaStore.Files` 的 `RELATIVE_PATH` | 有索引 |
 * | [MediaSource.SAF_TREE] | 媒体 document uri 的兄弟目录 | 问 provider |
 * | [MediaSource.FILE_SYSTEM] | 绝对路径的上一级目录 | 直接列目录 |
 *
 * 这是一个**纯函数**：给一条 [MediaEntry]，回答一个位置。放成纯函数的原因不是美观，
 * 而是这条判断曾经出过一次只有真机能发现的 bug——文件浏览器打开的条目落在了
 * MediaStore 那一支，于是「代码写完、编译过、九百多个测试全绿，字幕就是不出现」。
 * 现在它可以被单测直接钉住。
 *
 * ## 为什么不让 [MediaSource.FILE_SYSTEM] 也走 MediaStore
 *
 * 因为内核文件浏览器存在的全部意义就是「MediaStore 看不见的地方」：`Download/` 根、
 * 内部存储根、带 `.nomedia` 的目录。这些地方在系统媒体库里**一条记录都没有**，
 * 拿相对路径去查只会得到 0 行，然后被翻译成「这个文件夹里没有字幕」——
 * 而真相是「我们根本没查过那个文件夹」。见 [FileSystemSubtitleLocator]。
 */
internal sealed interface SubtitleLookup {

    /**
     * 日志里指位置用的字符串。
     *
     * [Unavailable] 是空串：那条分支在用到它之前就已经返回了，
     * 留一个空串而不是 `null`，是因为日志拼字符串时多一次 `?:` 判断并不能
     * 换来任何信息。
     */
    val where: String

    /** 查 `MediaStore.Files` 的 `RELATIVE_PATH`（形如 `Movies/`）。 */
    data class MediaStoreDirectory(val relativePath: String) : SubtitleLookup {
        override val where: String get() = relativePath
    }

    /** 问 SAF provider 要媒体的兄弟目录。 */
    data class SafDocument(val uri: String) : SubtitleLookup {
        override val where: String get() = uri
    }

    /** 直接列这个文件系统目录。 */
    data class LocalDirectory(val path: String) : SubtitleLookup {
        override val where: String get() = path
    }

    /** 推不出位置。界面据此说「无法确定这个文件所在的文件夹」。 */
    data object Unavailable : SubtitleLookup {
        override val where: String get() = ""
    }
}

/**
 * 这条媒体该去哪儿找字幕。
 *
 * `when` **刻意不写 `else`**：`MediaSource` 以后每加一种来源，编译器都会在这里
 * 停下要求表态。这正是上面那张表要防的事——新来源悄悄落进「查 MediaStore」
 * 那一支，和当年那个 bug 是同一种形状。
 */
internal fun subtitleLookupOf(entry: MediaEntry): SubtitleLookup = when (entry.source) {
    MediaSource.SAF_TREE -> SubtitleLookup.SafDocument(entry.uri)

    MediaSource.FILE_SYSTEM ->
        localDirectoryOf(entry.uri)
            ?.let { SubtitleLookup.LocalDirectory(it) }
            ?: SubtitleLookup.Unavailable

    // 媒体库条目（以及网络 / 分享进来的条目）只有相对路径可用：
    // 前者的 uri 是 `content://media/...`，推不出任何目录；后两者根本没有本地目录。
    MediaSource.MEDIA_STORE ->
        entry.relativePath
            ?.takeIf { it.isNotBlank() }
            ?.let { SubtitleLookup.MediaStoreDirectory(it) }
            ?: SubtitleLookup.Unavailable

    // 这两类**刻意不去查媒体库**，即使 `relativePath` 恰好有值：
    // - 网络条目（m3u8 之类）根本没有本地文件夹；
    // - 分享进来的条目只有别人给的 content uri，它未必在媒体库索引里，
    //   而按相对路径查回来的是**那个目录下所有应用的文件**——同名不同源时
    //   自动挂上的就是别人家片子的字幕。
    MediaSource.REMOTE, MediaSource.SHARED -> SubtitleLookup.Unavailable
}

/**
 * 从一条**本地文件**的 uri / 路径里取出它所在的目录。推不出来时返回 `null`。
 *
 * 只认绝对路径（可带 `file://` 前缀）。**推不出来就老实返回 `null`**，
 * 绝不硬凑一个值：传 `""` 给 `File("")` 会去列当前工作目录（`/`），
 * 一个陌生目录里的 `xxx.srt` 会被当成这条片子的候选——用户看到的将是
 * 「自动挂上了一条完全不相干的字幕」，比「没找到」糟得多。
 */
internal fun localDirectoryOf(mediaUri: String): String? {
    val path = mediaUri.removePrefix(FILE_SCHEME)
    // 去掉 `file://` 之后还带 `://` 的，是别的 scheme（`content://`、`http://`），
    // 那不是路径，任何解释都是猜。
    if (path.isEmpty() || path.contains(SCHEME_SEPARATOR)) return null
    // `File("movie.mp4").parent` 是 `null`：没有上一级就是没有，不补成 `"."`。
    return File(path).parent?.takeIf { it.isNotBlank() }
}

private const val FILE_SCHEME = "file://"
private const val SCHEME_SEPARATOR = "://"
