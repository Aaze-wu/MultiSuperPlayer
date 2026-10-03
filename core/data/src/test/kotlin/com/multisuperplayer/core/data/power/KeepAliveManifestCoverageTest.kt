package com.multisuperplayer.core.data.power

import android.Manifest
import com.multisuperplayer.core.data.permissions.PermissionRules
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 申请电池优化白名单要靠清单里的一条声明。
 *
 * 少了它 `startActivity(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)` 会直接抛
 * `ActivityNotFoundException`——**不是弹一个空框**，而是「点了没反应」，
 * 而 `KeepAliveAccess` 会把这次失败读成「本机没有这个框」，静默退化成跳名单页。
 * 用户看到的只是「跳到另一个页面了」，离真因很远。
 *
 * `PermissionManifestCoverageTest` 钉不住这一条：它检查的是「声明的权限有没有人登记」，
 * 方向正好相反。两条合起来才封住「声明」与「使用」之间的那个缺口。
 */
class KeepAliveManifestCoverageTest {

    @Test
    fun `申请白名单要用到的权限名已经在清单里声明`() {
        assertTrue(
            "KeepAliveAccess.requestIntent() 要弹系统框，但清单里没有 " +
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in declaredPermissions(),
        )
    }

    @Test
    fun `这条权限也在权限页的折叠清单里登记过`() {
        // 另一头：声明了却谁也不说，权限页就漏掉一项用户实际拥有的权限——
        // 而「权限透明」正是权限页存在的理由。
        assertTrue(
            "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 没进 PermissionRules 的任何一份清单",
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in
                PermissionRules.OTHER_PERMISSIONS,
        )
    }

    /** 清单里声明的权限名。用正则而不是 `[xml]`：理由见 `PermissionManifestCoverageTest`。 */
    private fun declaredPermissions(): Set<String> {
        val file = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        assertTrue("找不到 $file", file.isFile)
        return Regex("""<uses-permission\s[^>]*android:name="([^"]+)"""")
            .findAll(file.readText())
            .map { it.groupValues[1] }
            .toSet()
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
