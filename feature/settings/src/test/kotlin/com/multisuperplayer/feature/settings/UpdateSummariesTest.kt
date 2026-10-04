package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.update.UpdateAvailability
import com.multisuperplayer.core.data.update.UpdateChannel
import com.multisuperplayer.core.data.update.UpdateFailureText
import com.multisuperplayer.core.data.update.UpdateRelease
import com.multisuperplayer.core.data.update.UpdateVersion
import java.io.File
import org.junit.Assert
import org.junit.Test

/**
 * 「检查更新」页的每一句摘要。
 *
 * 这一页要说清三组互相独立的事，测试盯的正是这三组的**分界**：
 *
 * - 四种检查状态各说各的。合成一句兜底串的后果在别的地方也许只是文案差，
 *   在这里是「已忽略 v0.6.8」被说成「已是最新版本」——用户以为没更新，
 *   而那个版本其实是**被他自己忽略掉的**，撤销入口就再也不会被想到。
 * - 八种失败各有自己的话。这一页上「网络不可达」和「签名不符」要用户做的事
 *   完全不同（前者重试，后者**不要装**），套用一句「检查更新失败」会把
 *   「别装这个包」这条唯一的警告淹掉。
 * - 令牌有没有填是两句不同的话。填了还说「未填写」会让用户再填一次，
 *   而 GitHub 的令牌是只显示一次的。
 *
 * 另外这一组还有资源本身的三条：三套语言齐 / 繁体不是抄的 / 英文里没有汉字。
 */
class UpdateSummariesTest {

    // ------------------------------------------------------------ 状态

    @Test
    fun `状态 - 四种状态各一句`() {
        val release = release(tag = "v0.7.0-alpha.1")

        Assert.assertEquals(
            idOf(UpdateSummaries.status(UpdateAvailability.NotChecked)),
            R.string.msp_update_status_not_checked,
        )
        Assert.assertEquals(
            idOf(UpdateSummaries.status(UpdateAvailability.UpToDate)),
            R.string.msp_update_status_up_to_date,
        )
        Assert.assertEquals(
            idOf(UpdateSummaries.status(UpdateAvailability.Available(release))),
            R.string.msp_update_status_available,
        )
        Assert.assertEquals(
            idOf(UpdateSummaries.status(UpdateAvailability.Ignored(release))),
            R.string.msp_update_status_ignored,
        )
    }

    @Test
    fun `状态 - 有新版本和已忽略说的不是同一句`() {
        // 「已忽略」比「已是最新」更需要一句自己的话：只有它能让用户想起
        // 「我当初是自己忽略掉的」，而撤销入口在页面下方。
        val release = release(tag = "v0.7.0-alpha.1")

        Assert.assertNotEquals(
            idOf(UpdateSummaries.status(UpdateAvailability.Available(release))),
            idOf(UpdateSummaries.status(UpdateAvailability.Ignored(release))),
        )
    }

    @Test
    fun `状态 - 有版本号的那两句把版本号带进去了`() {
        val release = release(tag = "v0.7.0-alpha.1")

        listOf(
            UpdateSummaries.status(UpdateAvailability.Available(release)),
            UpdateSummaries.status(UpdateAvailability.Ignored(release)),
        ).forEach { text ->
            val args = (text as MspText.Res).args
            Assert.assertEquals(
                "这两句必须带上版本号，否则用户看不出忽略的是哪一版",
                listOf<Any?>("v0.7.0-alpha.1"),
                args,
            )
        }
    }

    @Test
    fun `状态行 - 把状态和当前版本接在一句里`() {
        // 接在一句里而不是分两行：这一行要回答的是同一个问题
        // （「我该不该按左边那个按钮」）。
        Assert.assertEquals(
            MspText.Res(
                // `msp_joined` 在 core:model 里（`MspText.join` 的实现与它的源码同住），
                // 跨模块只能写全名——本文件的 `R` 是 feature:settings 自己的那个。
                com.multisuperplayer.core.model.R.string.msp_joined,
                listOf(
                    MspText.Res(R.string.msp_update_status_not_checked),
                    MspText.Plain(" · "),
                    MspText.Res(R.string.msp_update_current_version_value, listOf("0.7.0-alpha.1")),
                ),
            ),
            UpdateSummaries.statusLine(UpdateAvailability.NotChecked, "0.7.0-alpha.1"),
        )
    }

    // ------------------------------------------------------------ 通道

