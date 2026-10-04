package com.multisuperplayer.feature.settings

import android.Manifest
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.permissions.PermissionKind
import com.multisuperplayer.core.data.permissions.PermissionRules
import com.multisuperplayer.core.data.permissions.PermissionSnapshot
import com.multisuperplayer.core.data.permissions.PermissionState
import java.io.File
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限页每行的说法，以及设置入口页那一行的副标题。
 *
 * 这一页的价值全在「一眼看出这一项现在什么状态、下一步该点哪里」，所以这些测试
 * 断言的是**用户会读到的那句话**：`PermissionSummaries` 为了多语言返回的是
 * [MspText]（哪条资源 + 什么参数），这里用 [render] 真的拼成句子再比。
 * 只断言 id 的话，「已允许」改成「已授予」这种错一个字都不会被发现。
 *
 * 六种状态里，真机上最容易漏验的是 [PermissionState.ASK_AGAIN] 与
 * [PermissionState.GO_TO_SETTINGS]（要把同一项权限拒绝两次、其中一次勾上
 * 「不再询问」），以及对所有状态都得有一句话——因为少写一句的后果是**空白**，
 * 而空白在设置页里读起来像「还没加载完」。
 */
class PermissionSummariesTest {

    // ------------------------------------------------------------------ 入口行

    @Test
    fun `入口摘要 - 媒体已允许时说没问题`() {
        assertText(
            "已允许读取媒体库",
            PermissionSummaries.entry(snapshot(media = PermissionState.GRANTED)),
        )
    }

    @Test
    fun `入口摘要 - 只允许了一部分时要说出来`() {
        // 「只允许了音频」如果显示成「已允许读取媒体库」，用户会以为视频也能看了，
        // 然后发现视频列表是空的。
        assertText(
            "只允许了一部分媒体库",
            PermissionSummaries.entry(snapshot(media = PermissionState.PARTIAL)),
        )
    }

    @Test
    fun `入口摘要 - 从未申请过时说缺权限`() {
        assertText(
            "未允许读取媒体库",
            PermissionSummaries.entry(snapshot(media = PermissionState.NOT_ASKED)),
        )
    }

    @Test
    fun `入口摘要 - 被拒绝过也说缺权限, 不在这行写第二套词`() {
        // 这一行只回答「要不要进去处理一下」，两个状态都要处理，所以同一句话。
        // 「还能再申请」还是「只能去设置」由权限页里的那一行去区分。
        assertText(
            "未允许读取媒体库",
            PermissionSummaries.entry(snapshot(media = PermissionState.GO_TO_SETTINGS)),
        )
        assertText(
            "未允许读取媒体库",
            PermissionSummaries.entry(snapshot(media = PermissionState.ASK_AGAIN)),
        )
    }

    @Test
    fun `入口摘要 - 只看媒体那一项`() {
        // 「所有文件访问」「通知」「蓝牙」都是可选能力，**没允许就是默认状态**。
        // 把它们汇总进来，每个用户都会看到「4 项里有 3 项未允许」，
        // 进去一看三项都是默认值 —— 这行字就成了噪音。
        assertText(
            "已允许读取媒体库",
            PermissionSummaries.entry(
                PermissionSnapshot(
                    media = PermissionState.GRANTED,
                    allFiles = PermissionState.NOT_ASKED,
                    notification = PermissionState.NOT_ASKED,
                    bluetooth = PermissionState.NOT_ASKED,
                ),
            ),
        )
    }

    @Test
    fun `入口摘要 - 媒体没允许时另外三项都允许了也不算没问题`() {
        // 反向也要钉住：媒体是唯一「不处理就会被误解成扫描坏了」的一项。
        assertText(
            "未允许读取媒体库",
            PermissionSummaries.entry(
                PermissionSnapshot(
                    media = PermissionState.NOT_ASKED,
                    allFiles = PermissionState.GRANTED,
                    notification = PermissionState.GRANTED,
                    bluetooth = PermissionState.GRANTED,
                ),
            ),
        )
    }

    @Test
    fun `入口摘要 - 从未算过状态时按缺权限说而不是按已允许说`() {
        // `PermissionSnapshot()` 是全默认值，界面在第一次刷新之前读到的就是它。
        // 默认值如果落到「已允许」，冷启动的第一帧会先骗一次人。
        assertText("未允许读取媒体库", PermissionSummaries.entry(PermissionSnapshot()))
    }

    // -------------------------------------------------------------- 状态的说法

