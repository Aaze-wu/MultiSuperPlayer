package com.multisuperplayer.core.data.library

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.SafMediaIds

/**
 * SAF 扫描里的「纯规则」：文件类型判定、相对路径还原、去重键、遍历上限。
 *
 * ## 为什么又抽一个纯规则对象
 *
 * 和 [MediaStoreScanRules] 同一个理由，但这里更极端：SAF 的扫描全程要经过
 * `DocumentFile` / `DocumentsContract`，而这两个类在 JVM 单测里**连构造都做不到**
 * （`unitTests.isReturnDefaultValues = true` 只让 `android.*` 的方法返回 0/null，
 * 静态工厂仍然会走真实代码）。也就是说「扫描器里写的任何判断，一行都测不到」。
 *
 * 而这里的判断恰恰都是**错了从界面上看不出来**的那类：
 *
 * - 类型判定写漏一个后缀 ⇒ 那种格式的文件**永远不会出现在浏览页里**（不报错、不提示，
 *   用户只会认为「这个播放器不支持我的文件」）；
 * - 相对路径还原错了 ⇒ 字幕自动关联找不到同名字幕，而且表现得像「这个文件没有字幕」；
 * - 去重键错了 ⇒ 同一个文件在媒体库里出现两次。
 *
 * 所以凡是能脱离 Android 的判断，全部放这里，用普通 JVM 单测锁住。
 */
internal object SafScanRules {

    // --------------------------------------------------------------- 遍历上限

    /**
     * 递归深度上限。
     *
     * SAF 的目录树由别的应用（DocumentsProvider）提供，深度不归我们管。一个每次
     * 只返回一个子目录、但路径一直变长的 provider 足以让朴素递归栈溢出——而栈溢出
     * 在扫描线程里会直接杀掉进程，用户看到的是「点了添加文件夹，应用闪退」。
     * 8 层已经超过任何真实的音乐/视频目录结构。
     */
    const val MAX_DEPTH = 8

    /**
     * 单棵树最多收多少条。
     *
     * 用户完全可以把 `primary:` 根目录整棵授权给我们（系统选择器允许），
     * 那就是整个内置存储。不设上限的话，一次扫描要读几万个 document，
     * 每个都要跨进程 `ContentResolver` 查询，表现为「添加文件夹之后界面卡死几分钟」。
     */
    const val MAX_ENTRIES = 20_000

    // --------------------------------------------------------------- 类型判定

    /**
     * 判定一个 SAF 条目的媒体大类；返回 `null` 表示「这不是媒体文件，别收」。
     *
     * ## 为什么不是「只收白名单里的后缀」
     *
     * 这个项目在 v0.4 花了一整版接入 FFmpeg 软件解码，卖点就是「什么格式都能播」。
     * 而**白名单恰好会把卖点本身挡在门外**：越冷门的格式越不可能出现在任何一张
     * 手写清单里，于是用户拖进去的 `.mka` / `.ts` / `.ape` 会**静默地不出现在浏览页**。
     * 「文件不见了」比「文件打不开」难排查得多——后者至少有报错。
     *
     * 所以这里反过来做：**只挡掉明确已知不是媒体的类型**（字幕、图片、文档、压缩包、
     * 歌词、日志…），其余一律收下，判不出大类的记为 [MediaKind.UNKNOWN]
     * （`MediaEntry.kind` 的注释里本来就写着「播放时由内核自行探测」）。
     *
     * 代价是目录里一个不认识的后缀会出现在列表里、点了才失败。这个代价是有界的、
     * 可解释的；而静默漏文件是没有边界的。
     */
    fun kindOf(displayName: String?, mimeType: String?): MediaKind? {
        val extension = extensionOf(displayName)
        // 1. 后缀是明确不是媒体的东西 —— 先挡掉。
        //    放在 MIME 之前判断，是因为「.lrc 被 provider 报成 audio/x-lyric」
        //    这种事真的会发生，而歌词文件混进媒体库比漏一个文件更碍事。
        if (extension.isNotEmpty() && extension in NON_MEDIA_EXTENSIONS) return null

        // 2. MIME 能说话时信 MIME。`;` 后面是字符集之类的参数，去掉再比。
        val mime = mimeType?.substringBefore(';')?.trim().orEmpty()
        if (mime.startsWith("audio/", ignoreCase = true)) return MediaKind.AUDIO
        if (mime.startsWith("video/", ignoreCase = true)) return MediaKind.VIDEO

        // 3. 后缀表兜底。
        //    `.mkv` / `.mka` 必须是硬编码的：provider 把它们报成
        //    `application/octet-stream` 或 `video/x-matroska` 都不一定，
        //    而这正是本应用最常播的容器。
        if (extension.isNotEmpty()) {
            if (extension in VIDEO_EXTENSIONS) return MediaKind.VIDEO
            if (extension in AUDIO_EXTENSIONS) return MediaKind.AUDIO
        }

        // 4. 什么都没有：可能是没后缀的媒体（`VID_20240101` 这种），也可能不是。
        //    收起它，让内核去探测——被判成「不是媒体」的那个分支在上面。
        return MediaKind.UNKNOWN
    }

