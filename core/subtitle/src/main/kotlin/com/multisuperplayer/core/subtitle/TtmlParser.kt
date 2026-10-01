package com.multisuperplayer.core.subtitle

import com.multisuperplayer.core.model.CuePosition
import com.multisuperplayer.core.model.KaraokeSegment
import com.multisuperplayer.core.model.SubtitleCue
import com.multisuperplayer.core.model.SubtitleFormat
import com.multisuperplayer.core.model.SubtitleStyleDef
import com.multisuperplayer.core.subtitle.internal.Timecode
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * TTML / DFXP / SMPTE-TT 解析器。
 *
 * 用 `javax.xml` 的 DOM 而不是 `android.util.Xml`：`core:subtitle` 的单元测试
 * 跑在 JVM 上，用 Android 专有 API 会让这个格式**根本无法测试**，而 TTML 的
 * 时间表达式（帧、tick）恰恰是最容易算错的部分。
 *
 * 支持的时间写法：
 * - 时钟：`00:00:01.000`、`00:00:01`、`1:02:03.5`
 * - 帧时钟：`00:00:01:12`（帧数按 `ttp:frameRate` × `ttp:frameRateMultiplier` 换算）
 * - 偏移：`1.5s`、`100ms`、`2m`、`1h`、`300t`（tick，按 `ttp:tickRate` 换算）、`25f`（帧）
 *
 * 帧与 tick 的换算自己算而不用 [Timecode.parseClockToken]：后者不知道本文档声明的
 * 帧率/时基，会按默认 25fps / 10000tps 猜，结果"看起来对但不准"。
 */
class TtmlParser : SubtitleParser {

    override val format: SubtitleFormat = SubtitleFormat.TTML

    override val supportedFormats: Set<SubtitleFormat> = setOf(SubtitleFormat.TTML)

    override fun parse(content: String): ParseResult {
        val warnings = mutableListOf<String>()
        val metadata = mutableMapOf<String, String>()
        val styles = mutableMapOf<String, SubtitleStyleDef>()
        val regions = mutableMapOf<String, CuePosition>()

        val document = parseXml(content, warnings)
        val root = document.documentElement
            ?: throw SubtitleParseException("TTML 文件没有根元素")

        val rootName = localNameOf(root)
        if (!rootName.equals("tt", ignoreCase = true)) {
            warnings += "根元素是 <$rootName>，不是 <tt>，仍按 TTML 尽力解析"
        }

        val tickRate = attr(root, "tickRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_TICK_RATE
        val frameRate = attr(root, "frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_FRAME_RATE
        val frameRateMultiplier = attr(root, "frameRateMultiplier")?.let(::parseMultiplier) ?: 1.0
        val effectiveFrameRate = frameRate * frameRateMultiplier
        val rootLang = attr(root, "lang")
        rootLang?.let { metadata["lang"] = it }

        collectStyles(root, styles)
        collectRegions(root, regions)

        val cues = mutableListOf<SubtitleCue>()
        forEachElement(root, "p") { element ->
            val begin = parseTime(attr(element, "begin"), effectiveFrameRate, tickRate)
            var end = parseTime(attr(element, "end"), effectiveFrameRate, tickRate)
            val duration = parseTime(attr(element, "dur"), effectiveFrameRate, tickRate)

            if (begin == null) {
                warnings += "跳过缺少 begin 的 <p> 元素"
                return@forEachElement
            }
            if (end == null && duration != null) end = begin + duration
            if (end == null || end <= begin) {
                warnings += "<p begin=$begin> 没有有效结束时间，已按 2 秒兜底"
                end = begin + DEFAULT_CUE_DURATION_MS
            }

            val karaoke = mutableListOf<KaraokeSegment>()
            val text = extractText(element, effectiveFrameRate, tickRate, karaoke)

            if (text.isBlank()) return@forEachElement

            val styleName = attr(element, "style")?.trim()?.takeIf { it.isNotEmpty() }
            val regionName = attr(element, "region")?.trim()?.takeIf { it.isNotEmpty() }

            cues += SubtitleCue(
                index = cues.size + 1,
                startMs = begin,
                endMs = end,
                text = text,
                karaoke = karaoke,
                styleName = styleName,
                position = regionName?.let { regions[it] },
                overrides = buildMap {
                    attr(element, "lang")?.let { put("lang", it) }
                },
            )
        }

        return ParseResult(
            format = SubtitleFormat.TTML,
            cues = CuePostProcess.dropExactDuplicates(cues),
            styles = styles,
            defaultStyle = styles.values.firstOrNull(),
            metadata = buildMap {
                putAll(metadata)
                put("frameRate", effectiveFrameRate.toString())
                put("tickRate", tickRate.toString())
            },
            warnings = warnings,
        )
    }

    // -----------------------------------------------------------------------
    // XML
    // -----------------------------------------------------------------------

    private fun parseXml(content: String, warnings: MutableList<String>): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        // 安全加固：TTML 可能来自网络，禁止 DTD / 外部实体。部分实现不支持这些 feature，
        // 所以逐个 try——配置失败不能导致整个格式解析不可用。
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }

        return try {
            val builder = factory.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            builder.parse(InputSource(StringReader(content)))
        } catch (error: Exception) {
            warnings += "XML 解析失败：${error.message}"
            throw SubtitleParseException("TTML 不是合法 XML：${error.message}")
        }
    }

