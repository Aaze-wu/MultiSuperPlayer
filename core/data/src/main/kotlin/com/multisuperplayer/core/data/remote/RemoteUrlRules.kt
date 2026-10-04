package com.multisuperplayer.core.data.remote

import com.multisuperplayer.core.data.library.SafScanRules
import com.multisuperplayer.core.model.ExternalMediaIds
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.MediaSource
import java.net.URLDecoder

/**
 * 「网络地址」的纯规则：合法化、标题、类型判定、条目构造。全部无副作用，可单测。
 *
 * ## 为什么规则单独成一层
 *
 * 手输地址这条路上「用户输入的东西」和「播放器能认的东西」差得很远：用户会
 * 只写 `www.x.com/a.mp4`、会把地址粘在空格里、会在中文输入法下把 `:` 打成 `：`。
 * 这些清洗如果写在 Composable 或 ViewModel 里，就只能靠真机手输去验；
 * 抽成纯函数之后，每个坑都是一条断言。
 *
 * ## 判据一律是「白名单」
 *
 * 只有 `http` / `https` 能通过（协议可以省略，省略时按 [DEFAULT_SCHEME] 补）。
 * 不做 `rtsp:` / `rtmp:` / `magnet:`：媒体内核虽然认识一部分协议，但它们的
 * 缓冲、seek、断点续播行为都和 HTTP 不一样，没有验证过的路径不该出现在
 * 「地址栏」里——那种「能填进去但放不出来」的入口比没有入口更糟。
 */
object RemoteUrlRules {

    /** 历史里最多留几条地址。 */
    const val HISTORY_LIMIT: Int = 20

    private const val DEFAULT_SCHEME = "http"
    private const val SCHEME_SEPARATOR = "://"
    private val SUPPORTED_SCHEMES = setOf("http", "https")

    /**
     * 把用户输入的一个地址变成可以交给播放器的绝对地址；不是合法地址时返回 `null`。
     *
     * 通过的条件（按顺序）：
     *
     * 1. 去掉首尾空白、把全角标点换回 ASCII（见 [FULL_WIDTH_ALIASES]）；
     * 2. 中间不能有空白，也不能有反斜杠——前者是「用户粘进来两行」，后者是
     *    「从 Windows 资源管理器复制的路径」，把它们拼成 URL 只会得到一个必然
     *    失败的请求；
     * 3. 协议必须在 [SUPPORTED_SCHEMES] 里，**大小写无关**（`HTTP://X` 是人手写的）；
     * 4. 主机名不能为空、不能以点开头或结尾；
     * 5. **省略协议时，剩下的部分必须「像主机名」**（[looksLikeHost]），才补
     *    [DEFAULT_SCHEME]。这条是为了把「用户把片名打进地址框」变成一个
     *    立刻可见的「这不是一个地址」，而不是一个几十秒后才出现的网络错误；
     *    显式写了 `http://` 的人（`http://nas`、`http://localhost`）意图明确，照收。
     *
     * 返回的是**规范化之后**的地址（协议小写、补好协议），它同时也是
     * [MediaEntry.id] 的一部分——同一个地址再次输入必须命中同一条播放记录，
     * 所以这里不能返回用户的原串。
     */
    fun normalize(raw: String): String? {
        val text = raw.trim().aliasFullWidthPunctuation()
        if (text.isEmpty()) return null
        if (text.any { it.isWhitespace() }) return null
        if (text.contains('\\')) return null

        val separator = text.indexOf(SCHEME_SEPARATOR)
        val typedScheme = separator > 0
        val scheme: String
        val rest: String
        if (typedScheme) {
            scheme = text.substring(0, separator).lowercase()
            rest = text.substring(separator + SCHEME_SEPARATOR.length)
        } else {
            scheme = DEFAULT_SCHEME
            rest = text
        }
        if (scheme !in SUPPORTED_SCHEMES) return null

        // 主机名 = 协议之后、第一个 `/`（或 `?` / `#`）之前，再去掉 `:端口`。
        // 用截串而不是 `java.net.URL`：`URL` 在 `android.jar` 里是空壳，
        // 单测跑在 JVM 上时它和真机行为不一致（见 `FieldTextCodec` 的注释里
        // 同一类问题的记录），而这里需要的判据只有「主机名像不像样」。
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringBefore(':')
        if (host.isEmpty() || host.startsWith('.') || host.endsWith('.')) return null
        if (!typedScheme && !looksLikeHost(host)) return null

        return "$scheme://$rest"
    }