    /** 小写化的后缀，不含点；没有后缀时是空串。 */
    fun extensionOf(displayName: String?): String =
        displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()

    // --------------------------------------------------------- docId ↔ 路径

    /**
     * documentId 里的卷名（`primary:Music/a.mp3` → `primary`；SD 卡是 `ABCD-1234`）。
     *
     * 没有冒号时返回空串：那说明这个 provider 用的不是 ExternalStorageProvider，
     * 它的 docId 不对应任何真实路径，下游的相对路径就只能是 null。
     */
    fun volumeOf(documentId: String): String = documentId.substringBefore(':', "")

    /**
     * 相对**卷根**的路径，**带结尾斜杠**，好和 MediaStore 的 `RELATIVE_PATH` 直接比较。
     *
     * `primary:Music/Album/a.mp3` → `Music/Album/`
     *
     * 两个刻意的选择：
     * - **去掉卷名前缀**：MediaStore 的 `RELATIVE_PATH` 不含卷名，不去掉就永远比不上，
     *   于是同一首歌在媒体库里出现两次（一次来自 MediaStore、一次来自 SAF）。
     *   跨卷误判需要「两个卷上有完全同路径 + 同名 + 同大小」的文件才会发生，
     *   所以去重键里还带着大小（见 [identityKey]）。
     * - 根目录下的文件返回 `""` 而不是 `null`：MediaStore 对根目录下的文件也返回空串。
     *   两者都表示「就在卷根」，返回 null 会让它在去重时和谁都配不上。
     */
    fun relativePathOf(documentId: String): String? {
        val separator = documentId.indexOf(':')
        if (separator < 0) return null
        val path = documentId.substring(separator + 1)
        val lastSlash = path.lastIndexOf('/')
        // 文件直接在卷根（`primary:a.mp3`）：目录部分是空串。
        if (lastSlash < 0) return ""
        return path.substring(0, lastSlash + 1)
    }

    /** docId 里的文件名部分。`DocumentFile.getName()` 拿不到时的兜底。 */
    fun fileNameOf(documentId: String): String {
        val separator = documentId.indexOf(':')
        val path = if (separator < 0) documentId else documentId.substring(separator + 1)
        return path.substringAfterLast('/')
    }

    /**
     * 某个文档所在**目录**的 documentId；算不出来时返回 null。
     *
     * `primary:Movies/a.mkv` → `primary:Movies`
     * `primary:a.mkv`       → `primary:`（卷根，卷根下的文件，它的目录就是卷根）
     * `1234-5678:`          → `1234-5678:`
     * 没有冒号（非 ExternalStorageProvider）→ null
     *
     * 用途是找**同目录的外挂字幕**：MediaStore 那条路靠 `RELATIVE_PATH`，
     * 但 SAF 目录里的文件 MediaStore 根本看不见（云盘、应用私有目录、
     * SD 卡上的受限目录都是这样），只能拿 documentId 拼出兄弟目录的 children uri 去问。
     */
    fun parentDocumentIdOf(documentId: String): String? {
        val separator = documentId.indexOf(':')
        if (separator < 0) return null
        val volume = documentId.substring(0, separator)
        val path = documentId.substring(separator + 1)
        val lastSlash = path.lastIndexOf('/')
        val parentPath = if (lastSlash < 0) "" else path.substring(0, lastSlash)
        return "$volume:$parentPath"
    }