    @Test
    fun `状态 - 六种状态各有各的说法, 一格都不能空`() {
        val expected = mapOf(
            PermissionState.GRANTED to "已允许",
            PermissionState.PARTIAL to "只允许了一部分",
            PermissionState.NOT_ASKED to "未开启，可以在这里申请",
            PermissionState.ASK_AGAIN to "未开启，可以再申请一次",
            PermissionState.GO_TO_SETTINGS to "已被拒绝",
            PermissionState.UNSUPPORTED to "本机系统版本不支持",
        )

        // 三项都用同一套词，所以每一项都过一遍：将来给蓝牙（或者通知）加特例时，
        // 漏掉一个 kind 会在这里被抓住。
        listOf(PermissionKind.MEDIA, PermissionKind.NOTIFICATION, PermissionKind.BLUETOOTH).forEach { kind ->
            expected.forEach { (state, text) ->
                assertText("$kind / $state 的说法不对", text, PermissionSummaries.state(kind, state))
            }
        }
    }

    @Test
    fun `状态 - 所有文件访问沿用文件访问那三句`() {
        // 这三句已经写进 README 第 7 节，换掉会让文档和界面各说一套。
        assertText(
            "已开启，可以浏览任意文件夹",
            PermissionSummaries.state(PermissionKind.ALL_FILES, PermissionState.GRANTED),
        )
        assertText(
            "未开启，点这里去系统设置开启",
            PermissionSummaries.state(PermissionKind.ALL_FILES, PermissionState.NOT_ASKED),
        )
    }

    @Test
    fun `状态 - 所有文件访问在旧系统上说不支持而不是说未开启`() {
        // 30 以下根本没有这个权限，说「未开启，点这里去系统设置开启」
        // 会让用户去一个不存在的开关那里找。
        assertText(
            "本机系统版本不支持",
            PermissionSummaries.state(PermissionKind.ALL_FILES, PermissionState.UNSUPPORTED),
        )
    }

    @Test
    fun `状态 - 所有文件访问被拒绝时也说未开启, 不借用别人的已被拒绝`() {
        // 这一项的六态里只有 GRANTED 与 UNSUPPORTED 有意义，其余都归到「未开启」：
        // 它从来不能被「拒绝」（系统不给弹框），所以「已被拒绝」在这里是句假话。
        assertText(
            "未开启，点这里去系统设置开启",
            PermissionSummaries.state(PermissionKind.ALL_FILES, PermissionState.GO_TO_SETTINGS),
        )
    }

    // ---------------------------------------------------------------- 动作词

    @Test
    fun `动作 - 本机没有这一项时不给按钮`() {
        // 给一个灰按钮仍然在说「这里有个动作，只是现在不行」，
        // 而事实是这个系统里**永远**不会有。
        PermissionKind.entries.forEach { kind ->
            Assert.assertNull("$kind 不支持时不该有按钮", PermissionSummaries.action(kind, PermissionState.UNSUPPORTED))
        }
    }

    @Test
    fun `动作 - 所有文件访问永远只有去设置`() {
        listOf(
            PermissionState.NOT_ASKED,
            PermissionState.ASK_AGAIN,
            PermissionState.GO_TO_SETTINGS,
        ).forEach { state ->
            assertText(
                "$state 时所有文件访问的按钮",
                "去设置",
                PermissionSummaries.action(PermissionKind.ALL_FILES, state) ?: MspText.Plain("<没有按钮>"),
            )
        }
    }

    @Test
    fun `动作 - 还能弹系统框时说申请`() {
        listOf(PermissionKind.MEDIA, PermissionKind.NOTIFICATION, PermissionKind.BLUETOOTH).forEach { kind ->
            listOf(PermissionState.NOT_ASKED, PermissionState.ASK_AGAIN, PermissionState.PARTIAL).forEach { state ->
                assertText(
                    "$kind / $state 的按钮",
                    "申请",
                    PermissionSummaries.action(kind, state) ?: MspText.Plain("<没有按钮>"),
                )
            }
        }
    }

    @Test
    fun `动作 - 只能去系统里改的时候说去设置`() {
        listOf(PermissionKind.MEDIA, PermissionKind.NOTIFICATION, PermissionKind.BLUETOOTH).forEach { kind ->
            assertText(
                "$kind 被系统记住拒绝后的按钮",
                "去设置",
                PermissionSummaries.action(kind, PermissionState.GO_TO_SETTINGS)
                    ?: MspText.Plain("<没有按钮>"),
            )
        }
    }

