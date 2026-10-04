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

    private val seasonEpisode = Regex("""(?i)(?:^|[^a-z0-9])s(\d{1,2})[\s._\-]*e(\d{1,3})(?![0-9])""")
    private val crossEpisode = Regex("""(?i)(?:^|[^0-9])(\d{1,2})x(\d{1,3})(?![0-9])""")
    private val cjkEpisode = Regex("""(?:^|[^a-z0-9])第\s*(\d{1,3})\s*[集话話]?""")
    private val animeEpisode = Regex("""(?i)(?:^|[^a-z0-9])(?:ep|e)(\d{1,3})(?![0-9])""")

    // ------------------------------------------------------------------ 结果类型

    /** 文件名解析结果。 */
    data class NameInfo(
        /** 归一化后的片名，用于和媒体名做相等/前缀比较。 */
        val title: String,
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
            return NameInfo(normalize(tokens.joinToString(".")), null, false, false)
        }

        return NameInfo(
            title = normalize(tokens.subList(0, cut).joinToString(".")),
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

    // ------------------------------------------------------------------ 关联打分

    /**
     * 字幕文件与媒体文件的关联度。`0` 表示肯定不是它的字幕。
     *
     * @param mediaFileName 媒体文件名（带后缀，例如 `Show.S01E01.1080p.mkv`）。
     * @param subtitleFileName 字幕文件名（带后缀）。
     */
    fun matchScore(mediaFileName: String, subtitleFileName: String): Int {
        val media = normalize(baseName(mediaFileName))
        val info = analyze(subtitleFileName)
        if (media.isEmpty() || info.title.isEmpty()) return NO_MATCH

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
        return when {
            media == info.title -> SCORE_EXACT

            // 字幕名没写分辨率/编码（`Show.S01E01.srt` 配 `Show.S01E01.1080p.mkv`）；
            // 反过来则是字幕名多了后缀。两个方向都只接受**前缀**关系。
            //
            // 这里刻意不做「共同前缀足够长就算匹配」那类模糊匹配：
            // `The.Thing.1982` 与 `The.Thing.2011` 的共同前缀长达 8 个字符，
            // 那是两部不同的电影。宁可漏配让用户手选——漏配只是多一步操作，
            // 错配的症状是「字幕时间轴完全对不上」，而且用户看不出原因。
            info.title.length >= MIN_TITLE_LENGTH && media.startsWith(info.title) -> SCORE_PREFIX
            info.title.length >= MIN_TITLE_LENGTH && info.title.startsWith(media) -> SCORE_PREFIX_REVERSED

            else -> NO_MATCH
        }
    }

    /**
     * 关联打分 + 「格式和媒体类型对不对口」的加减分。
     *
     * 加减分**只在本来就对得上时才生效**。不这样写的话，一首歌旁边完全不相干的
     * `.lrc` 会因为 `+25` 而变成「有匹配」，于是自动挂上一份别人的歌词——
     * 而且界面上看起来一切正常，没有任何地方会报错。
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