    /**
     * SAF 条目的稳定 id。
     *
     * 用 `documentId` 而不是「树 uri + 相对路径」，是因为 `ExternalStorageProvider`
     * 的 documentId 本来就是**卷内绝对**的：把 `primary:` 和 `primary:Music`
     * 两棵树都授权进来，同一个文件在两棵树里的 documentId 完全相同，于是
     * 「重复授权」不会产生两条 id 不同的记录。
     *
     * 前缀 `saf:` 和 [MediaStoreScanner] 用的 `audio:` / `video:` 一样，是
     * 「来源 + 本来源内的键」的写法（`MediaEntry.id` 的注释里解释过为什么这么定），
     * 因此续播位置、播放列表里存的 key 天然不会和 MediaStore 条目撞车。
     */
    fun safIdOf(documentId: String): String = SafMediaIds.idOf(documentId)

    /**
     * 见 [safIdOf]。
     *
     * 值本身住在 [SafMediaIds.PREFIX]：读 id 的一侧在 `core:model`
     * （`PlaylistItem.sourceOf`），它不能反过来依赖 `core:data`，所以那个模块
     * 才是这个常量的归属地；这里保留同名别名的原因只是不让 `core:data` 里的
     * 调用点来回跨模块引用。
     */
    const val SAF_ID_PREFIX = SafMediaIds.PREFIX

    // --------------------------------------------------------------- 去重

    /**
     * 同一个文件的身份键；`null` 表示「这个条目不足以判定身份，别参与去重」。
     *
     * 用「相对路径 + 文件名 + 大小」而不是 uri 或 id：MediaStore 给的是
     * `content://media/external/audio/media/123`，SAF 给的是
     * `content://com.android.externalstorage.documents/tree/.../document/primary%3AMusic%2Fa.mp3`，
     * **两者没有任何共同字段**，只有「在同一个卷的同一个位置、同样大小的同名文件」
     * 才是同一个文件这件事是双方都能算出来的。
     *
     * 大小参与比较是为了把冲突概率压到可忽略：跨卷的同路径同名文件几乎必然大小不同。
     * 代价是**文件被改动过大小之后会认为是两个文件**——这在「先扫 MediaStore、
     * 后授权 SAF 目录」的场景里不会发生（同一时刻的同一个文件）。
     *
     * 没有 `relativePath` 的条目（网络、分享进来的）返回 null，一律保留。
     */
    fun identityKey(
        relativePath: String?,
        displayName: String?,
        sizeBytes: Long,
    ): String? {
        val name = displayName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val path = relativePath?.trim()?.lowercase() ?: return null
        // 大小 0 的条目（provider 不给大小）不参与去重：用 0 当「一样大」会把
        // 同一目录下所有读不到大小的文件判成同一个。
        if (sizeBytes <= 0L) return null
        return "$path|$name|$sizeBytes"
    }

    // --------------------------------------------------------------- 后缀表

