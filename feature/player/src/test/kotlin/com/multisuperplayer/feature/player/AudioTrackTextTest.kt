package com.multisuperplayer.feature.player

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.player.MspTrackInfo
import com.multisuperplayer.core.player.MspTrackKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音轨相关的文案：副标题（`zh · 2 声道 · 默认 · ac-3`）和芯片上的字（「国语」/「自动」）。
 *
 * ## 为什么这两条值得单独测
 *
 * 副标题是**同语言多条轨之间唯一的区分手段**：容器里两条轨都写着 `zh` 的时候，
 * 用户就是靠「2 声道 / 5.1 声道」和「默认」来决定点哪一行的。少报一个字段的症状是
 * 「列表里两行一模一样」，用户只能猜；顺序错了症状是「默认」跑到编解码后面，
 * 读起来不像一句话。
 *
 * 芯片上的字则要回答「现在**听的是谁**」——它写错（比如永远写「自动」）的症状是
 * 用户切完音轨后没法确认到底切成功没有，而那正是他这个动作唯一想确认的事。
 *
 * 断言方式是把它渲染成句子（按 `values/strings.xml` 真的拼出来），而不是断言
 * 「返回了某个 id 的 MspText」：前者能钉住顺序和分隔符，后者钉不住。
 */
class AudioTrackTextTest {

    // ---------------- describeAudioDetails ----------------

    @Test
    fun `语言未知时明说 而不是留一个空字段`() {
        // 留空会让副标题变成「 · 2 声道」，用户不知道第一个格子是什么。
        assertText(
            "未标语言",
            audio(language = null).describeAudioDetails(),
        )
    }

    @Test
    fun `语言按容器里的写法显示`() {
        // 和字幕候选行同一个口径（那边也直接写 `zh`）：两处显示方式不同的话，
        // 用户会以为「字幕的 zh」和「音轨的 中文」是两种不同的东西。
        assertText("zh", audio(language = "zh").describeAudioDetails())
    }

    @Test
    fun `声道 默认 编解码按这个顺序接在语言后面`() {
        assertText(
            "zh · 2 声道 · 默认 · ac-3",
            audio(language = "zh", channelCount = 2, isDefault = true, codec = "ac-3")
                .describeAudioDetails(),
        )
    }

    @Test
    fun `认不出来的编解码器不留尾巴`() {
        // 写「未知」会把这一行里最有用的语言/声道淹掉（编解码只有在意环绕格式的人看）。
        assertText(
            "zh · 6 声道",
            audio(language = "zh", channelCount = 6, codec = "   ").describeAudioDetails(),
        )
        assertText(
            "zh · 6 声道",
            audio(language = "zh", channelCount = 6, codec = null).describeAudioDetails(),
        )
    }

    @Test
    fun `只有一个字段时不带分隔符`() {
        // `MspText.join` 对单元素列表不做拼接；这条是防止有人把它改成「先加分隔符再拼」，
        // 那种实现会让每一项都以 `· ` 开头。
        val rendered = render(audio(language = "ja", channelCount = null, codec = "eac3").describeAudioDetails())
        assertEquals("ja${SEP}eac3", rendered)
        assertFalse("单个字段不该出现两次分隔符", render(audio(language = "ja").describeAudioDetails()).contains(SEP))
    }

    // ---------------- audioTrackChipLabel ----------------

    @Test
    fun `明确选中一条时芯片写那条轨的名字`() {
        val tracks = listOf(
            audio(id = "a1", label = "国语", isSelected = false),
            audio(id = "a2", label = "原声", isSelected = true),
        )
        assertEquals("原声", audioTrackChipLabel(tracks, fallbackOf = { "兜底" }, autoLabel = "自动"))
    }

    @Test
    fun `没有选中时芯片写自动`() {
        // 三种情况都落到这里，而界面对它们只有一种画法：还没解析出轨道、片源没有音频、
        // 以及多码率自适应（那时说「正在听第 2 条」是假的）。
        val none = listOf(audio(id = "a1"), audio(id = "a2"))
        assertEquals("自动", audioTrackChipLabel(none, fallbackOf = { "兜底" }, autoLabel = "自动"))

        val adaptive = listOf(audio(id = "a1", isSelected = true), audio(id = "a2", isSelected = true))
        assertEquals("自动", audioTrackChipLabel(adaptive, fallbackOf = { "兜底" }, autoLabel = "自动"))

        assertEquals("自动", audioTrackChipLabel(emptyList(), fallbackOf = { "兜底" }, autoLabel = "自动"))
    }

    @Test
    fun `没有名字的轨用兜底名 且编号取自那条轨自己的下标`() {
        // 兜底名必须走 `_n` 那条带占位符的资源（调用方传 `indexInGroup + 1`）：
        // 两种语言各自拼一遍编号，迟早会分叉。
        val tracks = listOf(
            audio(id = "a1", label = null, indexInGroup = 3),
            audio(id = "a2", label = null, indexInGroup = 4, isSelected = true),
        )
        val seen = mutableListOf<MspTrackInfo>()
        val label = audioTrackChipLabel(
            tracks,
            fallbackOf = { track ->
                seen += track
                "音轨 ${track.indexInGroup + 1}"
            },
            autoLabel = "自动",
        )
        // 用的是**选中那条**的下标（4 → 5），不是它在列表里的位置（1 → 2）。
        assertEquals("音轨 5", label)
        // 兜底回调只该为选中的那条轨调用：给整张列表调一遍的话，
        // 每次重组都要为用不到的轨算一遍名字。
        assertEquals(listOf("a2"), seen.map { it.id })
    }

