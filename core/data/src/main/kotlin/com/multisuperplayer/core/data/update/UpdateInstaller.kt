package com.multisuperplayer.core.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.multisuperplayer.core.common.log.MspLog
import java.io.File

/** 准备安装的结果。三态是因为界面要说三句不同的话、给三个不同的下一步。 */
sealed interface InstallLaunch {

    /** 可以装了，把 [intent] 交给 `startActivity`。 */
    data class Ready(val intent: Intent) : InstallLaunch

    /** 系统不允许本应用装 APK，得用户先开「安装未知应用」。 */
    data object NeedsUnknownSourcePermission : InstallLaunch

    /** 系统里没有任何能处理 APK 的安装器（或共享路径配置不对，见日志）。 */
    data object NoInstaller : InstallLaunch
}

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * ## 为什么必须有「安装未知应用」这一步
 *
 * `REQUEST_INSTALL_PACKAGES` 只是**声明**，安装即授予（所以它在权限页的折叠区里）。
 * 从 API 26 起，真正决定「本应用能不能装 APK」的是每个应用一份的 special access
 * `canRequestPackageInstalls()`，默认是**关**的。少了这一步，
 * `ACTION_VIEW` 的安装意图会被系统丢掉——用户点「安装」之后什么都没发生，
 * 而这看起来像应用坏了，不像缺权限。
 *
 * 所以界面上的顺序是：先问 [canInstall]，false 就先把人送到那个开关页，
 * 回来（`ON_RESUME`）再重试。
 *
 * ## 为什么走 FileProvider
 *
 * `file://` 的 Uri 从 targetSdk 24 起会直接抛 `FileUriExposedException`。
 * 共享目录的白名单在 `res/xml/update_apk_paths.xml`。
 */
class UpdateInstaller(context: Context) {

    private val appContext = context.applicationContext

    /** 本应用现在能不能发起安装。API 26 是本项目的 minSdk，无需再判版本。 */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /**
     * 「安装未知应用」的授权页，**限定到本应用**。
     *
     * 不带 `package:` 的话会打开整个列表页，用户还得自己找到本应用——
     * 而那一页在部分 ROM 上会显示成十几个条目，找不到是很正常的。
     */
    fun unknownSourceSettingsIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${appContext.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun prepareInstall(apk: File): InstallLaunch {
        if (!apk.exists()) {
            MspLog.w(TAG) { "安装包不存在：${apk.absolutePath}" }
            return InstallLaunch.NoInstaller
        }
        if (!canInstall()) return InstallLaunch.NeedsUnknownSourcePermission

        val intent = try {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uriFor(apk), APK_MIME_TYPE)
                // 只借读权限，而且只在这一次 intent 上生效（不写进文件、不写进 manifest）。
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // 部分 ROM（国产 ROM 居多）的安装器要求带 NEW_TASK 才起得来。
                // 带上它不会让界面跑出我们自己的任务栈：系统安装器本来就是独立任务。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: IllegalArgumentException) {
            // FileProvider 找不到配置好的根目录时抛这个。它是**我们的配置错误**
            // （白名单和落地目录不一致），所以必须留一条 error 日志——
            // 否则用户只看到「本机没有安装器」，而真因在代码里。
            MspLog.e(TAG, e) { "交不出安装包：FileProvider 路径未配置（检查 update_apk_paths.xml）" }
            return InstallLaunch.NoInstaller
        }

        return if (intent.resolveActivity(appContext.packageManager) == null) {
            MspLog.w(TAG) { "系统里没有能处理 APK 的安装器" }
            InstallLaunch.NoInstaller
        } else {
            InstallLaunch.Ready(intent)
        }
    }

    private fun uriFor(apk: File): Uri {
        // authority 必须与 AndroidManifest 里的 `${applicationId}.update.fileprovider` 一致：
        // 写死包名会让调试版（带 applicationIdSuffix）在运行时找不到 provider。
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.update.fileprovider", apk)
    }

    private companion object {
        const val TAG = "UpdateInstaller"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