    @Test
    fun `动作 - 已经允许时只剩去设置关掉`() {
        // 已经允许了就没有「申请」这个动作了；但这一项仍然可能想关掉，
        // 所以给「去设置」而不是留白。
        assertText(
            "去设置",
            PermissionSummaries.action(PermissionKind.NOTIFICATION, PermissionState.GRANTED)
                ?: MspText.Plain("<没有按钮>"),
        )
    }

    // ------------------------------------------------------------------ 标题

    @Test
    fun `标题 - 所有文件访问那一行仍然叫文件访问`() {
        assertText("文件访问", PermissionSummaries.title(PermissionKind.ALL_FILES))
    }

    @Test
    fun `标题 - 四项的标题互不相同`() {
        // 两行标题一样的话，用户没法在问号里认出哪一行说的是哪个权限。
        val titles = PermissionKind.entries.map { kind -> render(PermissionSummaries.title(kind)) }

        Assert.assertEquals("四行标题有重复", titles.size, titles.toSet().size)
    }

    // -------------------------------------------------------------- 折叠说明区

    @Test
    fun `折叠区 - 七条权限各有标签`() {
        val expected = mapOf(
            Manifest.permission.INTERNET to "网络访问",
            Manifest.permission.ACCESS_NETWORK_STATE to "查看网络状态",
            Manifest.permission.FOREGROUND_SERVICE to "前台服务",
            Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK to "媒体播放前台服务",
            Manifest.permission.WAKE_LOCK to "保持唤醒",
            Manifest.permission.MODIFY_AUDIO_SETTINGS to "更改音频设置",
            // 用系统设置里那一项的名字，用户好对照（见 PermissionSummaries.otherLabel）。
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to "忽略电池优化",
        )

        expected.forEach { (permission, text) ->
            assertText(permission, text, PermissionSummaries.otherLabel(permission))
        }
    }

    @Test
    fun `折叠区 - 认不出来的权限原样显示而不是空白`() {
        // 有人加了权限却忘了加标签时，界面会露出 `android.permission.XXX`：
        // 难看，但**看得见**。显示成空串就会变成「折叠区少了一行」这种没人发现的错。
        val unknown = "android.permission.SOME_FUTURE_PERMISSION"

        assertText(unknown, PermissionSummaries.otherLabel(unknown))
    }

    @Test
    fun `折叠区 - 顺序与清单对照表一致, 不在这里另排一遍`() {
        Assert.assertEquals(PermissionRules.OTHER_PERMISSIONS, PermissionSummaries.otherPermissions)
    }

    @Test
    fun `折叠区 - 可更改的四项不该出现在折叠区里`() {
        val listed = PermissionRules.LISTED_PERMISSIONS.toSet()

        PermissionSummaries.otherPermissions.forEach { permission ->
            Assert.assertFalse("$permission 是可更改项，不该同时在折叠区", permission in listed)
        }
    }

    // ------------------------------------------------------------ 可更改项本身

    @Test
    fun `可更改项 - 顺序来自 PermissionKind 声明顺序`() {
        // 在权限页里再排一遍的话，加第五项时必然漏掉一处，而那时界面是安静的。
        Assert.assertEquals(PermissionKind.entries.toList(), PermissionSummaries.changeable)
    }

    @Test
    fun `可更改项 - 恰好是四项且有顺序`() {
        Assert.assertEquals(
            listOf(
                PermissionKind.MEDIA,
                PermissionKind.ALL_FILES,
                PermissionKind.NOTIFICATION,
                PermissionKind.BLUETOOTH,
            ),
            PermissionSummaries.changeable,
        )
    }

    @Test
    fun `可更改项 - 每一项的标题和说明都不是空串`() {
        PermissionSummaries.changeable.forEach { kind ->
            Assert.assertTrue("$kind 的标题是空的", render(PermissionSummaries.title(kind)).isNotBlank())
            Assert.assertTrue("$kind 的说明是空的", render(PermissionSummaries.description(kind)).isNotBlank())
        }
    }

    // ------------------------------------------------------------------ 资源

    @Test
    fun `资源 - 权限页新增的键三种语言都有非空文案`() {
        listOf("values", "values-en", "values-b+zh+Hant").forEach { locale ->
            val strings = settingsStrings(locale)
            PERMISSION_KEYS.forEach { key ->
                val value = strings[key]
                Assert.assertNotNull("$locale 里少了 $key", value)
                Assert.assertTrue("$locale 里的 $key 是空的", value!!.isNotBlank())
            }
        }
    }

