package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.log.LogSummary
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.settings.AspectRatioMode
import com.multisuperplayer.core.data.settings.PlaybackSettings
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.TranslationSettings
import java.io.File
import org.junit.Assert
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置首页那四行副标题。
 *
 * 这一页的全部价值就是「一眼看出当前是什么状态」，所以这些测试的重点**不是**正常值，
 * 而是各种脏数据：从未设置过（`null`）、认不出来的 id（降级安装 / 手改过配置）、
 * 两个开关互相覆盖、档位表里没有的值。这些情况在真机上都能发生，而写错任何一条，
 * 这一行就会理直气壮地骗人。
 *
 * 断言方式：`SettingsSummaries` 为了支持多语言，返回的不再是句子而是 [MspText]
 * （「哪一条资源 + 什么参数」），所以这里用 [render] 把它真的拼成用户会看到的那句话，
 * 断言的仍然是句子本身。理由：这一行的价值就在于「读起来对不对」，
 * 只断言 id 和参数的话，把「·」改成「,」这种错就逃得过去。
 */
class SettingsSummariesTest {

    // ------------------------------------------------------------------ 外观

    @Test
    fun `外观 - 从未设置过时显示默认主题与默认强调色`() {
        assertText(
            "跟随系统 · 靛蓝",
            SettingsSummaries.appearance(ThemeSettings(), systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 认不出来的 id 回退到默认值而不是崩掉`() {
        val theme = ThemeSettings(baseThemeId = "no-such-theme", accentId = "rose")

        // 「rose」是存在的（绯红），「no-such-theme」不存在 ⇒ 只有基底回退。
        assertText(
            "跟随系统 · 绯红",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 显式选过的基底与强调色都要显示出来`() {
        val theme = ThemeSettings(baseThemeId = "black", accentId = "teal")

        assertText(
            "纯黑（OLED） · 青碧",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 封面取色开启时说出来`() {
        val theme = ThemeSettings(colorFromArtwork = true)

        assertText(
            "跟随系统 · 靛蓝 · 封面取色",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 封面取色赢过系统取色, 两个都开也只说一个`() {
        val theme = ThemeSettings(useDynamicColor = true, colorFromArtwork = true)
        val text = SettingsSummaries.appearance(theme, systemColorSupported = true)

        // 同时说「封面取色」和「系统取色」是自相矛盾的：实际生效的只有一个。
        assertText("跟随系统 · 靛蓝 · 封面取色", text)
    }

    @Test
    fun `外观 - 系统取色真的生效时才这么说`() {
        val theme = ThemeSettings(useDynamicColor = true)

        assertText(
            "跟随系统 · 靛蓝 · 系统取色",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 系统不支持时明确说未生效`() {
        val theme = ThemeSettings(useDynamicColor = true)

        // 开关开着、颜色却是强调色 —— 不说出来的话，用户只能怀疑这个开关坏了。
        assertText(
            "跟随系统 · 靛蓝 · 系统取色未生效",
            SettingsSummaries.appearance(theme, systemColorSupported = false),
        )
    }

    @Test
    fun `外观 - 纯黑模式下系统取色未生效`() {
        val theme = ThemeSettings(baseThemeId = "black", useDynamicColor = true)

        // 即使系统支持（Android 12+）也要走这条分支：纯黑模式下系统取色会给出深灰，
        // 正好把「省像素」这件事毁掉，所以那条路是自己关掉的。
        assertText(
            "纯黑（OLED） · 靛蓝 · 系统取色未生效",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    @Test
    fun `外观 - 没打开系统取色时不提未生效`() {
        val theme = ThemeSettings(baseThemeId = "dark")

        // 用户自己就没想要系统取色，说「未生效」是在报告一个不存在的问题。
        assertText(
            "深色 · 靛蓝",
            SettingsSummaries.appearance(theme, systemColorSupported = true),
        )
    }

    // ------------------------------------------------------------------ 播放

    @Test
    fun `播放 - 全是默认值也显示同样三项`() {
        // 三项总是显示是有意的：只显示「非默认项」会让这一行时有时无，看起来像坏了。
        assertText(
            "1× · 长按 2× · 适应",
            SettingsSummaries.playback(PlaybackSettings(), softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 普通倍速如实显示, 长按倍速按档位归一`() {
        // 普通倍速是连续值（1.37× 真的是 1.37×，如实显示）；
        // 但长按倍速是**档位**：写进去时会夹进档位表，播放时也会再 normalize 一次
        // （见 PlayerViewModel），所以 2.5 实际执行的是 2×。
        // 这里必须显示 2×，否则这一行承诺的「长按 2.5×」和真实行为不一样。
        val settings = PlaybackSettings(speed = 1.37f, boostSpeed = 2.5f)

        assertText(
            "1.37× · 长按 2× · 适应",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 记不住的位置要变成不记位置`() {
        val settings = PlaybackSettings(rememberPosition = false)

        assertText(
            "1× · 长按 2× · 适应 · 不记位置",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 强制软解开启且可用时说出来`() {
        val settings = PlaybackSettings(forceSoftwareDecoding = true, aspectRatioMode = AspectRatioMode.CROP)

        assertText(
            "1× · 长按 2× · 裁剪 · 强制软解",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    @Test
    fun `播放 - 本包没有 FFmpeg 时不说强制软解, 只说没有 FFmpeg`() {
        val settings = PlaybackSettings(forceSoftwareDecoding = true)

        // 这一条是关键：开关在「播放」页里是灰的、根本没生效，
        // 摘要却写「强制软解」就是自相矛盾。
        assertText(
            "1× · 长按 2× · 适应 · 本包无 FFmpeg",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = false),
        )
    }

    @Test
    fun `播放 - 没开强制软解但本包没有 FFmpeg 时也要说`() {
        // 「没有 FFmpeg」是安装包的事实，和开关无关：用户反馈「这个格式放不了」时，
        // 这一行是第一个需要被排除的原因。
        assertText(
            "1× · 长按 2× · 适应 · 本包无 FFmpeg",
            SettingsSummaries.playback(PlaybackSettings(), softwareDecodingAvailable = false),
        )
    }

    @Test
    fun `播放 - 长按倍速为 null 时用默认值而不是显示零`() {
        val settings = PlaybackSettings(boostSpeed = null)

        assertText(
            "1× · 长按 2× · 适应",
            SettingsSummaries.playback(settings, softwareDecodingAvailable = true),
        )
    }

    // ------------------------------------------------------------------ 翻译

    @Test
    fun `翻译 - 什么都没填时逐项列出缺什么`() {
        // 「还不够」必须写清楚差什么：这一行是用户在播放页翻不出字幕时唯一会看的地方。
        assertText(
            "DeepSeek · 还不能翻译（缺：服务地址、模型名、API 密钥）",
            SettingsSummaries.translation(TranslationSettings()),
        )
    }

    @Test
    fun `翻译 - 填够了才显示模型和目标语言`() {
        val settings = TranslationSettings(
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-v3",
            apiKeyStored = true,
        )

        assertText("DeepSeek · deepseek-v3 · 译成简体中文", SettingsSummaries.translation(settings))
    }

    @Test
    fun `翻译 - 地址写错时说的是地址, 不是一句笼统的未配置`() {
        val settings = TranslationSettings(
            baseUrl = "api.deepseek.com",
            model = "deepseek-v3",
            apiKeyStored = true,
        )

        assertText(
            "DeepSeek · 还不能翻译（缺：服务地址（需要以 http:// 或 https:// 开头））",
            SettingsSummaries.translation(settings),
        )
    }

    @Test
    fun `翻译 - 不需要密钥的服务商不该把密钥列进缺失项`() {
        val settings = TranslationSettings(
            providerId = "ollama",
            baseUrl = "http://127.0.0.1:11434",
            model = "qwen2.5",
        )

        assertText("Ollama（本地） · qwen2.5 · 译成简体中文", SettingsSummaries.translation(settings))
    }

    // ------------------------------------------------------------------ 文件访问

    @Test
    fun `文件访问 - 已开启时只说已开启`() {
        assertText(
            "已开启，可以浏览任意文件夹",
            SettingsSummaries.fileAccess(supported = true, granted = true),
        )
    }

    @Test
    fun `文件访问 - 没开启时说的是去开启而不是一句不能浏览`() {
        assertText(
            "未开启，点这里去系统设置开启",
            SettingsSummaries.fileAccess(supported = true, granted = false),
        )
    }

    @Test
    fun `文件访问 - 系统不支持时说的不是未开启`() {
        // 三种状态里最容易写歪的就是这一条：「系统太旧」和「用户没开」在界面上
        // 一个是灰的、一个是能点的。合成一句「未开启」等于让用户去找一个不存在的
        // 开关，而这种情况下的 `granted` 恒为 false（系统根本不会给我们这个权限）。
        assertText("本机系统版本不支持", SettingsSummaries.fileAccess(supported = false, granted = false))
        assertText("本机系统版本不支持", SettingsSummaries.fileAccess(supported = false, granted = true))
    }

    // ------------------------------------------------------------------ 关于

    @Test
    fun `关于 - 有版本号时显示版本号`() {
        assertText("0.5.4 (50400)", SettingsSummaries.about(buildInfo()))
    }

    @Test
    fun `关于 - 构建脚本没注入版本号时显示未知而不是空白`() {
        // 空白行看起来像「这一页还没做完」，而「未知」说明是构建脚本的问题。
        assertText("未知", SettingsSummaries.about(AppBuildInfo.Unknown))
    }

    // ------------------------------------------------------------------ 日志

    @Test
    fun `日志 - 列出文件数量与总体积`() {
        val summary = LogSummary(
            fileCount = 3,
            totalBytes = 1024L * 1024L,
            oldestDay = "2026-10-01",
            newestDay = "2026-10-03",
        )

        // 体积那一段交给 TimeFormat 自己格式化（两位有效数字、单位换算），
        // 这里只钉住「数量 · 体积」这个形状：少了分隔号整行会变成一坨数字。
        val rendered = render(SettingsSummaries.logFiles(summary))

        assertTrue(rendered.startsWith("3 个 · "))
        assertTrue(rendered.endsWith("MB"))
    }

    @Test
    fun `日志 - 一个文件都没有时明说而不是显示零`() {
        // 「0 个 · 0 B」看起来像统计出错；事实是「这个安装包还没产生过日志」，
        // 而这一点直接决定了用户要不要去点「导出日志」。
        val text = render(SettingsSummaries.logFiles(LogSummary.Empty))

        assertText("还没有任何日志文件", SettingsSummaries.logFiles(LogSummary.Empty))
        assertFalse(text.contains("0 个"))
    }

    @Test
    fun `日志 - 只有一天时只显示一天`() {
        // 写成 `2026-10-02 ~ 2026-10-02` 会被读成「这中间还有几天」，
        // 用户会以为日志丢过。
        val summary = LogSummary(1, 2048L, "2026-10-02", "2026-10-02")

        assertText("2026-10-02", SettingsSummaries.logRange(summary))
    }

    @Test
    fun `日志 - 跨天时显示区间`() {
        val summary = LogSummary(4, 2048L, "2026-10-02", "2026-10-05")

        assertText("2026-10-02 ~ 2026-10-05", SettingsSummaries.logRange(summary))
    }

    @Test
    fun `日志 - 日期读不到时给个占位符而不是空白`() {
        // 空白行在关于页里读起来像「还没加载完」，而事实上永远不会加载出来了。
        assertText("—", SettingsSummaries.logRange(LogSummary(2, 2048L, null, null)))
        // 只丢了一头时，有的那头仍然要说出来。
        assertText("2026-10-02", SettingsSummaries.logRange(LogSummary(2, 2048L, "2026-10-02", null)))
    }

    // ------------------------------------------------------------------ 资源

    @Test
    fun `英文资源里不能残留中文`() {
        // 「界面能切英文」这件事最容易在这里破功：漏翻一两句，切过去才发现。
        val offenders = settingsStrings("values-en")
            .filterValues { value -> value.any { it.isCjk() } }
            .keys

        Assert.assertEquals("这些键的英文文案里还有中日韩字符", emptySet<String>(), offenders)
    }

    @Test
    fun `三种语言的关键字集合完全一致`() {
        val base = settingsStrings("values").keys

        Assert.assertEquals("英文少了键", base, settingsStrings("values-en").keys)
        Assert.assertEquals("繁体少了键", base, settingsStrings("values-b+zh+Hant").keys)
    }

    // ===== 下面是给上面那些断言用的工具 =====

    /**
     * 断言「用户会看到的那句话」。
     *
     * 名字故意不叫 `assertEquals`：它比原样比较多做了一件事——按 `values/strings.xml`
     * 把 [MspText] 真的拼出来。用 JUnit 的名字会让读者以为这里只是原样比对象。
     */
    private fun assertText(expected: String, actual: MspText) {
        Assert.assertEquals(expected, render(actual))
    }

    /** 把一棵 [MspText] 树按 `values/strings.xml` 拼成一句话。 */
    private fun render(text: MspText): String = when (text) {
        is MspText.Plain -> text.text
        is MspText.Res -> fillIn(lookup(text.id), text.args.map { arg -> if (arg is MspText) render(arg) else arg })
    }

    private val INDEXED_ARG = Regex("""%(\d+)\$[sd]""")
    private val BARE_ARG = Regex("""%[sd]""")

    /** 按 Android 的规则填 `%1$s` / `%2$d`（以及不带序号的 `%s`）。 */
    private fun fillIn(template: String, args: List<Any?>): String {
        var next = 0
        val indexed = INDEXED_ARG.replace(template) { match ->
            val index = match.groupValues[1].toInt()
            assertTrue("模板「$template」用了第 $index 个参数，但只给了 ${args.size} 个", index in 1..args.size)
            args[index - 1].toString()
        }
        return BARE_ARG.replace(indexed) {
            val value = args.getOrNull(next++)
            assertTrue("模板「$template」的参数不够填", value != null)
            value.toString()
        }
    }

    /** 资源 id → 键名。JVM 单测里拿不到 `Resources`，而 `MspText.Res` 里只有 id。 */
    private fun lookup(id: Int): String {
        val key = RESOURCE_KEYS[id] ?: error("认不出的资源 id $id：R.string 反射表里没有它")
        return STRINGS[key] ?: error("values/strings.xml 里没有 $key")
    }

    private val RESOURCE_DIRS = listOf(
        "feature/settings/src/main/res/values",
        "core/ui/src/main/res/values",
        "core/common/src/main/res/values",
        "core/translate/src/main/res/values",
        "core/data/src/main/res/values",
        "core/player/src/main/res/values",
    )

    /**
     * 把这几个模块的基线文案合起来查。
     *
     * 副标题会拼到别的模块的资源（主题名在 core:ui、缺项名在 core:translate、
     * 连接符在 core:common），所以渲染器不能只看本模块。键名前缀不重叠，合并不歧义。
     */
    private val STRINGS: Map<String, String> by lazy {
        RESOURCE_DIRS.flatMap { dir -> readResource(dir).entries }.associate { it.key to it.value }
    }

    /**
     * 资源 id → 键名：把每个模块 `R.string` 的静态字段反射出来。
     *
     * 用反射而不是拄一份表：拄的表会随改名惄惄过期，而这里一改就会在 [lookup] 报错，
     * 不会静默把整行渲染成空串。
     */
    private val RESOURCE_KEYS: Map<Int, String> by lazy {
        listOf(
            com.multisuperplayer.feature.settings.R.string::class.java,
            com.multisuperplayer.core.ui.R.string::class.java,
            com.multisuperplayer.core.common.R.string::class.java,
            com.multisuperplayer.core.translate.R.string::class.java,
            com.multisuperplayer.core.data.R.string::class.java,
            com.multisuperplayer.core.player.R.string::class.java,
        ).flatMap { resourceClass ->
            resourceClass.declaredFields.mapNotNull { field ->
                if (field.type != Int::class.java) return@mapNotNull null
                field.isAccessible = true
                // id 为 0 说明 R 类是空壳，这时应该让 lookup 报错，
                // 而不是把这一行渲染成空串、静默通过。
                (field.get(null) as? Int)?.takeIf { it != 0 }?.let { it to field.name }
            }
        }.toMap()
    }

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    /** 本模块某一语言的 `strings.xml`：键 → 文本。 */
    private fun settingsStrings(locale: String): Map<String, String> =
        readResource("feature/settings/src/main/res/$locale")

    /** 读某个语言的 `strings.xml`：键 → 文本。 */
    private fun readResource(dir: String): Map<String, String> {
        val file = File(repoRoot(), "$dir/strings.xml")
        assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to unquote(it.groupValues[2]) }
    }

    /**
     * 模拟 aapt2 对文本的处理：首尾空白会被裁掉，除非值被一对双引号包住
     * （Android 文档里的「保留空白」写法），那时被裁掉的是引号本身。
     *
     * 少了这一步，测试读到的是 XML 原文，`> · <` 在设备上被烫成 `·` 这类
     * 只能靠眼睛看出来的问题就永远漏掉了。
     */
    private fun unquote(raw: String): String =
        if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            raw.substring(1, raw.length - 1)
        } else {
            raw.trim()
        }

    /** 从测试的工作目录往上找仓库根（认得 `settings.gradle.kts`）。 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根目录（settings.gradle.kts）")
    }

    private fun buildInfo() = AppBuildInfo(
        versionName = "0.5.4",
        versionCode = 50400,
        gitCommit = "09b06a6",
        gitTag = "v0.5.3",
        gitDirty = false,
        buildTimeText = "2026-10-02 15:30 +08:00",
    )
}
