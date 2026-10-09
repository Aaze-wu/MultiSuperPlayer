/**
 * 本地绝对路径 ⇄ `file://` uri。**编码与解码必须成对使用。**
 *
 * ## 为什么不能直接拼字符串
 *
 * `"file://$path"` 这种写法在普通文件名上看不出问题，遇上下面两类字符会**静默**出错，
 * 而且坏在两个相反的方向上：
 *
 * | 字符 | `Uri.parse("file://…")` 之后的解释 | 症状 |
 * |---|---|---|
 * | `#` | 后面的内容变成 **fragment**，不再属于路径 | 路径被截短 ⇒ 文件明明在，播放器说打不开 |
 * | `%` | 被当成 **百分号转义的开始** | 后两个字符不是合法十六进制就是非法转义 ⇒ `FileNotFoundException: Invalid file path` |
 *
 * Android 的 `Uri` 在**取值**时（`getPath()`、`ParcelFileDescriptor.open`）会做一次百分号
 * 解码，所以只要**写入**时把危险字符编码掉，读出来就是原路径。这就是这个文件的全部职责。
 * 路径里的中文与空格不编码其实也能用，编码了更稳（部分 provider 与内核解析器对非 ASCII
 * 更挑剔），代价只是 uri 串变长。
 *
 * ## 为什么不用 `Uri.fromFile`
 *
 * 它做的事完全正确（内部的 `PathPart.fromDecoded` 会编码），但它是 Android API：JVM 单测里
 * 开着 `unitTests.isReturnDefaultValues`，`Uri.fromFile` 直接返回 `null`，于是这条规则会变成
 * **测不到**的东西——而它恰恰只在这些边角字符上才有意义。这里的实现是纯 Kotlin，
 * 因此 `#`、`%`、空格的行为可以在单测里钉住。
 *
 * ## 为什么要能反解
 *
 * [localFilePath] 给「已经拿着 uri、但要按文件系统办事」的调用方用（列目录、`File.length()`）。
 * 两个函数互为逆运算，`LocalPathUriTest` 用往返断言钉住这一点。
 */
package com.multisuperplayer.core.model

import java.io.ByteArrayOutputStream

private const val FILE_SCHEME = "file://"

private const val URI_SCHEME_SEPARATOR = "://"

/**
 * uri 里能原样出现的**标点**：RFC 3986 的 unreserved 与 sub-delims，加上路径分隔符和冒号。
 * 字母与数字不算在这个常量里（见 [isUriSafe]）——把它们一个个抄进来最容易漏，
 * 而漏一个的症状是路径被编成一串 `%XX`（看着像正常工作，只是没人认得出来）。
 *
 * `\` 也放进来：它只出现在 Windows 路径里（单测跑在 Windows 上），不编码它可以让
 * 「往返一致」这条规则在所有平台上都成立，代价是 uri 里可能出现反斜杠——而反斜杠
 * 在 uri 路径段里本来就是合法字符，不会引起误解释。
 */
private const val SAFE_PUNCTUATION = "-._~!$&'()*+,;=:@/\\"

private const val HEX_DIGITS = "0123456789ABCDEF"

/** 字母、数字与 [SAFE_PUNCTUATION] 里的字符可以原样出现在 uri 里，其余一律百分号编码。 */
private fun isUriSafe(ch: Char): Boolean =
    ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || SAFE_PUNCTUATION.indexOf(ch) >= 0

/**
 * 把一条**本地绝对路径**变成可以交给 `Uri.parse` 的 `file://` uri。
 *
 * **已经带 scheme 的输入原样返回**（`content://`、`http://`、本来就是 `file://` 的）。
 * 调用方不需要先判断自己手上是路径还是 uri：文件浏览器给的是裸路径，手动选字幕给的是
 * SAF uri，两条路都直接调这里。
 *
 * 唯一的代价：磁盘上真有一个文件名里含 `://`（`a://b.mp4` 在 ext4 上是合法的）时会被
 * 当成「已经带 scheme」而不编码。这种名字比它带来的麻烦罕见得多，不值得为它加分支。
 */
fun localFileUri(path: String): String {
    if (path.contains(URI_SCHEME_SEPARATOR)) return path
    val out = StringBuilder(path.length + FILE_SCHEME.length)
    out.append(FILE_SCHEME)
    var index = 0
    while (index < path.length) {
        val codePoint = path.codePointAt(index)
        val width = Character.charCount(codePoint)
        // 安全字符都在 BMP 内且是单字符；代理对（emoji 之类）一律走编码分支，
        // 按半个 Char 取字节会把它们拆成两个替换字符。
        if (width == 1 && isUriSafe(codePoint.toChar())) {
            out.append(codePoint.toChar())
        } else {
            appendPercentEncoded(out, codePoint)
        }
        index += width
    }
    return out.toString()
}

/**
 * 从 `file://` uri 或裸绝对路径里取回磁盘路径。
 *
 * - `file://…` ⇒ 解码百分号转义后返回；
 * - 不带 scheme 的输入 ⇒ **原样**返回（它本来就是路径，再解释一次只会出错）；
 * - 别的 scheme（`content://`、`http://`）⇒ `null`：**猜出来的路径会去操作别的文件**，
 *   宁可让调用方老老实实说「这不是文件系统里的东西」。
 */
fun localFilePath(uri: String): String? {
    if (uri.isEmpty()) return null
    if (!uri.contains(URI_SCHEME_SEPARATOR)) return uri
    if (!uri.startsWith(FILE_SCHEME, ignoreCase = true)) return null
    return percentDecoded(uri.substring(FILE_SCHEME.length)).takeIf { it.isNotEmpty() }
}

/** 按 UTF-8 把 [codePoint] 的每个字节写成 `%XX`（大写十六进制，与 `Uri` 的输出一致）。 */
private fun appendPercentEncoded(out: StringBuilder, codePoint: Int) {
    val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
    for (byte in bytes) {
        val value = byte.toInt() and 0xFF
        out.append('%')
        out.append(HEX_DIGITS[value shr 4])
        out.append(HEX_DIGITS[value and 0xF])
    }
}

/**
 * 解开 `%XX`。**遇到不合法的转义就原样留下那个字符**，不抛异常也不吞字符：
 * 调用方可能拿着一个手工拼过、只有一半编码的 uri，那种情况下「尽量还原」比
 * 「整条失败」有用得多——最坏的结果是找不到文件，而那正是它本来就有的结果。
 */
private fun percentDecoded(text: String): String {
    if ('%' !in text) return text
    val bytes = ByteArrayOutputStream(text.length)
    var index = 0
    while (index < text.length) {
        val high = if (text[index] == '%' && index + 2 < text.length) hexValue(text[index + 1]) else -1
        val low = if (high >= 0) hexValue(text[index + 2]) else -1
        if (high >= 0 && low >= 0) {
            bytes.write((high shl 4) or low)
            index += 3
        } else {
            bytes.write(text[index].toString().toByteArray(Charsets.UTF_8))
            index++
        }
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

private fun hexValue(ch: Char): Int = when (ch) {
    in '0'..'9' -> ch - '0'
    in 'a'..'f' -> ch - 'a' + 10
    in 'A'..'F' -> ch - 'A' + 10
    else -> -1
}
