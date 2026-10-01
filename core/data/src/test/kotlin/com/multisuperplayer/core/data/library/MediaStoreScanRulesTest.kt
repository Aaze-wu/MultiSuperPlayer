package com.multisuperplayer.core.data.library

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描纯规则的单元测试。
 *
 * 这些测试覆盖的是「肉眼看不出来」的失败：
 * 权限申请错版本 = 授权对话框不弹；标题回退少一层 = 列表里出现空标题；
 * partial 判错 = 界面把不完整的库当成完整的库来展示。
 */
class MediaStoreScanRulesTest {

    // ------------------------------------------------------------- 权限申请

    @Test
    fun `Android 14 及以上申请三个新权限`() {
        assertArrayEquals(
            arrayOf(
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            ),
            MediaStoreScanRules.requiredPermissions(34),
        )
        // 35 也得走同一条分支：这些权限名没有再变。
        assertArrayEquals(
            MediaStoreScanRules.requiredPermissions(34),
            MediaStoreScanRules.requiredPermissions(35),
        )
    }

    @Test
    fun `Android 13 不申请 14 才有的权限`() {
        assertArrayEquals(
            arrayOf(
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.READ_MEDIA_VIDEO",
            ),
            MediaStoreScanRules.requiredPermissions(33),
        )
    }

    @Test
    fun `Android 12 及以下回退到旧的粗粒度存储权限`() {
        assertArrayEquals(
            arrayOf("android.permission.READ_EXTERNAL_STORAGE"),
            MediaStoreScanRules.requiredPermissions(32),
        )
        assertArrayEquals(
            MediaStoreScanRules.requiredPermissions(32),
            MediaStoreScanRules.requiredPermissions(26),
        )
    }

    // ------------------------------------------------------------- partial

    @Test
    fun `只有用户勾选的视频时才算部分访问`() {
        assertTrue(
            MediaStoreScanRules.isPartialVisualAccess(
                sdkInt = 34,
                hasFullVideoPermission = false,
                hasUserSelectedVisualPermission = true,
            ),
        )
    }

    @Test
    fun `同时拿到完整视频权限时不算部分访问`() {
        // 用户先选了「部分」，后来又给了「全部」：那个 selected 权限还留着，
        // 但它已经不是事实上的限制了。这里判错会让界面永远挂一条
        // 「你可能看不到全部视频」的提示，而其实全都看得到。
        assertFalse(
            MediaStoreScanRules.isPartialVisualAccess(
                sdkInt = 34,
                hasFullVideoPermission = true,
                hasUserSelectedVisualPermission = true,
            ),
        )
    }

    @Test
    fun `Android 14 以下不存在部分视觉访问`() {
        assertFalse(
            MediaStoreScanRules.isPartialVisualAccess(
                sdkInt = 33,
                hasFullVideoPermission = false,
                hasUserSelectedVisualPermission = true,
            ),
        )
    }

    @Test
    fun `音频被拒但有视频时标记为不完整扫描`() {
        assertTrue(
            MediaStoreScanRules.isPartialScan(
                partialVisualAccess = false,
                hasAudioPermission = false,
                videoCount = 3,
            ),
        )
    }

    @Test
    fun `什么都没扫到时不算不完整`() {
        // 「一个都没有」和「只扫到一部分」是两种状态：前者要提示用户导入/授权，
        // 后者要提示可能有遗漏。混在一起会让提示文案永远对不上实际情况。
        assertFalse(
            MediaStoreScanRules.isPartialScan(
                partialVisualAccess = false,
                hasAudioPermission = false,
                videoCount = 0,
            ),
        )
    }

    @Test
    fun `权限齐全时扫描结果视为完整`() {
        assertFalse(
            MediaStoreScanRules.isPartialScan(
                partialVisualAccess = false,
                hasAudioPermission = true,
                videoCount = 12,
            ),
        )
    }

    // ------------------------------------------------------------- 标题回退

    @Test
    fun `优先使用 MediaStore 的标题`() {
        assertEquals("晴天", MediaStoreScanRules.deriveTitle("晴天", "track01.mp3"))
    }

    @Test
    fun `标题为空时用去掉扩展名的文件名`() {
        assertEquals("track01", MediaStoreScanRules.deriveTitle("", "track01.mp3"))
        assertEquals("track01", MediaStoreScanRules.deriveTitle(null, "track01.mp3"))
    }

    @Test
    fun `标题只有空白也算没有标题`() {
        assertEquals("track01", MediaStoreScanRules.deriveTitle("   ", "track01.mp3"))
    }

    @Test
    fun `没有扩展名的文件名原样使用`() {
        assertEquals("recording", MediaStoreScanRules.deriveTitle(null, "recording"))
    }

    @Test
    fun `扩展名在中间时只截最后一段`() {
        // substringBeforeLast 而不是 substringBefore：一个叫
        // 「2024.03.15 演唱会.mp4」的文件，按第一个点截会变成「2024」。
        assertEquals("2024.03.15 演唱会", MediaStoreScanRules.deriveTitle(null, "2024.03.15 演唱会.mp4"))
    }

    @Test
    fun `隐藏文件截完为空时回退到未知`() {
        assertEquals(MediaStoreScanRules.UNKNOWN_TITLE, MediaStoreScanRules.deriveTitle(null, ".mp3"))
        assertEquals(MediaStoreScanRules.UNKNOWN_TITLE, MediaStoreScanRules.deriveTitle("", "   "))
    }

    @Test
    fun `标题与文件名都缺失时给出未知占位`() {
        assertEquals(MediaStoreScanRules.UNKNOWN_TITLE, MediaStoreScanRules.deriveTitle(null, null))
    }

    // --------------------------------------------------------- 艺术家/专辑哨兵值

    @Test
    fun `正常的艺术家名原样保留`() {
        assertEquals("周杰伦", MediaStoreScanRules.normalizeTag("周杰伦"))
        assertEquals("Queen", MediaStoreScanRules.normalizeTag("Queen"))
    }

    @Test
    fun `MediaStore 的 unknown 占位被当作没有`() {
        // 真机上抓到的：WAV 文件没有艺术家信息时 MediaStore 返回的**不是** null，
        // 而是字面量 "<unknown>"，于是列表副标题显示成 "<unknown> · Music"。
        assertNull(MediaStoreScanRules.normalizeTag(MediaStoreScanRules.UNKNOWN_TAG))
        assertNull(MediaStoreScanRules.normalizeTag("<UNKNOWN>"))
    }

    @Test
    fun `空串与空白都算没有`() {
        // 必须归一成 null 而不是空串：UI 用 listOfNotNull 拼「艺术家 · 专辑」，
        // 给空串会拼出一个孤零零的「·」。
        assertNull(MediaStoreScanRules.normalizeTag(null))
        assertNull(MediaStoreScanRules.normalizeTag(""))
        assertNull(MediaStoreScanRules.normalizeTag("   "))
    }

    @Test
    fun `首尾空白被去掉`() {
        assertEquals("周杰伦", MediaStoreScanRules.normalizeTag("  周杰伦 "))
    }

    @Test
    fun `名字里出现尖括号但不是哨兵值的保留`() {
        assertEquals("<Various>", MediaStoreScanRules.normalizeTag("<Various>"))
        assertEquals("unknown", MediaStoreScanRules.normalizeTag("unknown"))
    }
}
