package com.multisuperplayer.core.data.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** 一个存储卷。 */
data class StorageVolumeInfo(
    /** 卷根绝对路径，例如 `/storage/emulated/0`。 */
    val path: String,
    val primary: Boolean,
    val removable: Boolean,
)

/**
 * 「现在有没有全盘读权限」这一个问题的抽象。
 *
 * ## 为什么要单独抽这么一个只有一个方法的接口
 *
 * 因为 `FileSystemSource` 是本模块里**唯一能在 JVM 单测里真跑**的目录来源
 * （`java.io.File` 是纯 JDK）。而它唯一的外部依赖就是这一个布尔值。
 *
 * 如果让它直接依赖 [StorageAccess]（需要 `Context`，而 `Environment` 在单测里
 * 只会返回默认值 `false`），这条唯一可测的路径也就变成不可测了——而它恰好承载了
 * 「目录 vs 文件怎么判、绝对路径怎么拼、大小/时间取哪个值」这些**只有它能验证**
 * 的转换。
 *
 * 所以这里用 `fun interface`：生产代码传 [StorageAccess]，测试里传 `{ true }`，
 * 一行 `Method ... not mocked` 都不会出现。
 *
 * 它是 `public` 只是为了满足 Kotlin 的可见性规则（[StorageAccess] 是个 public 类），
 * 语义上没有对外的意思——真正的对外入口是 [StorageAccess.hasAllFilesAccess]。
 */
fun interface AllFilesAccess {
    fun granted(): Boolean
}

/**
 * 「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`) 的状态与授权入口。
 *
 * ## 这个权限的定位（重要，别改）
 *
 * 它是**可选**的、**默认关闭**的、**永不主动申请**的。理由：
 *
 * - `MANAGE_EXTERNAL_STORAGE` 是特权权限，Play 商店对它有明确的适用范围要求，
 *   一个播放器要它必须有「核心功能依赖全盘访问」的说法。用户不想要这个权限时，
 *   应用必须**完全可用**——所以它只是[FileSystemSource] 这一个来源的开关，
 *   别的来源（媒体库、SAF）一行都不依赖它。
 * - 它的授权不是一个权限对话框，而是一个**跳出去的系统设置页**。用户可能在里面
 *   什么也不做就返回。所以界面必须在每次回到前台时重新读一次状态，
 *   而不是「点过一次就当开着了」——这是它和普通运行时权限最大的行为差别。
 *
 * ## 为什么 API < 30 直接判不可用
 *
 * 分区存储在 Android 10 (API 29) 上已经开始强制（`requestLegacyExternalStorage`
 * 只对 `targetSdk <= 29` 生效，而我们 `targetSdk = 36`），而
 * `isExternalStorageManager()` 是 API 30 才有的。也就是说 API 26–29 上
 * **没有任何**合法途径能自由读 `/sdcard`。
 *
 * 那就老实说「不支持」——比返回 `false`（看起来像「用户没开，去开一下吧」）
 * 诚实得多：那会把用户送进一个点了没反应的设置页。
 */
class StorageAccess(context: Context) : AllFilesAccess {

    private val appContext = context.applicationContext

    /** 这个系统版本**有没有**「所有文件访问」这个概念。 */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 用户**现在**有没有开。API < 30 恒为 `false`（见类注释）。 */
    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return Environment.isExternalStorageManager()
    }

    /** 给 [FileSystemSource] 用同一个答案——它只想知道「能不能读」。 */
    override fun granted(): Boolean = hasAllFilesAccess()

    /**
     * 跳系统「所有文件访问」设置页的 intent。
     *
     * 带 `package:` 的那个是应用专页；少数精简 ROM 没实现它，所以调用方
     * **必须**有 fallback（见 [fallbackSettingsIntent]）——直接
     * `startActivity` 会抛 `ActivityNotFoundException`。
     */
    fun settingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${appContext.packageName}"),
        )

    /** 兜底：跳全局的那一页（用户要自己找应用）。 */
    fun fallbackSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /**
     * 直接能跳到「本应用」那一页的 intent；本机没有这一页时退回全局列表页。
     *
     * 判断放在这里而不是界面里，是因为这也是个**只能靠猜**的系统行为：
     * `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 在大多数设备上有，
     * 少数精简 ROM 上没有，唯一可靠的检测是问一次 PackageManager。
     * 界面那边则可以不再写 try/catch——两种返回都保证能**启动**。
     */
    fun preferredSettingsIntent(): Intent {
        val appPage = settingsIntent()
        val resolvable = appContext.packageManager.resolveActivity(appPage, 0) != null
        return if (resolvable) appPage else fallbackSettingsIntent()
    }

    /**
     * 列出所有**已挂载**的存储卷（内部存储 + 可插拔的 SD 卡 / U 盘）。
     *
     * 用 `getExternalFilesDirs` 而不是 `StorageManager.getStorageVolumes()`：
     *
     * - `getStorageVolumes()` 的 `getDirectory()` 要 API 30，`getDescription()`
     *   返回的是**系统本地化的字符串**（「内部共享存储」），我们拿不到「哪个是主卷」，
     *   只能靠 `isPrimary`（API 30 才有）。
     * - `getExternalFilesDirs` 从 API 19 起就返回**每一个已挂载卷**上属于本应用的目录，
     *   路径形如 `/storage/1A2B-3C4D/Android/data/<pkg>/files`，
     *   截到 `/Android/` 之前就是卷根，而且 `isExternalStorageEmulated(dir)`
     *   直接告诉我们哪个是主卷。零权限、零版本分支。
     *
     * 返回顺序**不保证**主卷在前，调用方要自己排序（不能靠 `first()` 拿主卷）。
     */
    fun volumes(): List<StorageVolumeInfo> = runCatching {
        appContext.getExternalFilesDirs(null)
            .filterNotNull()
            .mapNotNull { dir ->
                val root = dir.absolutePath.substringBefore("/Android/", "")
                if (root.isEmpty()) return@mapNotNull null
                val primary = Environment.isExternalStorageEmulated(dir)
                StorageVolumeInfo(path = root, primary = primary, removable = !primary)
            }
            // 同一个卷可能会返回多条（多用户 / 多存储域），按路径去重。
            .distinctBy { it.path }
    }.getOrDefault(emptyList())
}
