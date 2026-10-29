package com.multisuperplayer.core.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「媒体库只显示选中的文件夹」规则的单元测试。
 *
 * 这里每一条都在防一个**在界面上看不出来、但用户一眼就能看出来**的失败：
 * 路径规范化漏一次 ⇒ 勾了没用；覆盖判定写错 ⇒ 勾 `Music` 却看不到 `Music/Live`；
 * 取消勾选时忘了展开祖先 ⇒ 勾不了也别想取消；空选集当成「没有限制」⇒ 说好的
 * 「都不显示」变成「全都显示」。
 */
class LibraryFolderRulesTest {

    // --------------------------------------------------------------- 路径规范化

    @Test
    fun `结尾斜杠统一去掉`() {
        // MediaStore 的 RELATIVE_PATH 与 SafScanRules.relativePathOf 都带结尾斜杠，
        // 而偏好里存的是不带斜杠的形式。这个折点只有一处，写错就全盘失效。
        assertEquals("Music/Album", LibraryFolderRules.folderPathOf("Music/Album/"))
        assertEquals("Music", LibraryFolderRules.folderPathOf("Music/"))
        assertEquals("Music", LibraryFolderRules.folderPathOf("Music"))
        assertEquals("Music/Album", LibraryFolderRules.folderPathOf("  Music/Album/  "))
    }

    @Test
    fun `卷根是空串，算不出来是 null`() {
        // 这两个必须分开：空串表示「文件就在存储根」，null 表示「不知道在哪」。
        // 折成一个值的话，根目录下的文件会被当成算不出来而被丢掉。
        assertEquals("", LibraryFolderRules.folderPathOf(""))
        assertEquals("", LibraryFolderRules.folderPathOf("/"))
        assertNull(LibraryFolderRules.folderPathOf(null))
    }

    @Test
    fun `祖先列表从卷根开始`() {
        assertEquals(listOf<String>(), LibraryFolderRules.ancestorsOf(""))
        assertEquals(listOf(""), LibraryFolderRules.ancestorsOf("Music"))
        assertEquals(listOf("", "Music"), LibraryFolderRules.ancestorsOf("Music/Live"))
        assertEquals(
            listOf("", "A", "A/B"),
            LibraryFolderRules.ancestorsOf("A/B/C"),
        )
    }

    // --------------------------------------------------------------- 覆盖判定

    @Test
    fun `父覆盖子，但卷根只覆盖自己`() {
        assertTrue(LibraryFolderRules.covers("Music", "Music"))
        assertTrue(LibraryFolderRules.covers("Music", "Music/Live"))
        assertTrue(LibraryFolderRules.covers("Music", "Music/Live/2024"))
        // 前缀相同但不是子目录：把 "Music2" 当成 "Music" 的子目录会让取消勾选失效。
        assertFalse(LibraryFolderRules.covers("Music", "Music2"))
        assertFalse(LibraryFolderRules.covers("Music/Live", "Music"))
        // 卷根「包含子文件夹」等于「全部显示」，那不是我们给的单行语义：
        // 它只代表「文件就在存储根」这一种位置。
        assertTrue(LibraryFolderRules.covers("", ""))
        assertFalse(LibraryFolderRules.covers("", "Music"))
    }

    // --------------------------------------------------------------- 过滤

    @Test
    fun `空选集一条都不显示`() {
        // 产品决定：默认「都不显示」。把空集当成「没有限制」会让这个功能完全失效，
        // 而且失效得毫无痕迹——用户以为设了偏好，其实什么都没发生。
        assertFalse(LibraryFolderRules.accepts("Music/", emptySet()))
        assertFalse(LibraryFolderRules.accepts("", emptySet()))
        assertFalse(LibraryFolderRules.accepts(null, emptySet()))
    }

    @Test
    fun `选中父文件夹时子文件夹里的条目也进库`() {
        val selected = setOf("Music")
        assertTrue(LibraryFolderRules.accepts("Music/", selected))
        assertTrue(LibraryFolderRules.accepts("Music/Album/", selected))
        assertTrue(LibraryFolderRules.accepts("Music/Album/2024/", selected))
        assertFalse(LibraryFolderRules.accepts("Movies/", selected))
        assertFalse(LibraryFolderRules.accepts("Music2/", selected))
        assertFalse(LibraryFolderRules.accepts("", selected))
    }

    @Test
    fun `只选卷根时只有根目录的文件进库`() {
        val selected = setOf("")
        assertTrue(LibraryFolderRules.accepts("", selected))
        assertFalse(LibraryFolderRules.accepts("Music/", selected))
    }

    @Test
    fun `算不出相对路径的条目一律排除`() {
        // 两个真实来源：API 26-28 上 MediaStore 没有 RELATIVE_PATH 列；
        // 非 ExternalStorageProvider 的 SAF docId 里没有冒号。选严格是因为宽松会让
        // 「已取消勾选的目录里的文件照样出现」，那种 bug 用户能看见却无法解释。
        assertFalse(LibraryFolderRules.accepts(null, setOf("Music")))
        assertFalse(LibraryFolderRules.accepts(null, setOf("")))
    }

