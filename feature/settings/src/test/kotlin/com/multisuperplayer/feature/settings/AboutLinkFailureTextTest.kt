package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.model.text.MspText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「关于」页打开外链失败时留下来的那句话。
 *
 * 这一条断言看着琐碎，守的却是一个真实的死角：整页只有一处会碰系统
 * （`startActivity`），失败的唯一出路就是**把地址还给用户**让他自己复制。
 * 如果哪天有人把 `%1$s` 从模板里删掉、或者顺手把 `url` 参数丢了，
 * 界面只会显示「无法打开浏览器：」——编译过、测试过、真机上也没崩，
 * 而用户拿着这句话无路可走。
 *
 * 另外两层在别处兜着：**[AboutViewModel.openLink] 里 `Intent` 那段分支在
 * 纯 JVM 单测里起不来**（`feature/settings` 没开 `unitTests.isReturnDefaultValues`，
 * 构造 `Intent` 会抛「not mocked」），所以这里只钉住判定逻辑；
 * 设备上到底有没有浏览器只能靠真机。
 */
class AboutLinkFailureTextTest {

    private val url = "https://github.com/Aaze-wu/MultiSuperPlayer/issues"

    @Test
    fun `成功时不留下任何提示`() {
        assertNull(linkFailureText(failure = null, url = url))
    }

    @Test
    fun `失败时给的提示必须是资源文案且带上地址`() {
        val text = linkFailureText(IllegalStateException("no browser"), url)

        val res = text as? MspText.Res ?: error("失败提示应当是资源文案，实际是 $text")
        assertEquals(R.string.msp_settings_about_link_failed, res.id)
        // 单独再确认参数：只比对整个对象的话，读者容易以为这里在比「同一个对象」，
        // 而真正要守的是「地址真的被传进去了」。
        assertEquals(listOf<Any?>(url), res.args)
    }

    @Test
    fun `三种语言的模板都给地址留了位置`() {
        // 模板里少了 `%1$s` 时上面那条断言照样绿——`args` 里确实有地址，
        // 只是界面上一个字都不会露出来。所以直接读三份资源文件核对。
        // 注意 `\$` ：Kotlin 字符串里不转义的话 `$s` 会被当成模板。
        assertEquals("无法打开浏览器：%1\$s", templateOf("values"))
        assertEquals("無法開啟瀏覽器：%1\$s", templateOf("values-b+zh+Hant"))
        assertEquals("Could not open a browser: %1\$s", templateOf("values-en"))
    }

    /** 读本模块某一语言的 `msp_settings_about_link_failed` 模板原文。 */
    private fun templateOf(locale: String): String {
        val file = File(repoRoot(), "feature/settings/src/main/res/$locale/strings.xml")
        assertTrue("找不到资源文件 $file", file.isFile)
        val match = Regex(
            """<string name="msp_settings_about_link_failed">(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(file.readText()) ?: error("$locale 里没有 msp_settings_about_link_failed")
        return match.groupValues[1]
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
}
