package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.model.SafTreeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从哪里可以开始浏览」这份清单的单元测试。
 *
 * 它决定的东西只能靠人眼发现错误：少一条 ⇒ 用户的存储卡在界面上不存在；
 * `available` 标错 ⇒ 条目点了没反应；主卷与可移动卷的文案搞反 ⇒
 * 用户在两张卡之间选错。全都是纯数据排列，没有任何理由不可测。
 */
class BrowserRootsTest {

    @Test
    fun `没开权限时文件系统来源仍然列出来，只是标记为不可用`() {
        val roots = BrowserRoots.build(
            volumes = listOf(StorageVolumeInfo("/storage/emulated/0", primary = true, removable = false)),
            fileSystemSupported = true,
            fileSystemGranted = false,
            safTrees = emptyList(),
            safAccessible = { true },
        )

        // 「条目在不在列表里」和「条目能不能用」必须分开：
        // 藏起来的话，用户根本不知道有这个来源、也不知道去哪儿开权限。
        val root = roots.single()
        assertFalse(root.available)
        // 原因必须是「用户还没开」而不是「系统太旧」：前者界面要给一个跳设置页的
        // 按钮，后者给了也没用（那个页里根本没这一项）。
        assertEquals(BrowserRootIssue.ACCESS_OFF, root.issue)
        assertEquals(BrowserSourceKind.FILE_SYSTEM, root.kind)
        assertTrue(root.label is MspText.Res)
    }

    @Test
    fun `系统版本太低时永远不可用，与有没有授权无关`() {
        val roots = BrowserRoots.build(
            volumes = listOf(StorageVolumeInfo("/storage/emulated/0", primary = true, removable = false)),
            fileSystemSupported = false,
            // 就算这个值被传成 true（例如状态读串了），也不能变成可用：
            // API < 30 上我们没有任何合法途径读 /sdcard。
            fileSystemGranted = true,
            safTrees = emptyList(),
            safAccessible = { true },
        )

        assertFalse(roots.single().available)
        assertEquals(BrowserRootIssue.NOT_SUPPORTED, roots.single().issue)
    }

    @Test
    fun `「系统太旧」和「用户没开」是两种原因，不能合成一个`() {
        // 这两种情况界面要做的事完全不同：前者**不该**有按钮（用户去设置里也找不到
        // 这一项），后者要给一个直接跳设置页的按钮。只有一个布尔值的话，
        // 界面就只能猜——猜错的症状是「引导用户点一个没反应的按钮」。
        val tooOld = BrowserRoots.build(
            volumes = listOf(StorageVolumeInfo("/storage/emulated/0", primary = true, removable = false)),
            fileSystemSupported = false,
            fileSystemGranted = false,
            safTrees = emptyList(),
            safAccessible = { true },
        ).single()

        val notGranted = BrowserRoots.build(
            volumes = listOf(StorageVolumeInfo("/storage/emulated/0", primary = true, removable = false)),
            fileSystemSupported = true,
            fileSystemGranted = false,
            safTrees = emptyList(),
            safAccessible = { true },
        ).single()

        // 两者都不可用（这就是旧布尔字段能表达的全部），但原因必须分得开。
        assertFalse(tooOld.available)
        assertFalse(notGranted.available)
        assertEquals(BrowserRootIssue.NOT_SUPPORTED, tooOld.issue)
        assertEquals(BrowserRootIssue.ACCESS_OFF, notGranted.issue)
    }

    @Test
    fun `主卷排在可移动卷前面，与输入顺序无关`() {
        // `StorageAccess.volumes()` 的顺序由系统决定，不保证主卷在前。
        val roots = BrowserRoots.build(
            volumes = listOf(
                StorageVolumeInfo("/storage/1A2B-3C4D", primary = false, removable = true),
                StorageVolumeInfo("/storage/emulated/0", primary = true, removable = false),
            ),
            fileSystemSupported = true,
            fileSystemGranted = true,
            safTrees = emptyList(),
            safAccessible = { true },
        )

        assertEquals(listOf("/storage/emulated/0", "/storage/1A2B-3C4D"), roots.map { it.ref })
        // 可用时 `issue` 必须是 null，而且 `available` 是从它推出来的——
        // 两个字段同时存在的话，迟早会出现「available=true 但 issue 写着没权限」。
        assertTrue(roots.all { it.issue == null })
    }

    @Test
    fun `主卷不显示路径，可移动卷用卷号区分`() {
        val roots = BrowserRoots.build(
            volumes = listOf(
                StorageVolumeInfo("/storage/emulated/0", primary = true, removable = false),
                StorageVolumeInfo("/storage/1A2B-3C4D", primary = false, removable = true),
            ),
            fileSystemSupported = true,
            fileSystemGranted = true,
            safTrees = emptyList(),
            safAccessible = { true },
        )

        // `/storage/emulated/0` 对用户没有任何信息量，显示出来只是噪音。
        assertNull(roots.first().detail)
        // 卷号才是用户用来区分两张卡的东西（他在系统设置里看到的就是这个）。
        assertEquals("1A2B-3C4D", roots.last().detail)
    }

    @Test
    fun `SAF 授权被收回时标记为不可用而不是消失`() {
        val roots = BrowserRoots.build(
            volumes = emptyList(),
            fileSystemSupported = true,
            fileSystemGranted = true,
            safTrees = listOf(SafTreeInfo(uri = "content://tree/gone", label = "Music", addedAtMs = 1L)),
            safAccessible = { false },
        )

        val root = roots.single()
        assertEquals(BrowserSourceKind.SAF, root.kind)
        assertEquals("content://tree/gone", root.ref)
        // 授权被收回是常态（用户在设置里撤销、SD 卡拔出），不是异常。
        // 条目留着 + 标记不可用 = 界面能说「这个目录要重新授权」。
        assertFalse(root.available)
        assertEquals(BrowserRootIssue.GRANT_REVOKED, root.issue)
    }

    @Test
    fun `SAF 树的 label 原样使用，卷根才用本地化文案`() {
        val roots = BrowserRoots.build(
            volumes = emptyList(),
            fileSystemSupported = true,
            fileSystemGranted = true,
            safTrees = listOf(
                SafTreeInfo(uri = "content://tree/music", label = "Music", addedAtMs = 1L),
                // label 为 null 表示「授权的是某个卷的根」。
                SafTreeInfo(uri = "content://tree/root", label = null, addedAtMs = 2L),
            ),
            safAccessible = { true },
        )

        assertEquals(MspText.Plain("Music"), roots.first().label)
        assertTrue(roots.last().label is MspText.Res)
    }

    @Test
    fun `系统一个卷都给不出来时 SAF 目录仍然出现`() {
        // `volumes()` 在权限不足 / 读取失败时会返回空表（见 StorageAccess）。
        // 那种情况下不能让 SAF 目录跟着一起消失——它们是两条独立的来源。
        val roots = BrowserRoots.build(
            volumes = emptyList(),
            fileSystemSupported = true,
            fileSystemGranted = true,
            safTrees = listOf(SafTreeInfo(uri = "content://tree/a", label = "A", addedAtMs = 1L)),
            safAccessible = { true },
        )

        assertEquals(listOf("content://tree/a"), roots.map { it.ref })
    }
}
