package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.model.SubtitleFormat

/**
 * 外挂字幕的**命名规则**：从文件名里读出语言标记，并判断它到底属于哪一条媒体。
 *
 * ## 为什么单独抽一个文件
 *
 * 这里每条规则都在决定「字幕对不对得上」，而失败时**不会报错**：
 * 单集字幕被挂到另一集上、繁体被当简体、双语文件只挑出英文——界面上全都
 * 「有字幕在显示」，只是内容是错的。这类 bug 只能靠纯函数 + 单测拦住；
 * 混在 `ContentResolver` 查询里就是一行都测不到。
 *
 * ## 为什么归一化到 BCP-47
 *
 * 发布组对同一种语言有几十种写法（`chs` / `sc` / `cn` / `zh-Hans` / `简体`…），
 * 彼此还会冲突。这里统一归一化成 BCP-47，显示名（「简体中文」）交给 UI 层决定，
 * 数据层不需要关心它是给哪个界面用的。
 */
internal object SubtitleFileNaming {

    /** 关联打分：`0` 表示「不是这条媒体的字幕」。 */
    const val NO_MATCH = 0

    /**
     * 「字幕名与媒体名去掉后缀后完全相同」。
     *
     * 故意不做 `private`：`SubtitleFiles.subtitleSourceOf` 也要用它——用户在文件
     * 浏览器里**亲手点中**的那个文件，关联分本来就是满分（他指定的就是它）。
     * 那边另写一个字面量 100 的话，两处迟早会分家。
     */
    const val SCORE_EXACT = 100

    private const val SCORE_PREFIX = 90
    private const val SCORE_PREFIX_REVERSED = 85

    /**
     * 前缀匹配要求的最短片名长度。
     *
     * 没有这道门槛时，`a.srt` 会前缀命中 `abc.mkv`，`up.srt` 会命中 `u.mkv`。
     * 片名越短，「是前缀」就越可能只是巧合。
     */
    private const val MIN_TITLE_LENGTH = 3

    /** 格式与媒体类型对口时的加减分幅度。 */
    private const val AFFINITY = 25

    /**
     * 可以作为「文本外挂字幕」被发现的格式后缀。
     *
     * 刻意排掉所有位图格式（[SubtitleFormat.isBitmap]：VobSub / PGS / DVB）：它们是
     * 一张张图片 + 索引文件，需要专门的图形渲染器，解析器读不出任何文本。
     * 把它们列进候选，只会让子集列表里多出一堆「点了必然失败」的文件。
     *
     * 用 `isBitmap` 而不是逐个点名，是为了让以后再加一个位图格式时**这里自动生效**
     * ——那种「新格式悄悄跑进候选清单」的缺口在界面上只表现为「点它没反应」。
     */
    val discoverableExtensions: List<String> = SubtitleFormat.entries
        .filterNot { it == SubtitleFormat.UNKNOWN || it.isBitmap }
        .flatMap { it.extensions }
        .distinct()

    /**
     * 文件名里常见的语言标记 → BCP-47。
     *
     * 注意 `"中文"` 归到简体：这是个取舍，这个应用的主要用户群在简体环境里，
     * 而且猜错时「简体的片子配了繁体字幕」比反过来更容易读。
     */
    private val languageAliases: Map<String, String> = buildMap {
        fun add(tag: String, vararg aliases: String) = aliases.forEach { put(it, tag) }

        // 中文（简体）。发布组标记最多的一类：chs / sc / cn / gb / 简中…
        add(
            "zh-Hans", "zh", "chi", "zho", "chs", "sc", "cn", "sg", "gb", "gbk", "gb2312",
            "zh-cn", "zh-sg", "zh-hans", "简体", "简中", "中文",
        )
        // 中文（繁体）
        add(
            "zh-Hant", "cht", "tc", "tw", "hk", "big5",
            "zh-tw", "zh-hk", "zh-hant", "繁体", "繁中",
        )
        // 主要外语
        add("en", "en", "eng", "english", "英文")
        add("ja", "ja", "jp", "jpn", "japanese", "日文", "日语", "日本語")
        add("ko", "ko", "kor", "korean", "韩文", "韩语")
        add("fr", "fr", "fra", "fre", "french", "法文")
        add("de", "de", "deu", "ger", "german", "德文")
        add("es", "es", "spa", "spanish", "西班牙文")
        add("ru", "ru", "rus", "russian", "俄文")
        add("pt", "pt", "por", "portuguese", "葡萄牙文")
        add("it", "it", "ita", "italian", "意大利文")
        add("th", "th", "tha", "泰文")
        add("vi", "vi", "vie", "越南文")
    }