    /**
     * 省略协议时，剩下那一段像不像主机名。
     *
     * 两条判据缺一不可：
     *
     * 1. **含有点**：`www.x.com`、`192.168.1.5:8080` 是主机名，`让子弹飞`、
     *    `nas/movies` 是用户随手打的字；
     * 2. **整段不能是一个文件名**：这一条是三行代码换来的，而它管的恰恰是
     *    **最常见的那个错**——用户从文件管理器复制一个片名过来，粘的是
     *    `TestClip.mp4`，它天然含有点 ⇒ 只判第 1 条的话，这种输入会被补成
     *    `http://TestClip.mp4`，再花十几秒 DNS 失败。这正是第 5 条规则
     *    存在的理由，而「片名」绝大多数是**带后缀**的。
     *
     * 判据借用 [SafScanRules.kindOf]，不另写一张后缀表：地址栏里 `.mp4` 是文件名
     * 而在本地文件里是媒体，这两件事必须用同一张表，否则同一个字符串在两条路上
     * 有两种结论。`UNKNOWN` 是「见过但认不出」，`null` 是「明确不是媒体」
     * （`.lrc` 这种）——两者都不是主机名，所以只放行 `UNKNOWN`。
     */
    private fun looksLikeHost(host: String): Boolean {
        if ('.' !in host) return false
        return SafScanRules.kindOf(host, mimeType = null) == MediaKind.UNKNOWN
    }

    /**
     * 地址里最后一个路径段的文件名（已按百分号解码、已去掉 `?` / `#` 之后的部分）。
     *
     * `https://host/a%20b/My%20Movie.mp4?token=1` → `My Movie.mp4`
     *
     * 拿不到路径段时（地址指向站点根）返回空串：调用方会退回用整条地址当标题，
     * 这比显示一个空的标题好。**不要**在这里去猜「是不是个文件名」。
     */
    fun fileNameOf(url: String): String {
        val path = url.substringBefore('#').substringBefore('?')
        return decode(path.substringAfterLast('/')).trim()
    }

