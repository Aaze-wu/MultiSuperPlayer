package com.multisuperplayer.core.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [UpdateVersion] 的顺序规则。
 *
 * 这一组用例守的是「检查更新会不会说谎」——比较写错的症状不是崩溃，
 * 而是**安静地**告诉用户「已是最新版本」，或者更糟：把一个更早的版本
 * 当成更新推给他。所以每条规则都有一个正例和一个反例。
 */
class UpdateVersionTest {

    private fun v(text: String): UpdateVersion =
        requireNotNull(UpdateVersion.parse(text)) { "解析不了：$text" }

    // ---------------------------------------------------------------- 编码一致

    @Test
    fun `versionCode 与构建脚本同一套编码`() {
        // 这几个数字直接决定「同数值段」的判断，必须与 app/build.gradle.kts 一致。
        assertEquals(608, v("0.6.8").versionCode)
        assertEquals(700, v("0.7.0").versionCode)
        assertEquals(800, v("0.8.0").versionCode)
        assertEquals(900, v("0.9.0").versionCode)
        // 1.0.0 跨了 major 位：10000，不是 1000。公式是 major*10000+minor*100+patch。
        assertEquals(10_000, v("1.0.0").versionCode)
    }

    @Test
    fun `预发行版与同段正式版共享 versionCode`() {
        // 这不是巧合而是既有设计（发版计划里写明了），也是「不能拿 versionCode
        // 比较新旧」的原因——下面那条用例给出后果。
        assertEquals(700, v("0.7.0-alpha.1").versionCode)
        assertEquals(700, v("0.7.0-alpha.2").versionCode)
    }

    // ---------------------------------------------------------------- 比较

    @Test
    fun `数字段按数值比而不是按字符串比`() {
        // 字符串比较会得出 "0.6.10" < "0.6.8"，于是 0.6.10 永远提示不了。
        assertTrue(v("0.6.10") > v("0.6.8"))
        assertTrue(v("0.6.10") > v("0.6.9"))
        assertTrue(v("0.10.0") > v("0.9.9"))
    }

    @Test
    fun `预发行版比同段正式版小`() {
        assertTrue(v("0.7.0") > v("0.7.0-alpha.1"))
        assertTrue(v("0.7.0-alpha.1") < v("0.7.0"))
        // 同时它仍然比上一段大：预发行不是「倒退」。
        assertTrue(v("0.7.0-alpha.1") > v("0.6.8"))
    }

    @Test
    fun `同数值段的两个预发行版能分出先后`() {
        // 这条是 versionCode 比较会失败的那个场景。
        assertTrue(v("0.7.0-alpha.2") > v("0.7.0-alpha.1"))
        assertTrue(v("0.7.0-alpha.10") > v("0.7.0-alpha.9"))
    }

    @Test
    fun `预发行标识符 数字小于字母 且前缀短的更小`() {
        assertTrue(v("1.0.0-alpha.1") < v("1.0.0-alpha.beta"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
    }

    @Test
    fun `相等版本互不小于`() {
        assertEquals(0, v("0.7.0").compareTo(v("0.7.0")))
        assertEquals(0, v("0.7.0-alpha.1").compareTo(v("0.7.0-alpha.1")))
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析宽容的写法`() {
        assertEquals(v("0.7.0"), v("v0.7.0"))
        assertEquals(v("0.7.0"), v("V0.7.0"))
        assertEquals(v("0.7.0"), v("0.7"))          // 缺补丁位补 0
        assertEquals(v("1.2.3"), v("1.2.3+build.7")) // 元数据不参与比较
        assertEquals(v("0.7.0-alpha.1"), v(" 0.7.0-alpha.1 "))
    }

    @Test
    fun `解析拒绝会算错顺序的写法`() {
        // 悬空连字符：如果当成正式版，它会排到所有同段预发行版前面。
        assertNull(UpdateVersion.parse("0.7.0-"))
        // 空标识符：'谁更长'没有定义。
        assertNull(UpdateVersion.parse("0.7.0-alpha..1"))
        // 非数字段。
        assertNull(UpdateVersion.parse("abc"))
        assertNull(UpdateVersion.parse("0.x.0"))
        // 只有一段：是版本号还是构建号说不清。
        assertNull(UpdateVersion.parse("7"))
        // 四段本项目从未使用。
        assertNull(UpdateVersion.parse("1.2.3.4"))
        assertNull(UpdateVersion.parse(""))
        assertNull(UpdateVersion.parse("v"))
    }

    @Test
    fun `前导 v 只在前缀后紧跟数字时才去掉`() {
        // 否则 "version" 会被吃成 "ersion" 变成垃圾版本号，
        // 而它「看起来解析成功了」的样子更难查。
        assertNull(UpdateVersion.parse("version"))
    }
}
