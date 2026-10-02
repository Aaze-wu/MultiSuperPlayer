package com.multisuperplayer.core.data.storage

/**
 * 「用分隔符把几个字段拼成一个字符串」这件事的公共实现。
 *
 * ## 为什么不能直接 `joinToString("|")`
 *
 * 因为写进 DataStore 的字段里有**用户给的自由文本**：SAF 目录名可以叫
 * `A|B`，媒体标题可以叫 `1\2`，而 [split] 之后再拆出来的字段数量和内容就全乱了。
 * 更糟的是这种损坏**不会抛异常**：它只是让「标题」变成「标题的前半段」，
 * 表现成界面上一个少了一半的名字——没人会想到去查存储层。
 *
 * 所以字段在拼接前一律转义，拆分时再还原。规则只有两条，反向唯一：
 * - `\` → `\\`
 * - `|` → `\|`
 *
 * ## 为什么不用 JSON
 *
 * 这个项目里的单测跑在 JVM 上，而 `org.json` 在 `android.jar` 里只有**空壳**：
 * `unitTests.isReturnDefaultValues = true` 会让它静默返回 null 而不是抛异常。
 * 也就是说用 JSON 存的东西**在单测里永远解析不出来**，而症状是「测试里所有
 * 记录都读不到」这种最难归因的失败。手写的转义反而是可测的那个选择。
 *
 * （`ResumeCodec` 用的是更土的 `位置:时间` 两数字格式，因为它只存两个数字、
 * 且 `:` 是它唯一的分隔符；那条路径不需要这套转义。）
 */
internal object FieldTextCodec {

    /** 字段分隔符。任何字段内容里的它都会被转义成 `\|`。 */
    const val SEPARATOR = '|'

    private const val ESCAPE = '\\'

    /** 按顺序拼接字段，每个字段先转义。 */
    fun join(fields: List<String>): String = fields.joinToString(SEPARATOR.toString()) { escape(it) }

    /**
     * 拆回字段。
     *
     * 不做「字段数量必须等于 N」的校验——那是调用方的事（每个 codec 自己知道
     * 期待几个字段），这里只负责**忠实地**切分。切分永远不会失败：一个末尾悬空的
     * `\` 当作字面反斜杠处理，因为「记录被写坏」这件事应该由调用方按语义判断
     * （比如时间戳解析不出来），而不是让拆分本身抛异常。
     */
    fun split(raw: String): List<String> {
        if (raw.isEmpty()) return listOf("")
        val fields = ArrayList<String>(4)
        val current = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            when {
                char == ESCAPE && index + 1 < raw.length -> {
                    // 转义序列：下一个字符无条件当字面量。
                    current.append(raw[index + 1])
                    index += 2
                }

                char == SEPARATOR -> {
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

    /** 转义一个字段。 */
    fun escape(value: String): String {
        if (value.none { it == ESCAPE || it == SEPARATOR }) return value
        val builder = StringBuilder(value.length + 4)
        for (char in value) {
            if (char == ESCAPE || char == SEPARATOR) builder.append(ESCAPE)
            builder.append(char)
        }
        return builder.toString()
    }

    /**
     * 还原**单个**字段。
     *
     * 单独暴露是因为有的键把 uri 直接当键名、把名字当值，那种值也需要还原。
     * 和 [split] 的区别：这里的分隔符**不**参与切分（值本来就只有一个字段），
     * 所以任何没有转义前缀的字符原样保留。
     */
    fun unescape(value: String): String {
        if (value.indexOf(ESCAPE) < 0) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == ESCAPE && index + 1 < value.length) {
                builder.append(value[index + 1])
                index += 2
            } else {
                builder.append(char)
                index += 1
            }
        }
        return builder.toString()
    }
}