    // --------------------------------------------------------------- 最小化

    @Test
    fun `最小化去掉被覆盖的冗余项`() {
        assertEquals(
            setOf("Music"),
            LibraryFolderRules.minimize(setOf("Music", "Music/Live", "Music/Live/2024")),
        )
        // 卷根谁都不覆盖（除了自己），不能把它当冗余删掉。
        assertEquals(
            setOf("", "Music"),
            LibraryFolderRules.minimize(setOf("", "Music", "Music/Live")),
        )
        assertEquals(emptySet<String>(), LibraryFolderRules.minimize(emptySet()))
    }

    // --------------------------------------------------------------- 勾选

    @Test
    fun `勾选一个文件夹会顺手带走它的后代`() {
        val result = LibraryFolderRules.toggle(
            selected = emptySet(),
            target = "Music",
            allFolders = listOf("", "Music", "Music/Live", "Movies"),
        )
        assertEquals(setOf("Music"), result)
    }

    @Test
    fun `取消勾选自己就整支关掉`() {
        val result = LibraryFolderRules.toggle(
            selected = setOf("Music"),
            target = "Music",
            allFolders = listOf("", "Music", "Music/Live", "Movies"),
        )
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `取消被祖先覆盖的子文件夹时要把祖先展开`() {
        // 这是整个勾选逻辑里唯一的坑：勾了 Music 之后想单独去掉 Music/Live。
        // 只删 Music 会把 Music/Album 一起关掉；不删又会因为 Music 还覆盖着
        // Music/Live 而「取消不掉」。正确结果 = 去掉 Music，显式留下别的子文件夹。
        val result = LibraryFolderRules.toggle(
            selected = setOf("Music"),
            target = "Music/Live",
            allFolders = listOf("", "Music", "Music/Album", "Music/Live", "Movies"),
        )
        assertEquals(setOf("Music/Album"), result)
        assertFalse(LibraryFolderRules.accepts("Music/Live/", result))
        assertTrue(LibraryFolderRules.accepts("Music/Album/", result))
    }

    @Test
    fun `展开祖先时深层后代由最小化收拾`() {
        val result = LibraryFolderRules.toggle(
            selected = setOf("Music"),
            target = "Music/Live",
            allFolders = listOf("Music", "Music/Album", "Music/Album/2024", "Music/Live/2024"),
        )
        // Music/Album/2024 被 Music/Album 覆盖，不该单独留在集合里。
        assertEquals(setOf("Music/Album"), result)
    }

    @Test
    fun `文件夹清单为空时取消勾选退化成整支关掉`() {
        assertEquals(
            emptySet<String>(),
            LibraryFolderRules.toggle(setOf("Music"), "Music/Live", emptyList()),
        )
    }

    @Test
    fun `全选折成最小形式`() {
        // 卷根只覆盖自己，所以顶级文件夹得各自留下；Music/Live 被 Music 吃掉。
        assertEquals(
            setOf("", "Music", "Movies"),
            LibraryFolderRules.selectAll(listOf("", "Music", "Music/Live", "Movies")),
        )
        assertEquals(emptySet<String>(), LibraryFolderRules.selectAll(emptyList()))
    }

    // --------------------------------------------------------------- 文件夹树

    @Test
    fun `文件夹树先序排列并补齐祖先`() {
        val nodes = LibraryFolderRules.folderNodes(
            listOf(
                "Music/Live/",
                "Music/Album/",
                "", // 文件就在卷根
                null, // 算不出路径的条目不该造出任何节点
            ),
        )
        assertEquals(
            listOf("", "Music", "Music/Album", "Music/Live"),
            nodes.map { it.path },
        )
        // Music 自己没有直接子文件，但总计数必须把子文件夹里的算进来，
        // 否则界面上会出现「Music（0 项）」却点得进去的怪状态。
        assertEquals(0, nodes.first { it.path == "Music" }.directCount)
        assertEquals(2, nodes.first { it.path == "Music" }.totalCount)
        assertEquals(1, nodes.first { it.path == "Music/Album" }.totalCount)
        assertEquals(1, nodes.first { it.path == "" }.totalCount)
    }

    @Test
    fun `文件夹树的层级与显示名`() {
        val nodes = LibraryFolderRules.folderNodes(listOf("Music/Live/"))
        assertEquals(listOf("", "Music", "Music/Live"), nodes.map { it.path })
        assertEquals(listOf(0, 1, 2), nodes.map { it.depth })
        // 卷根没有名字，界面自己给「存储根目录」这条文案。
        assertEquals(listOf("", "Music", "Live"), nodes.map { it.name })
    }

    @Test
    fun `没有任何条目时文件夹树为空`() {
        assertEquals(emptyList<FolderNode>(), LibraryFolderRules.folderNodes(emptyList()))
        assertEquals(emptyList<FolderNode>(), LibraryFolderRules.folderNodes(listOf(null, null)))
    }
}