    @Test
    fun `资源 - 繁体文案里真的是中文而不是抄过来的简体`() {
        // 繁体那一套是手写的，最容易出现的错是整条从基线复制过来没改。
        val offenders = PERMISSION_KEYS.filterNot { key ->
            settingsStrings("values-b+zh+Hant")[key]?.any { it.isCjk() } == true
        }

        Assert.assertEquals("这些键的繁体文案里没有汉字", emptyList<String>(), offenders)
    }

    // ===== 下面是给上面那些断言用的工具 =====

    private fun snapshot(media: PermissionState) = PermissionSnapshot(media = media)

    /**
     * 断言「用户会看到的那句话」。
     *
     * 名字故意不叫 `assertEquals`：它比原样比较多做了一件事——按 `values/strings.xml`
     * 把 [MspText] 真的拼出来。
     */
    private fun assertText(expected: String, actual: MspText) {
        Assert.assertEquals(expected, render(actual))
    }

    private fun assertText(what: String, expected: String, actual: MspText) {
        Assert.assertEquals(what, expected, render(actual))
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
        val filled = BARE_ARG.replace(indexed) {
            val value = args.getOrNull(next++)
            assertTrue("模板「$template」的参数不够填", value != null)
            value.toString()
        }
        // Android 的字符串资源里百分号要写成 `%%`，真正取文案时由 aapt2 收成一个 `%`。
        return filled.replace("%%", "%")
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
        // 连接符（`msp_joined`）跟着 `MspText` 住在 core:model
        "core/model/src/main/res/values",
        "core/data/src/main/res/values",
    )

    /**
     * 把这几个模块的基线文案合起来查。
     *
     * 副标题会拼到别的模块的资源（连接符在 core:common、主题名在 core:ui），
     * 所以渲染器不能只看本模块。键名前缀不重叠，合并不歧义。
     */
    private val STRINGS: Map<String, String> by lazy {
        RESOURCE_DIRS.flatMap { dir -> readResource(dir).entries }.associate { it.key to it.value }
    }

    /**
     * 资源 id → 键名：把每个模块 `R.string` 的静态字段反射出来。
     *
     * 用反射而不是抄一份表：抄的表会随改名悄悄过期，而这里一改就会在 [lookup] 报错。
     */
    private val RESOURCE_KEYS: Map<Int, String> by lazy {
        listOf(
            com.multisuperplayer.feature.settings.R.string::class.java,
            com.multisuperplayer.core.ui.R.string::class.java,
            com.multisuperplayer.core.common.R.string::class.java,
            com.multisuperplayer.core.model.R.string::class.java,
            com.multisuperplayer.core.data.R.string::class.java,
        ).flatMap { resourceClass ->
            resourceClass.declaredFields.mapNotNull { field ->
                if (field.type != Int::class.java) return@mapNotNull null
                field.isAccessible = true
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
     * 模拟 aapt2 对文本的处理：首尾空白会被裁掉，除非值被一对双引号包住。
     *
     * 少了这一步，测试读到的是 XML 原文，而设备上显示的是裁过的。
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

    private companion object {
        /**
         * 权限页与入口摘要用到的新键。
         *
         * 这里故意**只列新键**：老的那几句（`msp_settings_file_access_*`）由
         * `SettingsSummariesTest` 管着，两份测试各管一片，谁也不用背对方的清单。
         */
        val PERMISSION_KEYS = listOf(
            "msp_settings_permissions",
            "msp_settings_summary_permissions_ok",
            "msp_settings_summary_permissions_partial",
            "msp_settings_summary_permissions_missing",
            "msp_permissions_section_changeable",
            "msp_permissions_media",
            "msp_permissions_media_desc",
            "msp_permissions_all_files_desc",
            "msp_permissions_notification",
            "msp_permissions_notification_desc",
            "msp_permissions_bluetooth",
            "msp_permissions_bluetooth_desc",
            "msp_permissions_state_granted",
            "msp_permissions_state_partial",
            "msp_permissions_state_not_asked",
            "msp_permissions_state_ask_again",
            "msp_permissions_state_denied",
            "msp_permissions_state_unsupported",
            "msp_permissions_action_request",
            "msp_permissions_action_settings",
            "msp_permissions_other_header",
            "msp_permissions_other_note",
            "msp_permissions_other_internet",
            "msp_permissions_other_network_state",
            "msp_permissions_other_foreground_service",
            "msp_permissions_other_foreground_service_media_playback",
            "msp_permissions_other_wake_lock",
            "msp_permissions_other_modify_audio_settings",
            "msp_permissions_other_ignore_battery_optimizations",
        )
    }
}