    /** 强制字幕（通常只覆盖外语对白部分）。 */
    private val forcedTokens = setOf("forced", "force", "强制")

    /** 「这条文件里同时有两种语言」的标记。 */
    private val bilingualTokens = setOf(
        "bilingual", "dual", "双语", "中英", "中日", "简繁",
        "chi&eng", "eng&chi", "chs&eng", "eng&chs", "zh&en", "en&zh", "chs&cht",
    )

    /**
     * 文件名分词。
     *
     * **刻意不在 `-` 和 `&` 上切分**：
     * - `zh-CN` 是一个完整标记，切开就只剩 `zh` + `cn`（碰巧还能认出来，但 `zh-TW` 不行）；
     * - `chi&eng` 是「双语」这个含义的整体，切开会被当成两个单语言标记。
     * 复合标记在 [classifyToken] 里再按 `-` 二次拆分。
     */
    private val tokenSeparator = Regex("""[._\s\u3000]+""")

    /** 方括号包裹的标记：`[CHS]`、`【简中】`、`(eng)` 都很常见。 */
    private const val BRACKETS = "[](){}<>【】〖〗（）《》"

    /** 圆括号的两种写法：里面的内容要再按「是不是 ASCII」判一次，见 [keepDecoration]。 */
    private const val PAREN_BRACKETS = "(（"

    /**
     * 整段被一对括号包住的「装饰段」：`[YYeTs]`、`【高清影视】`、`(2023)`、`《片名》`…
     *
     * 逐对写清楚而不是用一个「所有括号」的字符类：这样配对不完整时（`[a.b`）
     * 整段保持原样，而不会只吃掉半截（只吃掉半截等于把片名改了）。
     *
     * ⚠️ **花括号两个都要转义，字符类里面也一样**：JVM 的 `Pattern` 接受裸的 `}`，
     * Android 的正则引擎（ICU）不接受，会在**类初始化**时抛 `PatternSyntaxException`，
     * 表现为「一播放就闪退」而**单测全绿**——因为单测跑在 JVM 上，那个 `}` 是合法的。
     * 花括号在正则里是量词符号（`{1,3}`），要当字面量就必须转义。
     * 这条由 `KotlinRegexBracesTest` 盯着（它按「只允许量词」这条规则扫全仓库）。
     */
    private val decorationGroup = Regex(
        """\[[^\[\]]*]|【[^【】]*】|〖[^〖〗]*〗|\{[^\{\}]*\}|《[^《》]*》|<[^<>]*>|\([^()]*\)|（[^（）]*）""",
    )

