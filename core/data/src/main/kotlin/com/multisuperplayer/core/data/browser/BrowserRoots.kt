package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.R
import com.multisuperplayer.core.model.SafTreeInfo

/**
 * 组装「从哪里可以开始浏览」的那份清单。
 *
 * 抽成纯对象（没有 `Context`、没有 `ContentResolver`）是因为它决定的东西
 * **只能靠人眼发现错误**：
 *
 * - 少了一条 → 用户的某个 SD 卡在界面上不存在，看起来像「应用不支持 SD 卡」；
 * - `issue` 标错 → 条目点了没反应，或者把用户引到一个没用这项设置的页面上；
 * - 主卷和可移动卷的文案搞反 → 用户在两张卡之间选错。
 *
 * 这些都是纯数据的排列组合，没有任何理由让它们藏在 IO 代码里测不到。
 */
internal object BrowserRoots {

    /**
     * @param volumes 已挂载的卷（`StorageAccess.volumes()`）。
     * @param fileSystemSupported 这个系统版本有没有「所有文件访问」这个概念。
     * @param fileSystemGranted 用户现在有没有开。
     * @param safTrees 用户已授权的 SAF 目录树。
     * @param safAccessible 某个树 uri 现在还能不能读（授权可能已被系统收回）。
     */
    fun build(
        volumes: List<StorageVolumeInfo>,
        fileSystemSupported: Boolean,
        fileSystemGranted: Boolean,
        safTrees: List<SafTreeInfo>,
        safAccessible: (String) -> Boolean,
    ): List<BrowserRoot> {
        val fileSystemIssue = when {
            // API < 30 时**永远**不可用，而且不是「去开一下就好」——
            // 那个系统版本上我们没有合法途径读 /sdcard。
            // 界面必须分开说这两种情况，见 `StorageAccess` 的类注释。
            !fileSystemSupported -> BrowserRootIssue.NOT_SUPPORTED
            !fileSystemGranted -> BrowserRootIssue.ACCESS_OFF
            else -> null
        }

        // 顺序：先主卷，再外接卡，最后是用户自己授权的目录。
        // `volumes()` 的返回顺序由系统决定（不保证主卷在前），所以在这里排一次——
        // 排序键取自 `StorageVolumeInfo.primary` 这个**显式字段**，
        // 而不是回头从渲染出来的 label/detail 上反推（那种隐式耦合一改文案就断）。
        val fileSystemRoots = volumes.sortedByDescending { it.primary }.map { volume ->
            BrowserRoot(
                kind = BrowserSourceKind.FILE_SYSTEM,
                ref = volume.path,
                label = volumeLabel(volume),
                // 主卷的路径（`/storage/emulated/0`）对用户没有任何信息量，
                // 可移动卷的卷号（`1A2B-3C4D`）才是用户用来区分两张卡的东西。
                detail = if (volume.primary) null else volume.path.substringAfterLast('/'),
                // 原因在 map 外算一次、所有卷共用：「所有文件访问」是**应用级**开关，
                // 不是每个卷一个。放进 map 里逐个重算会让「两个卷状态不一致」
                // 看起来是可能的，而实际上不是。
                issue = fileSystemIssue,
            )
        }

        val safRoots = safTrees.map { tree ->
            BrowserRoot(
                kind = BrowserSourceKind.SAF,
                ref = tree.uri,
                // `label == null` 表示「这棵树就是某个卷的根」，见 SafTreeInfo 的注释。
                // 兜底**不能**猜成「内部存储」：那个卷完全可能是 SD 卡，猜错会让用户
                // 在两张卡之间选错。说「存储卷根」不会错，具体是哪一张看下面那行 uri。
                // 本地化字符串不能存在数据层之外，所以这里是它唯一的落点。
                label = tree.label?.let { MspText.Plain(it) }
                    ?: MspText.Res(R.string.msp_browser_volume_root),
                detail = tree.uri,
                // 授权被收回是**常态**（用户在设置里撤销、SD 卡拔出、provider 被卸载），
                // 而不是异常。这里必须真的去问一次，不能拿清单里「授权过」当事实。
                issue = if (safAccessible(tree.uri)) null else BrowserRootIssue.GRANT_REVOKED,
            )
        }

        return fileSystemRoots + safRoots
    }

    private fun volumeLabel(volume: StorageVolumeInfo): MspText =
        if (volume.primary) MspText.Res(R.string.msp_browser_storage_internal)
        else MspText.Res(R.string.msp_browser_storage_removable)
}
