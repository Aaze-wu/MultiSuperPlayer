package com.multisuperplayer.core.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 授权目录展示名的单元测试。
 *
 * 解错的表现是「浏览页里那个目录叫 `primary:`」或者「叫一长串 id」——
 * 功能没问题，但用户不知道该点哪一个。
 */
class SafTreeLabelRulesTest {

    @Test
    fun `普通目录取最后一段`() {
        assertEquals("Music", SafTreeLabelRules.labelOf("primary:Music"))
        assertEquals("Album", SafTreeLabelRules.labelOf("primary:Music/Album"))
        // 有些 provider 的 documentId 带结尾斜杠。
        assertEquals("Album", SafTreeLabelRules.labelOf("primary:Music/Album/"))
    }

    @Test
    fun `内置存储根目录没有名字返回 null`() {
        // 界面会把它换成「内置存储」这句可翻译的文案——数据层不能出文案。
        assertNull(SafTreeLabelRules.labelOf("primary:"))
        // 没有冒号就不是 ExternalStorageProvider 的卷根形式，按不透明 id 处理：
        // 宁可显示一个怪名字，也不要凭一个字符串猜出「这是内置存储根目录」。
        assertEquals("primary", SafTreeLabelRules.labelOf("primary"))
    }

    @Test
    fun `外置存储的卷根用卷名当标签`() {
        // SD 卡的卷名（ABCD-1234）本身就是有信息量的名字，比「根目录」有用。
        assertEquals("ABCD-1234", SafTreeLabelRules.labelOf("ABCD-1234:"))
    }

    @Test
    fun `外置存储的子目录取最后一段`() {
        assertEquals("Download", SafTreeLabelRules.labelOf("ABCD-1234:Download"))
    }

    @Test
    fun `非 ExternalStorageProvider 的 id 原样当标签`() {
        // 云盘 provider 的 documentId 形式自定义（比如就是一串 id）。
        // 硬解只会解出一段没意义的字符，而它至少还能和别的区分开。
        assertEquals("1AbC-xyz_9999", SafTreeLabelRules.labelOf("1AbC-xyz_9999"))
    }

    @Test
    fun `空字符串不抛异常`() {
        assertNull(SafTreeLabelRules.labelOf(""))
    }

    @Test
    fun `中文目录名原样保留`() {
        assertEquals("我的音乐", SafTreeLabelRules.labelOf("primary:我的音乐"))
        assertEquals("电影", SafTreeLabelRules.labelOf("primary:媒体/电影"))
    }
}