    /**
     * 明确不是媒体的后缀：字幕、图片、文档、压缩包、歌词、日志。
     *
     * 这份表**宁可漏不可错**：漏了某个非媒体后缀，结果是目录里多显示一个打不开的
     * 文件；错把一个真的媒体后缀放进来，结果是那个文件永远看不见。所以只放
     * 100% 确定不是媒体的。
     *
     * ⚠️ **「漏一个」的代价并不总是「多显示一个文件」**。`UNKNOWN` 是「交给内核去
     * 探测」，而播放页只有音频/视频两种版面（唯一判据是 `entry.kind == VIDEO`），
     * 所以一个漏掉的后缀会以**音频页**的样子打开（封面 + 歌词，什么都播不出来，
     * 日志里一句错都没有）。这正是 v1.4.6 之前 `.docx` 的表现，见下面「办公文档」
     * 那一段。⇒ **常见文件类型必须补全，不能只想着冷门媒体格式。**
     *
     * 三张表都是 `internal` 而不是 `private`：`SafScanRulesTest` 要拿它们做结构守卫
     * （三张表两两不相交、全部小写无点、没有残留的大写/带点写法）。这类守卫是
     * 唯一能防住「以后往里加后缀时手滑」的东西——加错的后果都是静默的。
     */
    internal val NON_MEDIA_EXTENSIONS = setOf(
        // 字幕
        "srt", "ass", "ssa", "vtt", "lrc", "ttml", "dfxp", "smi", "sami", "sub", "idx",
        // 图片
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff",
        "svg", "ico", "dng", "raw",
        // 办公文档（Word / Excel / PowerPoint / OpenDocument / WPS）
        // 这一组是 v1.4.6 补的：用户报「docx 被识别成音频」。根因**不是** MIME
        // 说谎，而是这里少了这一组后缀 ⇒ `.docx` 落到第 4 步成 UNKNOWN ⇒ 可播
        // ⇒ 点开是一个播不出任何东西的音频页。补上之后它变成「明确不是媒体」，
        // 在浏览页里仍然可见（用户要能看见自己的文件），只是不可播、不进队列。
        "doc", "docx", "docm", "dot", "dotx",
        "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx",
        "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx",
        "rtf", "odt", "ods", "odp", "odg",
        // WPS Office（国内的 .wps / .dps 极常见）、OFD（国标文档）、
        // iWork（.pages / .numbers / .key）、Visio(.vsd/.vsdx) / Publisher(.pub) /
        // XPS(.xps)、CHM 帮助文件。
        // ⚠️ WPS 的**表格**后缀是 `.et`，**故意不加**——`.et` 是埃塞俄比亚的国家顶级域名，
        // 跟 `.ai` 是同一个坑（这张表被 `RemoteUrlRules.looksLikeHost` 借去判主机名）。
        "wps", "dps", "ofd", "pages", "numbers", "key",
        "vsd", "vsdx", "pub", "xps", "chm",
        // 电子书（cbz / cbr 本质是图片压缩包，不是视频）
        "epub", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr",
        // ⚠️ 设计稿（`psd` / `ai` / `sketch` / `fig`）**故意不加**：`.ai` 同时是一个
        // 真实顶级域名，加进来会让地址栏里不带协议的 `xxx.ai` 被当成文件名
        // （判据见 `RemoteUrlRules.looksLikeHost`，它借的就是这张表）。
        // 设计稿出现在音视频目录里的概率远低于这个误伤。
        // 文档 / 数据
        "txt", "log", "md", "json", "xml", "csv", "pdf", "html", "htm", "yml", "yaml",
        "ini", "conf", "properties", "db", "sqlite", "bak", "tmp",
        // 压缩包
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso",
        // 安装包 / 可执行
        "apk", "dex", "so", "exe", "dll", "bin", "jar",
        // 字体
        "ttf", "otf", "woff", "woff2",
        // 缩略图 / 元数据缓存（`.nomedia` 这种点开头文件也算）
        "nomedia",
    )

    /** 音频后缀。视频容器里也能装纯音频（`.mka`、`.ts` 里的 AAC），所以两张表不重合。 */
    internal val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "wav", "wave", "ogg", "oga", "opus", "wma",
        "ape", "alac", "aiff", "aif", "amr", "ac3", "eac3", "dts", "mka", "mp2", "mpga",
        "mid", "midi", "tta", "wv", "dsf", "dff", "m4p", "f4a",
    )

    /**
     * 视频后缀。
     *
     * `ts` / `m2ts` / `vob` / `rmvb` 这些都在表里，是因为它们**都是本应用通过
     * FFmpeg 能播的**——把它们挡在浏览页外，等于把 v0.4 那一版的成果藏起来。
     */
    internal val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "asf", "flv", "f4v", "ts", "m2ts",
        "mts", "mpg", "mpeg", "mpe", "m2v", "3gp", "3g2", "ogv", "rm", "rmvb", "vob",
        "divx", "xvid", "tp", "trp", "mxf", "ogm", "dv", "f4p",
    )
}
