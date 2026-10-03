package com.multisuperplayer.core.data.permissions

import android.Manifest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单里声明的权限，必须能在「权限页要说的话」里找到归属。
 *
 * 权限页只列用户能改的四项，其余的退化成一段折叠说明——但**每一份清单都必须
 * 承认自己是一份清单**：新加一条 `<uses-permission>` 却谁都没登记时，应用会
 * 静默多出一个用户看不到的权限，而这正是「权限透明」这件事最不该有的样子。
 *
 * 这个测试不检查「有没有必要申请这个权限」，只检查「有没有人说过它」——
 * 后者是能被机器检查的那一半。
 */
class PermissionManifestCoverageTest {

    @Test
    fun `清单里声明的权限与三份清单完全对应`() {
        val declared = declaredPermissions()
        val classified = (
            PermissionRules.LISTED_PERMISSIONS +
                PermissionRules.NOT_LISTED_PERMISSIONS +
                PermissionRules.OTHER_PERMISSIONS
            ).toSet()

        val unclassified = declared - classified
        val phantom = classified - declared

        // 两个方向分开报：合成一个「集合不相等」的话，看到的人还得自己算差在哪。
        assertEquals(
            "这些权限在 AndroidManifest.xml 里声明了，但没进权限页的任何一份清单" +
                "（要么加进 LISTED_PERMISSIONS，要么放进 NOT_LISTED_PERMISSIONS 并写明理由）",
            emptySet<String>(),
            unclassified,
        )
        assertEquals(
            "这些权限在三份清单里，但 AndroidManifest.xml 里没有——权限页会白列一项并不存在的权限",
            emptySet<String>(),
            phantom,
        )
    }

    @Test
    fun `四项可更改权限真的会去申请的那个权限名都在清单里声明了`() {
        // 上面那个测试只保证「有人登记过」，这一条保证「登记的是真的会申请的东西」：
        // 少了声明时 `checkSelfPermission` 永远答 DENIED、`requestPermissions` 静默返回，
        // 权限页上那一行就会永远是「未开启，可以在这里申请」——点多少次都没反应，
        // 而所有数据都对得上，看不出错。
        //
        // 这份对应关系是照着 `AppPermissions.requestFor` 抄的：那边要 `Context`，
        // 在 JVM 单测里跑不起来，所以只能在这里把「哪一种点会申请哪些名字」钉住。
        val declared = declaredPermissions()
        val required = mapOf(
            PermissionKind.MEDIA to listOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                // ≤32 上媒体读取的**旧名字**，同一项权限的另一种形态。
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
            PermissionKind.ALL_FILES to listOf(Manifest.permission.MANAGE_EXTERNAL_STORAGE),
            PermissionKind.NOTIFICATION to listOf(Manifest.permission.POST_NOTIFICATIONS),
            PermissionKind.BLUETOOTH to listOf(Manifest.permission.BLUETOOTH_CONNECT),
        )

        required.forEach { (kind, permissions) ->
            permissions.forEach { permission ->
                assertTrue(
                    "$kind 会申请 $permission，但 AndroidManifest.xml 里没有声明它",
                    permission in declared,
                )
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 清单里声明的权限名。
     *
     * 用正则而不是 `[xml]`：这份清单里 `tools:ignore` 和注释都参与过一次真实的
     * 解析事故（`aapt2` 之外几乎没有第二个解析器愿意为这些方言负责），
     * 而这里要的只是**一个名字列表**。
     */
    private fun declaredPermissions(): Set<String> {
        val raw = manifestFile().readText()
        return Regex("""<uses-permission\s[^>]*android:name="([^"]+)"""")
            .findAll(raw)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun manifestFile(): File {
        val file = File(repoRoot(), "app/src/main/AndroidManifest.xml")
        assertTrue("找不到 $file", file.isFile)
        return file
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