    @Test
    fun `通道 - 名字和说明各有两句`() {
        Assert.assertEquals(
            idOf(UpdateSummaries.channel(UpdateChannel.STABLE)),
            R.string.msp_update_channel_stable,
        )
        Assert.assertEquals(
            idOf(UpdateSummaries.channel(UpdateChannel.BETA)),
            R.string.msp_update_channel_beta,
        )
        Assert.assertEquals(
            idOf(UpdateSummaries.channelDescription(UpdateChannel.STABLE)),
            R.string.msp_update_channel_stable_desc,
        )
        Assert.assertEquals(
            idOf(UpdateSummaries.channelDescription(UpdateChannel.BETA)),
            R.string.msp_update_channel_beta_desc,
        )
    }

    @Test
    fun `通道 - 两个通道的说明不是同一句`() {
        Assert.assertNotEquals(
            idOf(UpdateSummaries.channelDescription(UpdateChannel.STABLE)),
            idOf(UpdateSummaries.channelDescription(UpdateChannel.BETA)),
        )
    }

    @Test
    fun `通道 - 每个枚举项都有名字和说明`() {
        // 枚举加一项而忘了写文案时，`when` 会编译不过；但「文案写成了上面那一条的」
        // 编译得过，界面上表现为两个选项长得一模一样——那等于多了一个没用的选项。
        val ids = UpdateChannel.entries.map { channel ->
            listOf(idOf(UpdateSummaries.channel(channel)), idOf(UpdateSummaries.channelDescription(channel)))
        }.flatten()

        Assert.assertEquals(
            "有两个通道项共用同一条文案",
            ids.size,
            ids.toSet().size,
        )
    }

    // ------------------------------------------------------------ 令牌

    @Test
    fun `令牌 - 填了没填各一句`() {
        Assert.assertEquals(idOf(UpdateSummaries.token(true)), R.string.msp_update_token_set)
        Assert.assertEquals(idOf(UpdateSummaries.token(false)), R.string.msp_update_token_unset)
    }

    // ------------------------------------------------------------ 失败

    @Test
    fun `失败 - 八种失败各有自己的话`() {
        val expected = mapOf(
            UpdateFailureText.Network to R.string.msp_update_failure_network,
            UpdateFailureText.RateLimited to R.string.msp_update_failure_rate_limited,
            UpdateFailureText.NotFound to R.string.msp_update_failure_not_found,
            UpdateFailureText.ServerError to R.string.msp_update_failure_server_error,
            UpdateFailureText.NoAsset to R.string.msp_update_failure_no_asset,
            UpdateFailureText.DownloadCorrupted to R.string.msp_update_failure_corrupted,
            UpdateFailureText.SignatureMismatch to R.string.msp_update_failure_signature,
            UpdateFailureText.NoInstaller to R.string.msp_update_failure_no_installer,
        )
        val actual = UpdateFailureText.entries.associateWith { failure ->
            idOf(UpdateSummaries.failure(failure))
        }

        Assert.assertEquals(expected, actual)
        // 光比 map 抓不到「两条指向同一句话」：那时 map 仍然相等，
        // 只有数 id 的**个数**才能发现。八种失败共用一句的后果，
        // 是「签名不符，请不要装」被稀释成一句谁都看不懂的通用错误。
        Assert.assertEquals("有两种失败用了同一条文案", expected.size, actual.values.toSet().size)
    }

    // ------------------------------------------------------------ 入口页那一行

    @Test
    fun `入口摘要 - 写的是当前版本号`() {
        val buildInfo = AppBuildInfo(
            versionName = "0.7.0-alpha.1",
            versionCode = 700,
            gitCommit = "",
            gitTag = "",
            gitDirty = false,
            buildTimeText = "",
            versionChannel = "alpha",
        )

        Assert.assertEquals(
            MspText.Res(R.string.msp_update_current_version_value, listOf("0.7.0-alpha.1")),
            UpdateSummaries.entry(buildInfo),
        )
    }

    // ------------------------------------------------------------ 资源本身

    @Test
    fun `资源 - 这些键在三套文案里都在`() {
        val missing = LOCALES.flatMap { locale ->
            val strings = settingsStrings(locale)
            UPDATE_KEYS.filterNot { key -> strings.containsKey(key) }.map { key -> "$locale/$key" }
        }

        Assert.assertEquals("这些键在某套文案里缺失", emptyList<String>(), missing)
    }