    /**
     * 文件名 → 标题：去掉最后一个点之后的部分。
     *
     * 判据是「去完之后还剩不剩东西」，不是「名字里有没有点」——
     * `.nomedia` 这种整个名字就是一个后缀的文件去完会变成空串，
     * 标题就成了一行空白。这条规则和本地文件的 `BrowserEntry.displayTitle`
     * 是同一条（那边是数据类的成员，读文件系统时用；这里读的是地址）。
     */
    fun displayTitleOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return fileName
        return fileName.substring(0, dot).ifEmpty { fileName }
    }

    /** 地址的展示标题（播放页标题、历史列表那一行）。 */
    fun titleOf(url: String): String {
        val name = fileNameOf(url)
        return if (name.isEmpty()) url else displayTitleOf(name)
    }

    /**
     * 地址的媒体类型。
     *
     * 复用 [SafScanRules.kindOf] 的后缀表，而不是另写一张：网络地址的后缀识别
     * 必须和本地文件**完全一致**（`.mka` / `.ts` / `.ape` 在本地认、在网络上不认，
     * 就会出现「同一个文件下载下来能播、直接播地址却进了未知类型」这种分叉），
     * 而那张表是这个项目里唯一一份，也是花了一整版调出来的。
     *
     * 后缀明确不是媒体的东西（`.jpg`、`.lrc`）在这里**不拒绝**：用户是主动把
     * 这条地址填进来的，「地址后面不是媒体」这个判断交给内核去做、去报错，
     * 比我们把它变成一个安静的空结果好。所以 `null` 映射成 [MediaKind.UNKNOWN]。
     */
    fun kindOf(url: String): MediaKind =
        SafScanRules.kindOf(fileNameOf(url), mimeType = null) ?: MediaKind.UNKNOWN

    /** 一条地址 → 可以交给播放器的 [MediaEntry]。 */
    fun toEntry(url: String): MediaEntry {
        val name = fileNameOf(url)
        return MediaEntry(
            id = ExternalMediaIds.remoteIdOf(url),
            uri = url,
            title = if (name.isEmpty()) url else displayTitleOf(name),
            kind = kindOf(url),
            source = MediaSource.REMOTE,
            displayName = name.ifEmpty { null },
        )
    }

    /**
     * 历史列表的排序 + 截断（纯函数，存储层只负责读出 `地址 → 添加时间`）。
     *
     * 时间戳是**我们的簿记**，不是用户的数据：它坏掉（读不出数字）时把那条排到最后，
     * 而不是整条丢掉——地址是用户一条条输进来的。同一毫秒的并列再按地址排序，
     * 保证同一个存储内容每次读出来都是同一个顺序（否则 DataStore 的键顺序会漏到界面上）。
     */
    internal fun arrangeHistory(entries: List<Pair<String, Long>>, limit: Int = HISTORY_LIMIT): List<String> =
        entries
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
            .map { it.first }
            .take(limit)

    /**
     * 全角标点 → ASCII。
     *
     * 只在**手输**时才会命中（粘贴进来的地址本来就是 ASCII），而手输时罪魁祸首
     * 就是中文输入法：实测在中文环境下敲 `http://192.168.1.5:11434/v1` 会得到
     * `http：／／192.168.1.5：11434／v1`（`：` U+FF1A、`／` U+FF0F），甚至
     * `。` 顶替 `.`。这类输入看起来「地址填好了」，实际是一个必然失败的字符串，
     * 而错误信息（网络错误）完全指不到真正的起因。
     *
     * 只映射**在 URL 里有明确含义**的那几个字符；中文逗号、括号这些不映射——
     * 它们可能真的是文件名/查询参数的一部分，改掉反而是我们弄坏了用户的地址。
     */
    private val FULL_WIDTH_ALIASES: Map<Char, Char> = mapOf(
        '：' to ':',
        '／' to '/',
        '。' to '.',
        '．' to '.',
        '＃' to '#',
        '？' to '?',
        '＆' to '&',
        '＝' to '=',
        '％' to '%',
        '＠' to '@',
        '＋' to '+',
        '－' to '-',
        '＿' to '_',
        '～' to '~',
    )

    private fun String.aliasFullWidthPunctuation(): String {
        if (none { it in FULL_WIDTH_ALIASES }) return this
        val builder = StringBuilder(length)
        for (ch in this) builder.append(FULL_WIDTH_ALIASES[ch] ?: ch)
        return builder.toString()
    }

    /**
     * 百分号解码：地址里的中文/空格都是编码过的，直接显示会是一串 `%E7%94%B5`。
     * 解不出来（`%` 后面不是合法的十六进制）时**原样返回**：用户可能在地址里
     * 真的写了一个 `%`，把它整条判成无效比显示一个丑标题更糟。
     *
     * `+` 先换成 `%2B`：`URLDecoder` 会把 `+` 当空格（那是
     * `application/x-www-form-urlencoded` 的规则），而地址里的 `+` 是字面量。
     */
    private fun decode(text: String): String {
        if ('%' !in text && '+' !in text) return text
        return runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
    }
}
