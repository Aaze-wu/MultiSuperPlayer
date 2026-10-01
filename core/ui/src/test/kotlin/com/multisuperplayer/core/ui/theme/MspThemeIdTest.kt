package com.multisuperplayer.core.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题枚举的 id 解析测试。
 *
 * 为什么值得测：这两个枚举的 id 是**写进用户设备的字符串**。解析函数一旦
 * 写错（抛异常、大小写敏感、认不出来时返回 null），表现都是「主题在重启后
 * 变回默认」或者「一打开设置页就崩」——两种都属于只有在真机上才会被发现，
 * 而这里几毫秒就能锁住。
 */
class MspThemeIdTest {

    @Test
    fun `每个主题基底的 id 都能被解析回自己`() {
        MspBaseTheme.entries.forEach { theme ->
            assertEquals(theme, MspBaseTheme.fromId(theme.id))
        }
    }

    @Test
    fun `每个强调色的 id 都能被解析回自己`() {
        MspAccent.entries.forEach { accent ->
            assertEquals(accent, MspAccent.fromId(accent.id))
        }
    }

    @Test
    fun `id 不重复`() {
        // 重复的 id 会让其中一个预设永远无法被存储表示出来：存进去读回来
        // 永远是另一个。这类错误肉眼看不出来（列表里两个选项都在），
        // 但用户会发现「选了这个却变成那个」。
        assertEquals(MspBaseTheme.entries.size, MspBaseTheme.entries.map { it.id }.toSet().size)
        assertEquals(MspAccent.entries.size, MspAccent.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `未设置时回退到默认值`() {
        assertEquals(MspBaseTheme.DEFAULT, MspBaseTheme.fromId(null))
        assertEquals(MspAccent.DEFAULT, MspAccent.fromId(null))
    }

    @Test
    fun `认不出来的 id 回退而不是抛异常`() {
        // 场景：降级安装、手改过配置文件、以后删掉某个预设。主题这种东西
        // 绝不能因为一个字符串对不上就崩，最差就是回到默认主题。
        assertEquals(MspBaseTheme.DEFAULT, MspBaseTheme.fromId("no-such-theme"))
        assertEquals(MspAccent.DEFAULT, MspAccent.fromId("no-such-accent"))
        assertEquals(MspBaseTheme.DEFAULT, MspBaseTheme.fromId(""))
        assertEquals(MspAccent.DEFAULT, MspAccent.fromId(""))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(MspBaseTheme.BLACK, MspBaseTheme.fromId("BLACK"))
        assertEquals(MspAccent.TEAL, MspAccent.fromId("Teal"))
    }

    @Test
    fun `跟随系统被视为深色以外的分支`() {
        // isDark 只用来「在没有系统信息时兜底」；FOLLOW_SYSTEM 的实际明暗
        // 由 isSystemInDarkTheme() 决定，所以它不能等于 LIGHT。
        assertFalse(MspBaseTheme.LIGHT.isDark)
        assertTrue(MspBaseTheme.DARK.isDark)
        assertTrue(MspBaseTheme.BLACK.isDark)
    }
}