    @Test
    fun `资源 - 更新页的资源键和这份清单一一对应`() {
        val declared = settingsStrings("values").keys
            .filter { key -> key.startsWith("msp_update_") }
            .toSet()
        val listed = UPDATE_KEYS.filter { key -> key.startsWith("msp_update_") }.toSet()

        Assert.assertEquals(
            "values/strings.xml 里多出来的更新键：写了没人用，或者忘了登记进这份清单",
            emptySet<String>(),
            declared - listed,
        )
        Assert.assertEquals(
            "这份清单里多出来的键：values/strings.xml 里没有对应的字符串",
            emptySet<String>(),
            listed - declared,
        )
    }

    @Test
    fun `资源 - 繁体文案里真的是中文而不是抄过来的简体`() {
        val offenders = UPDATE_KEYS.filterNot { key ->
            settingsStrings("values-b+zh+Hant")[key]?.any { char -> char.isCjk() } == true
        }

        Assert.assertEquals("这些键的繁体文案里没有汉字", emptyList<String>(), offenders)
    }

    @Test
    fun `资源 - 英文文案里没有汉字`() {
        val offenders = UPDATE_KEYS.filter { key ->
            settingsStrings("values-en")[key]?.any { char -> char.isCjk() } == true
        }

        Assert.assertEquals("这些键的英文文案里有汉字", emptyList<String>(), offenders)
    }

    // ===== 下面是给上面那些断言用的工具 =====

    private fun idOf(text: MspText): Int = when (text) {
        is MspText.Res -> text.id
        is MspText.Plain -> error("这一页的文案都该来自资源，不该是硬编码的「${text.text}」")
    }

    private fun Char.isCjk(): Boolean =
        code in 0x3000..0x303F || code in 0x4E00..0x9FFF || code in 0xFF00..0xFFEF

    private fun release(tag: String) = UpdateRelease(
        tagName = tag,
        version = UpdateVersion(0, 7, 0, listOf("alpha", "1")),
        isPreRelease = true,
        publishedAtEpochMs = null,
        notes = null,
        apkUrl = "https://example.invalid/app-release.apk",
        apkSizeBytes = 1024L,
        apkSha256 = null,
    )

    /** 本模块某一语言的 `strings.xml`：键 → 文本。 */
    private fun settingsStrings(locale: String): Map<String, String> {
        val file = File(repoRoot(), "feature/settings/src/main/res/$locale/strings.xml")
        Assert.assertTrue("找不到资源文件 $file", file.isFile)
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { match -> match.groupValues[1] to match.groupValues[2].trim() }
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
        val LOCALES = listOf("values", "values-en", "values-b+zh+Hant")

        /**
         * 更新页用到的全部资源键。
         *
         * 这份清单是**登记表**，不是「测试关心的子集」：多一个或少一个都会被
         * `资源 - 更新页的资源键和这份清单一一对应` 抓到。写在这里的价值是
         * 新增文案的人必须来登记一次，顺带就会被上面几条按状态/失败的断言覆盖到。
         */
        val UPDATE_KEYS = setOf(
            "msp_settings_update",
            "msp_update_current_version_value",
            "msp_update_status_not_checked",
            "msp_update_status_up_to_date",
            "msp_update_status_available",
            "msp_update_status_ignored",
            "msp_update_channel",
            "msp_update_channel_stable",
            "msp_update_channel_beta",
            "msp_update_channel_stable_desc",
            "msp_update_channel_beta_desc",
            "msp_update_auto_check",
            "msp_update_auto_check_desc",
            "msp_update_token",
            "msp_update_token_set",
            "msp_update_token_unset",
            "msp_update_token_note",
            "msp_update_token_label",
            "msp_update_token_add",
            "msp_update_token_change",
            "msp_update_token_clear",
            "msp_update_failure_network",
            "msp_update_failure_rate_limited",
            "msp_update_failure_not_found",
            "msp_update_failure_server_error",
            "msp_update_failure_no_asset",
            "msp_update_failure_corrupted",
            "msp_update_failure_signature",
            "msp_update_failure_no_installer",
            "msp_update_failure_dismiss",
            "msp_update_section_current",
            "msp_update_section_available",
            "msp_update_section_settings",
            "msp_update_check_now",
            "msp_update_check_action",
            "msp_update_checking",
            "msp_update_available_version",
            "msp_update_notes_empty",
            "msp_update_notes_expand",
            "msp_update_notes_collapse",
            "msp_update_download_and_install",
            "msp_update_download_progress",
            "msp_update_download_unknown_total",
            "msp_update_ignore",
            "msp_update_undo_ignore",
            "msp_update_need_unknown_source",
            "msp_update_open_unknown_source",
            "msp_update_startup_now",
            "msp_update_startup_later",
            "msp_update_startup_hint",
        )
    }
}