    @Test
    fun `语言可以当名字用`() {
        val tracks = listOf(audio(id = "a1", language = "ja", isSelected = true))
        assertEquals("ja", audioTrackChipLabel(tracks, fallbackOf = { "兜底" }, autoLabel = "自动"))
    }

    // ---------------- 渲染器 ----------------

    /** 断言「用户会看到的那句话」。 */
    private fun assertText(expected: String, actual: MspText) {
        assertEquals(expected, render(actual))
    }

    /**
     * 分隔符本身也从资源里取：写死 ` · ` 的话，改标点时测试会红在一个和意图无关的地方。
     *
     * `by lazy` 是必须的，不是风格：它靠 [render] 渲染，而 [render] 依赖声明在它
     * **后面**的 [STRINGS]。写成普通属性会在构造函数里当场求值，那时后面的 lazy
     * 委托还是 null，结果是**整类测试**都报一个和被测代码毫无关系的 NPE。
     */
    private val SEP: String by lazy { render(SUBTITLE_DETAIL_SEPARATOR) }

    /** 把一棵 [MspText] 树按各模块 `values/strings.xml` 拼成一句话。 */
    private fun render(text: MspText): String = when (text) {
        is MspText.Plain -> text.text
        is MspText.Res -> fillIn(
            template = STRINGS[KEY_OF_ID[text.id] ?: error("认不出的资源 id ${text.id}")],
            args = text.args.map { arg -> if (arg is MspText) render(arg) else arg },
        )
    }

    private fun fillIn(template: String?, args: List<Any?>): String {
        val resolved = template ?: error("values/strings.xml 里没有这条")
        return INDEXED_ARG.replace(resolved) { match ->
            val index = match.groupValues[1].toInt()
            assertTrue("模板「$resolved」用了第 $index 个参数，但只给了 ${args.size} 个", index in 1..args.size)
            args[index - 1].toString()
        }
    }

    private val INDEXED_ARG = Regex("""%(\d+)\$[sd]""")

    /**
     * 资源 id → 键名：反射 `R.string` 的静态字段。
     *
     * 用反射而不是拄一份表：拄的表会随改名悄悄过期，而这里一改就会在 [render] 里报错。
     */
    private val KEY_OF_ID: Map<Int, String> by lazy {
        listOf(
            com.multisuperplayer.feature.player.R.string::class.java,
            com.multisuperplayer.core.common.R.string::class.java,
            // `msp_joined` 跟着 `MspText` 搬到了 core:model，漏掉它这一行就会
            // 直接报「认不出的资源 id」
            com.multisuperplayer.core.model.R.string::class.java,
        ).flatMap { resourceClass ->
            resourceClass.declaredFields.mapNotNull { field ->
                if (field.type != Int::class.java) return@mapNotNull null
                field.isAccessible = true
                (field.get(null) as? Int)?.takeIf { it != 0 }?.let { it to field.name }
            }
        }.toMap()
    }

    /** 键名 → 文本。音轨文案在本模块，`msp_joined` 在 core:model（`MspText` 的家）。 */
    private val STRINGS: Map<String, String> by lazy {
        listOf(
            "feature/player/src/main/res/values/strings.xml",
            "core/common/src/main/res/values/strings.xml",
            "core/model/src/main/res/values/strings.xml",
        ).flatMap { relative ->
            val file = File(repoRoot(), relative)
            assertTrue("找不到资源文件 $file", file.isFile)
            Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .map { match -> match.groupValues[1] to unquote(match.groupValues[2]) }
                .toList()
        }.toMap()
    }

    /** `"  ·  "` → ` · `（XML 里的引号只是为了保住首尾空格）。 */
    private fun unquote(raw: String): String =
        raw.removePrefix("\"").removeSuffix("\"")

    /** 测试是从模块目录里跑的，往上找到仓库根（有 `settings.gradle.kts` 那一层）。 */
    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("找不到仓库根目录（settings.gradle.kts）")
        }
        return dir
    }

    // ---------------- helpers ----------------

    private fun audio(
        id: String = "a",
        label: String? = null,
        language: String? = null,
        indexInGroup: Int = 1,
        channelCount: Int? = null,
        codec: String? = null,
        isSelected: Boolean = false,
        isDefault: Boolean = false,
    ) = MspTrackInfo(
        id = id,
        kind = MspTrackKind.AUDIO,
        label = label,
        language = language,
        mimeType = "audio/ac3",
        indexInGroup = indexInGroup,
        codec = codec,
        channelCount = channelCount,
        isSelected = isSelected,
        isDefault = isDefault,
    )
}