    private fun localNameOf(node: Node): String =
        node.localName ?: node.nodeName.substringAfterLast(':')

    private fun attr(element: Element, name: String): String? {
        val attributes = element.attributes ?: return null
        for (i in 0 until attributes.length) {
            val attribute = attributes.item(i) ?: continue
            val local = attribute.localName ?: attribute.nodeName.substringAfterLast(':')
            if (local.equals(name, ignoreCase = true)) return attribute.nodeValue
        }
        return null
    }

    private fun forEachElement(root: Element, localName: String, action: (Element) -> Unit) {
        val stack = ArrayDeque<Node>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            if (node is Element && localNameOf(node).equals(localName, ignoreCase = true) && node !== root) {
                action(node)
            }
            val children = node.childNodes ?: continue
            // 逆序入栈，保证出栈即文档顺序。
            for (i in children.length - 1 downTo 0) {
                children.item(i)?.let(stack::addLast)
            }
        }
    }

    // -----------------------------------------------------------------------
    // 样式 / 区域
    // -----------------------------------------------------------------------

    private fun collectStyles(root: Element, styles: MutableMap<String, SubtitleStyleDef>) {
        forEachElement(root, "style") { element ->
            val id = attr(element, "id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachElement
            val fontSize = attr(element, "fontSize")
            val scale = fontSize
                ?.takeIf { it.trim().endsWith("%") }
                ?.removeSuffix("%")
                ?.toFloatOrNull()
                ?.div(100f)

            styles[id] = SubtitleStyleDef(
                name = id,
                fontFamily = attr(element, "fontFamily"),
                fontScale = scale,
                primaryColorArgb = parseTtmlColor(attr(element, "color")),
                outlineColorArgb = parseTtmlColor(attr(element, "textOutline")?.substringBefore(' ')),
                backColorArgb = parseTtmlColor(attr(element, "backgroundColor")),
                bold = attr(element, "fontWeight")?.equals("bold", ignoreCase = true),
                italic = attr(element, "fontStyle")?.equals("italic", ignoreCase = true),
                underline = attr(element, "textDecoration")?.contains("underline", ignoreCase = true),
                strikeThrough = attr(element, "textDecoration")?.contains("lineThrough", ignoreCase = true),
                alignment = parseTextAlign(attr(element, "textAlign")),
            )
        }
    }

    private fun collectRegions(root: Element, regions: MutableMap<String, CuePosition>) {
        forEachElement(root, "region") { element ->
            val id = attr(element, "id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEachElement
            val origin = attr(element, "origin") ?: return@forEachElement
            val parts = origin.trim().split(Regex("""\s+"""))
            if (parts.size < 2) return@forEachElement
            val x = parseExtentFraction(parts[0]) ?: return@forEachElement
            val y = parseExtentFraction(parts[1]) ?: return@forEachElement
            regions[id] = CuePosition(
                anchorX = x.coerceIn(0f, 1f),
                anchorY = y.coerceIn(0f, 1f),
                alignment = parseTextAlign(attr(element, "displayAlign")) ?: 2,
            )
        }
    }

    /** `50%` → 0.5；`480px` 在不知道画面宽度时无法换算，返回 null。 */
    private fun parseExtentFraction(raw: String): Float? {
        val text = raw.trim()
        return when {
            text.endsWith("%") -> text.removeSuffix("%").toFloatOrNull()?.div(100f)
            else -> null
        }
    }

    private fun parseTextAlign(raw: String?): Int? = when (raw?.trim()?.lowercase()) {
        "left", "start" -> 1
        "center" -> 2
        "right", "end" -> 3
        else -> null
    }

    private fun parseTtmlColor(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.startsWith("#")) {
            val hex = text.substring(1)
            return when (hex.length) {
                6 -> 0xFF000000L or hex.toLongOrNull(16).orZero()
                8 -> hex.toLongOrNull(16).orZero() // #RRGGBBAA
                3 -> {
                    val expanded = hex.map { "$it$it" }.joinToString("")
                    (0xFF000000L or expanded.toLongOrNull(16).orZero())
                }
                else -> null
            }
        }
        val rgbMatch = RGB.matchEntire(text) ?: return null
        val r = rgbMatch.groupValues[1].toLongOrNull() ?: return null
        val g = rgbMatch.groupValues[2].toLongOrNull() ?: return null
        val b = rgbMatch.groupValues[3].toLongOrNull() ?: return null
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun Long?.orZero(): Long = this ?: 0L

    // -----------------------------------------------------------------------
    // 时间
    // -----------------------------------------------------------------------

    private fun parseTime(raw: String?, frameRate: Double, tickRate: Double): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        FRAME_CLOCK.matchEntire(text)?.let { match ->
            val hours = match.groupValues[1].toLongOrNull() ?: return@let
            val minutes = match.groupValues[2].toLongOrNull() ?: return@let
            val seconds = match.groupValues[3].toLongOrNull() ?: return@let
            val frames = match.groupValues[4].toDoubleOrNull() ?: return@let
            return (hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L +
                (frames * 1000.0 / frameRate).toLong())
        }

        val lower = text.lowercase()
        val unit = lower.takeLastWhile { it.isLetter() }
        if (unit.isNotEmpty()) {
            val number = lower.dropLast(unit.length).toDoubleOrNull()
            when (unit) {
                "f" -> if (number != null) return (number * 1000.0 / frameRate).toLong()
                "t" -> if (number != null) return (number * 1000.0 / tickRate).toLong()
                else -> Unit
            }
        }

        return Timecode.parseClockToken(text)
    }

    /** `1 2` 或 `2 1`（分子/分母）→ 2.0 / 0.5。 */
    private fun parseMultiplier(raw: String): Double? {
        val parts = raw.trim().split(Regex("""\s+""")).mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 2 || parts[1] == 0.0) return null
        return parts[0] / parts[1]
    }

    // -----------------------------------------------------------------------
    // 正文
    // -----------------------------------------------------------------------

    private fun extractText(
        element: Element,
        frameRate: Double,
        tickRate: Double,
        karaoke: MutableList<KaraokeSegment>,
    ): String {
        val builder = StringBuilder()

        fun walk(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> builder.append(node.nodeValue.orEmpty())

                Node.ELEMENT_NODE -> {
                    val child = node as Element
                    when (localNameOf(child).lowercase()) {
                        "br" -> builder.append('\n')

                        "span" -> {
                            val begin = parseTime(attr(child, "begin"), frameRate, tickRate)
                            val end = parseTime(attr(child, "end"), frameRate, tickRate)
                            val mark = builder.length
                            val children = child.childNodes
                            for (i in 0 until children.length) {
                                children.item(i)?.let(::walk)
                            }
                            if (begin != null) {
                                val segmentText = builder.substring(mark)
                                if (segmentText.isNotEmpty()) {
                                    karaoke += KaraokeSegment(
                                        startMs = begin,
                                        durationMs = ((end ?: begin) - begin).coerceAtLeast(0L),
                                        text = segmentText,
                                    )
                                }
                            }
                        }

                        // metadata / image / set 等不影响文本。
                        else -> {
                            val children = child.childNodes
                            for (i in 0 until children.length) {
                                children.item(i)?.let(::walk)
                            }
                        }
                    }
                }
            }
        }

        val children = element.childNodes
        for (i in 0 until children.length) {
            children.item(i)?.let(::walk)
        }

        // TTML 默认的空白处理（xml:space="default"）：把连续空白折成一个空格。
        // 逐行 trim 之后再拼回，避免 <br/> 前后出现多余空格。
        return builder.toString()
            .split('\n')
            .joinToString("\n") { line -> WHITESPACE.replace(line, " ").trim() }
            .trim('\n')
    }

    private companion object {
        const val DEFAULT_TICK_RATE = 10_000.0
        const val DEFAULT_FRAME_RATE = 25.0
        const val DEFAULT_CUE_DURATION_MS = 2_000L

        val FRAME_CLOCK = Regex("""^(\d{1,4}):(\d{1,2}):(\d{1,2}):(\d{1,3})$""")
        val RGB = Regex("""^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$""")
        val WHITESPACE = Regex("""[ \t\r\u00A0]+""")
    }
}