    private val seasonEpisode = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[\s._\-]*e(\d{1,3})(?![0-9])""")
    private val crossEpisode = Regex("""(?i)(?:^|[^0-9])(\d{1,2})x(\d{1,3})(?![0-9])""")
    private val cjkEpisode = Regex("""(?:^|[^a-z0-9])第\s*(\d{1,3})\s*[集话話]?""")
    private val animeEpisode = Regex("""(?i)(?:^|[^a-z0-9])(?:ep|e)(\d{1,3})(?![0-9])""")

    /**
     * [episodeOf] 用到的全部写法，顺序即优先级。
     *
     * 另有一份列表专供 [stripEpisodeMarkers] 用。两者必须一致，否则会出现
     * 「认得出是两个不同的集、却认不出是同一个集」的自相矛盾（已在
     * `SubtitleFileNamingTest` 里按四种写法各钉了一条用例）。
     */
    private val episodePatterns = listOf(seasonEpisode, crossEpisode, cjkEpisode, animeEpisode)

    // ------------------------------------------------------------------ 结果类型

    /**
     * 文件名解析结果。
     *
     * 这里**只带出片名的原文**，不带「比较用的写法」：写法有几种（见 [coreVariants]），
     * 而「试哪几种」是 [matchScore] 的决定——把归一化后的片名也存一份，
     * 就会有第二个地方在决定「装饰段要不要去掉」。
     */
    data class NameInfo(
        /**
         * 片名原文：分词后用空格重新拼起来，**装饰段与季集号都还在**、语言标记已经切掉。
         *
         * 必须用空格拼回来（而不是直接用 `fileName` 或归一化后的文本）：
         * [coreVariants] 里找季集号的正则靠的就是「前面的字符不是字母数字」这条边界，
         * 而 [normalize] 会把分隔符全删掉——`某某电视剧 S01E01 1080p` 归一化之后是
         * `某某电视剧s01e011080p`，集号跟分辨率粘成一串数字，再也认不出边界了。
         */
        val rawTitle: String,
        /** BCP-47；认不出来时为 null。 */
        val languageTag: String?,
        val isForced: Boolean,
        val isBilingual: Boolean,
        /**
         * 片名**之外**还带了几段标记（语言、`forced`、`双语`、`1080p`…）。
         *
         * 只用于同分候选之间的排序，不参与打分，见 [associationScore]。
         */
        val trailingTagCount: Int = 0,
    )

    private data class TokenInfo(
        val language: String? = null,
        val forced: Boolean = false,
        val bilingual: Boolean = false,
    )

    /**
     * 季集号。
     *
     * 拆成「季」和「集」两个可空字段而不是一个字符串，是为了让不同命名风格能互相比较：
     * `第01集` 没有季号、`S01E01` 有，两者讲的是同一集，不能因为字符串不同就判成冲突。
     */
    data class Episode(val season: Int?, val number: Int)

    // ------------------------------------------------------------------ 文件名解析

    /** 去掉后缀。注意 `.srt` 这种「隐藏文件」会让下标的点在 0 位，此时原样返回。 */
    fun baseName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName else fileName.substring(0, dot)
    }

    /**
     * 归一化到「只留字母数字」的形式，用于比较。
     *
     * 去掉所有分隔符而不是统一成某一种：同一个片名有 `Show.S01E01` / `Show_S01E01` /
     * `Show S01E01` 三种写法，统一成任一种都还会在另一个方向上对不上，
     * 「全去掉」在两个方向（相等、前缀）上都稳。中文按原样保留，
     * 这样 `英文名.简体.srt` 这种混排也能参与前缀比较。
     */
    fun normalize(raw: String): String = raw.lowercase().filter { it.isLetterOrDigit() }

    /**
     * 一个名字**可能写成**的几种「核心名」，比较时两两试一遍、取最高分。
     *
     * ## 为什么是「几种写法」而不是一种
     *
     * 同一段方括号，在视频名里常常装的是**片名**，在字幕名里常常装的是**发布组**：
     *
     * - `[阿凡达：水之道].Avatar.The.Way.of.Water.2160p.mkv` ← 删掉就再也认不出是哪部片
     * - `[某某字幕组]某某电影.chs.srt` ← 不删就永远对不上 `某某电影.mkv`
     *
     * 而「这段括号里到底是不是片名」在文件名里**没有可靠判据**
     * （`[电影天堂www.dygod.net]` 与 `[阿凡达：水之道]` 的结构一模一样）。
     * 所以这里不猜：两种写法都是候选，能对上就是对上。
     *
     * 顺带解决兜底问题：去装饰段之后什么都不剩时（`《片名》.srt` 这种整段被包起来的），
     * 不去装饰的那一份仍然在候选里——片名空了会让这条文件从候选里静默消失
     * （它至少还能出现在手动列表里）。
     *
     * @param withEpisodeStripped 是否**额外**把季集号也去掉再生成一遍。
     *   字幕名那一侧**总是**开着：`某某电视剧.mkv` 配 `某某电视剧 第01集.srt` 是最常见的
     *   用法，字幕多写一个集号不该让匹配降一格。
     *   媒体名那一侧只在**两侧都写了集号、且指向同一集**时才开（见 [matchScore]）：
     *   媒体名是「这一条到底是第几集」的唯一来源，凭空去掉会把 `Show.other.srt`
     *   变成 `Show.S01E01.mkv` 的匹配。
     */
    private fun coreVariants(raw: String, withEpisodeStripped: Boolean = false): List<String> {
        val texts = if (withEpisodeStripped) listOf(raw, stripEpisodeMarkers(raw)) else listOf(raw)
        return texts
            .flatMap { listOf(normalize(it), normalize(stripDecorations(it))) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 去掉「装饰段」：整段被一对括号包住的发行标记。
     *
     * ## 为什么必须去掉
     *
     * 归一化（[normalize]）只是把分隔符丢掉，而这些段落**会被粘连到片名上**：
     * `[某某字幕组]某某电影` 归一化成 `某某字幕组某某电影`，于是和 `某某电影`
     * 既不相等也不互为前缀——两条明明对得上的文件被判成「不是它的字幕」。
     * 中文发布组的习惯正好全踩在这上面：片名前后挂 `【高清影视】`、`[电影天堂xxx]`、
     * `[YYeTs]`，而字幕名往往是干净的 `某某电影.chs.srt`。
     * 名字越长、空格越多，这种「多出来的段」就越多——所以它的症状看起来
     * 像「长文件名不认识」，其实与长度无关，只与是否带装饰段有关。
     *
     * 媒体名那一侧同样要去（视频才是常带装饰段的那一个）。
     */
    private fun stripDecorations(raw: String): String = decorationGroup.replace(raw) { match ->
        val open = match.value.first()
        if (keepDecoration(open, match.value.substring(1, match.value.lastIndex))) {
            match.value
        } else {
            // 换成空白：归一化会把分隔符全去掉，所以用什么都一样。
            " "
        }
    }

    /**
     * 这一段括号里的内容是不是「片名的一部分」，是的话不能删。
     *
     * - **光是年份的**（`The.Thing.(1982)` / `(2011)`）：那正是区分翻拍片的东西，
     *   删掉两份不同电影会变成同一个核心名；
     * - **圆括号里的纯中文短语**（`（上）`、`（下）`、`（剧场版）`）：发行标记几乎
     *   全是 ASCII（`(Uncut)`、`(1080p)`、`(Director's Cut)`），中文短语则多半是片名，
     *   删掉「上/下」会让上下两部互相匹配。
     *
     * 方括号与书名号里的一律当标记（`[YYeTs]`、`【高清影视】`、`《片名》`）——
     * 中文发布组的组名就写在方括号里，那是必须删掉的那一类。
     */
    private fun keepDecoration(open: Char, body: String): Boolean {
        if (body.length == 4 && body.all { it.isDigit() }) return true
        val asciiOrDigit = body.any { it.isDigit() || it in 'a'..'z' || it in 'A'..'Z' }
        return open in PAREN_BRACKETS && !asciiOrDigit
    }

    /** 由后缀推断格式；认不出来时返回 [SubtitleFormat.UNKNOWN]（交给内容嗅探）。 */
    fun formatOf(fileName: String): SubtitleFormat {
        // 先小写：MediaStore 的 LIKE 匹配不分大小写，所以 `.SRT` 也会进候选列表，
        // 而 candidatesForExtension 是按字面比较的。
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return SubtitleFormat.candidatesForExtension(extension).firstOrNull() ?: SubtitleFormat.UNKNOWN
    }

    /**
     * 解析文件名：切出片名、语言标记与标志。
     *
     * 从**末尾往前**读，遇到第一个不认识的词就停——这正是发布组的书写习惯
     * （`片名.语言.强制.srt`），而片名本身可能恰好包含像语言标记的词
     * （电影《It》），所以只在末尾连续的一段里找标记。
     *
     * 认不出来的词（`1080p`、`WEB-DL`、`【高清影视】`）归属于片名，所以片名里还会夹着
     * 发行标记与季集号——它们都在比较那一步处理（见 [coreVariants]）；
     * 语言标记本身则在分词这一步就认掉了（与装饰段无关）。
     */
    fun analyze(fileName: String): NameInfo {
        val tokens = tokenSeparator.split(baseName(fileName)).filter { it.isNotEmpty() }

        var cut = tokens.size
        var language: String? = null
        var forced = false
        var bilingual = false

        while (cut > 0) {
            val info = classifyToken(tokens[cut - 1]) ?: break
            // 越靠左的标记越靠近片名，也就越像「主语言」：`movie.chs.zh.srt`
            // 应该算简体而不是笼统的 zh。
            language = info.language ?: language
            forced = forced || info.forced
            bilingual = bilingual || info.bilingual
            cut--
        }

        // 整个文件名都由标记构成（电影《It》的字幕就叫 `It.srt`）。此时片名是空的，
        // 会把这条字幕从候选里彻底删掉。退回「全部当片名」：宁可丢掉语言标记，
        // 也不能让文件消失——它至少还能出现在手动列表里。
        if (cut == 0 && tokens.isNotEmpty()) {
            return NameInfo(tokens.joinToString(" "), null, false, false)
        }

        return NameInfo(
            rawTitle = tokens.subList(0, cut).joinToString(" "),
            languageTag = language,
            isForced = forced,
            isBilingual = bilingual,
            // `cut` 之后剩下的就是标记，数量即「这条字幕比片名多说了些什么」。
            trailingTagCount = tokens.size - cut,
        )
    }

    private fun classifyToken(raw: String): TokenInfo? {
        val token = raw.lowercase()
        if (token.isEmpty()) return null
        classifySimple(token)?.let { return it }

        // 方括号只是发布组的排版习惯，不影响含义（`[CHS]` 就是 `chs`）。
        // 不剥掉的话，整个标记会被当成片名的一部分，于是语言标签丢失。
        val cleaned = token.trim { it in BRACKETS }
        if (cleaned.isEmpty()) return null
        if (cleaned != token) {
            classifySimple(cleaned)?.let { return it }
        }

        // 复合标记：`zh-cn`（上面已直接命中）、`chi-eng`、`zh-forced`。
        // 要求**每一段**都认识才算标记，否则 `Spider-Man` 会被拆成 `spider` + `man`
        // 之后当作未知处理——结果一样，但那是碰巧，不能靠碰巧。
        val parts = cleaned.split('-').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val infos = parts.map { classifySimple(it) ?: return null }
        val languages = infos.mapNotNull { it.language }
        return TokenInfo(
            language = languages.lastOrNull(),
            forced = infos.any { it.forced },
            bilingual = infos.count { it.language != null } > 1,
        )
    }

    private fun classifySimple(token: String): TokenInfo? = when {
        languageAliases.containsKey(token) -> TokenInfo(language = languageAliases.getValue(token))
        token in bilingualTokens -> TokenInfo(bilingual = true)
        token in forcedTokens -> TokenInfo(forced = true)
        else -> null
    }

    // ------------------------------------------------------------------ 季集号

    /** 从文件名里抽出季集号；没有则返回 null。 */
    fun episodeOf(fileName: String): Episode? {
        // 顺序有意义：S01E01 最明确，先判；否则 `1x01` 里的 `x01` 会被
        //「E01」那一条抢走一半。
        seasonEpisode.find(fileName)?.let {
            return Episode(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        crossEpisode.find(fileName)?.let {
            return Episode(it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        cjkEpisode.find(fileName)?.let {
            return Episode(null, it.groupValues[1].toInt())
        }
        animeEpisode.find(fileName)?.let {
            return Episode(null, it.groupValues[1].toInt())
        }
        return null
    }

    /**
     * 去掉季集号，**只用于比较**；认出来的写法与 [episodeOf] 完全一致。
     *
     * 四个正则都带一个「吃掉前一个字符」的组 `(?:^|[^a-z0-9])`，为的是让紧跟在
     * 汉字后面的写法（`某剧第01集`）也能命中。替换时必须把这个字符**放回去**：
     * 否则 `某某电视剧第01集` 会被删成 `某某电视`——那是另一个片名了。
     * 匹配从 0 开始时那个组是零宽的 `^`（前面没有字符），否则它就是第 0 位那一个字符。
     */
    private fun stripEpisodeMarkers(raw: String): String {
        var text = raw
        for (regex in episodePatterns) {
            text = regex.replace(text) { match ->
                if (match.range.first == 0) "" else match.value.take(1)
            }
        }
        return text
    }

    // ------------------------------------------------------------------ 关联打分

    /**
     * 字幕文件与媒体文件的关联度。`0` 表示肯定不是它的字幕。
     *
     * @param mediaFileName 媒体文件名（带后缀，例如 `Show.S01E01.1080p.mkv`）。
     * @param subtitleFileName 字幕文件名（带后缀）。
     */
    fun matchScore(mediaFileName: String, subtitleFileName: String): Int {
        val info = analyze(subtitleFileName)
        val mediaName = baseName(mediaFileName)
        val mediaVariants = coreVariants(mediaName)
        // 字幕名这一侧**总是**把集号也去掉：`某某电视剧.mkv` 配
        // `某某电视剧 第01集.srt` 是最常见的用法，字幕多写一个集号不该降一格。
        val titleVariants = coreVariants(info.rawTitle, withEpisodeStripped = true)
        if (mediaVariants.isEmpty() || titleVariants.isEmpty()) return NO_MATCH

        // 季集号不同的两份字幕长得**极其**相似（共同前缀往往只差最后一位），
        // 但挂错集比完全没有字幕更糟：用户会以为播放器把字幕串台了，
        // 而且这种「看起来正常的错误」会一路用到他发现台词对不上为止。
        val mediaEpisode = episodeOf(mediaFileName)
        val subtitleEpisode = episodeOf(subtitleFileName)
        if (mediaEpisode != null && subtitleEpisode != null && isDifferentEpisode(mediaEpisode, subtitleEpisode)) {
            return NO_MATCH
        }

        // 注意这里**只**决定分数，不决定同分时的先后。片名一样的三条字幕
        // （`Show.srt` / `Show.chs.srt` / `Show.bilingual.srt`）会并列满分，
        // 谁被自动挂上由 `SubtitleSource` 的排序规则解决——那是「多个都对时挑哪个」
        // 的问题，塞进打分只会让同一件事有两个表达。
        val direct = bestScore(mediaVariants, titleVariants)
        if (direct != NO_MATCH) return direct

        // 只有一边写了集号时**不看下一步**：`Show.S01E01.mkv` 旁边的 `Show.other.srt`
        // 谁也不知道它是哪一集，留在手动列表里让用户选才对。
        if (mediaEpisode == null || subtitleEpisode == null) return NO_MATCH

        // 退一步：两边都写了季集号、而且指向同一集（冲突的那种上面已经否决过），
        // 那「集号」本身就不再有任何区分力了，把它从比较里去掉再比一次。
        // 这一步专门为**跨写法**准备：视频是 `Show.S01E01.1080p.mkv`、字幕是
        // `Show 第01集.chs.srt`（两者常常来自不同的发布组），片名明明一样，
        // 却因为一个写 `S01E01`、一个写 `第01集` 而互相不认识。
        //
        // 两侧都从**原始名字**上再去一遍集号（而不是从上面归一化过的候选上）：
        // 与片名同理，集号的边界只有在带分隔符的文本里才认得出。
        return bestScore(
            coreVariants(mediaName, withEpisodeStripped = true),
            coreVariants(info.rawTitle, withEpisodeStripped = true),
        )
    }

    /**
     * 两组写法两两比一遍，取最高分；全都对不上才是 [NO_MATCH]。
     *
     * 取最高而不是平均/累加：这些写法是**同一个东西的几种叫法**，不是几条独立证据。
     * 一种叫法完全相等（[SCORE_EXACT]）就足以断定是同一条；再拿别的写法去平均，
     * 只会把满分拉下来，反而丢掉「这条字幕就是它」这个事实。
     */
    private fun bestScore(mediaVariants: List<String>, titleVariants: List<String>): Int =
        mediaVariants
            .maxOfOrNull { media -> titleVariants.maxOfOrNull { compare(media, it) } ?: NO_MATCH }
            ?: NO_MATCH

    /**
     * 相等 / 互为前缀的判定，取舍见 [matchScore]。
     *
     * 刻意不做「共同前缀足够长就算匹配」那类模糊匹配：`The.Thing.1982` 与
     * `The.Thing.2011` 的共同前缀长达 8 个字符，那是两部不同的电影。
     * 宁可漏配让用户手选——漏配只是多一步操作，错配的症状是「字幕时间轴完全
     * 对不上」，而且用户看不出原因。
     */
    private fun compare(media: String, title: String): Int = when {
        media == title -> SCORE_EXACT

        // 字幕名没写分辨率/编码（`Show.S01E01.srt` 配 `Show.S01E01.1080p.mkv`）；
        // 反过来则是字幕名多了后缀。两个方向都只接受**前缀**关系。
        title.length >= MIN_TITLE_LENGTH && media.startsWith(title) -> SCORE_PREFIX
        title.length >= MIN_TITLE_LENGTH && title.startsWith(media) -> SCORE_PREFIX_REVERSED

        else -> NO_MATCH
    }

    /**
     * 关联打分 + 「格式和媒体类型对不对口」的加减分。
     *
     * 加减分**只在本来就对得上时才生效**。不这样写的话，一首歌旁边完全不相干的
     * `.lrc` 会因为 `+25` 而变成「有匹配」，于是自动挂上一份别人的歌词——
     * 而且界面上看起来一切正常，没有任何地方会报错。
     *
     * 返回值**只用于排序与展示**（同为命中时对口的那条排前面）。能不能自动挂
     * 由 [matchScore] 单独决定，见 `SubtitleSource.titleMatchScore` 与
     * `isAutoMatchable`。
     */
    fun associationScore(
        mediaFileName: String,
        subtitleFileName: String,
        subtitleFormat: SubtitleFormat,
        mediaKind: MediaKind,
    ): Int {
        val base = matchScore(mediaFileName, subtitleFileName)
        return if (base == NO_MATCH) NO_MATCH else base + kindAffinity(subtitleFormat, mediaKind)
    }

    private fun kindAffinity(format: SubtitleFormat, kind: MediaKind): Int = when {
        format.isLyricStyle -> if (kind == MediaKind.AUDIO) AFFINITY else -AFFINITY
        kind == MediaKind.AUDIO -> -AFFINITY
        else -> 0
    }

    private fun isDifferentEpisode(a: Episode, b: Episode): Boolean {
        if (a.number != b.number) return true
        // 季号只有两边都写出来的时候才能比较：`第01集` 没有季号，
        // 拿它和 `S02E01` 比季号会得出「第 2 季」这种假结论。
        return a.season != null && b.season != null && a.season != b.season
    }
}
