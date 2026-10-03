package com.multisuperplayer.core.data.power

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 「后台保活」这一件事涉及的全部系统交互：**读状态** + **三张要去的地方**。
 *
 * ## 为什么这是两个概念，不是一个开关
 *
 * 用户看到的是一句「后台长时间播放不被系统杀掉」，系统里其实是**两套互不相干的机制**：
 *
 * 1. **电池优化白名单**（AOSP 标准）。不在名单里的应用进入 Doze 后网络被掐、后台任务被推迟。
 *    进入的办法只有一个：弹系统框申请（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。
 * 2. **厂商自己的后台管理**（小米的自启动、华为的启动管理、OPPO 的省电策略……）。
 *    这套**完全不看**上面那个白名单，且没有任何公开 API——只能把用户送到那个页面，
 *    由他自己点。同一个厂商不同系统版本的组件名还不一样，所以是一串候选。
 *
 * 把 (2) 当成 (1) 的附注会误导用户：在小米上加入了白名单，锁屏清理照样会把播放掐掉。
 * 所以 [requestIntent] 和 [vendorIntents] 必须分别暴露，界面上也分成两段说。
 *
 * ## 为什么一次只能试一个
 *
 * [vendorIntents] 返回的是一串候选而不是一个，因为**没有可靠办法预先知道哪一个是有效的**。
 * `PackageManager.resolveActivity` 在 Android 11+ 的包可见性规则下对别的应用的组件
 * 可能返回 `null`（明明存在），而 `startActivity` 会隐式让组件可见，反而更准。
 * 所以策略是「按顺序 `startActivity`，第一个不抛异常的就是它」（见 [openVendorSettings]）。
 */
class KeepAliveAccess(context: Context) {

    private val appContext = context.applicationContext

    private val powerManager: PowerManager? =
        appContext.getSystemService(PowerManager::class.java)

    // ------------------------------------------------------------------ 读事实

    /** 一次把判定要用的三样都取回来，避免界面分几次问时读到不一致的快照。 */
    fun facts(): KeepAliveFacts = KeepAliveFacts(
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(),
        manufacturer = Build.MANUFACTURER.orEmpty(),
        brand = Build.BRAND.orEmpty(),
    )

    fun state(): KeepAliveState = KeepAliveRules.stateOf(facts())

    /**
     * 现在在不在白名单里。
     *
     * 取不到 `PowerManager` 时按「不在」算：这台设备上真实情况无从得知，
     * 而按「在」说会让用户放着一个可能被杀的后台不管——两种猜法的代价不对称。
     */
    fun isIgnoringBatteryOptimizations(): Boolean =
        powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true

    fun vendor(): DeviceVendor =
        KeepAliveRules.vendorOf(Build.MANUFACTURER.orEmpty(), Build.BRAND.orEmpty())

    // ---------------------------------------------------------------- 去的地方

    /**
     * 系统那个「允许它在后台不受限制地运行吗」的框。
     *
     * 它需要清单里的 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，缺了会直接抛
     * `ActivityNotFoundException`（不是弹一个空框）——所以那条声明和这里是一体的，
     * 单测里有一条专门核对它（见 `KeepAliveManifestCoverageTest`）。
     *
     * 少数 ROM 把这个框吃掉了，那时调用方要退到 [optimizationListIntent]。
     */
    fun requestIntent(): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.fromParts("package", appContext.packageName, null),
    )

    /**
     * 系统的「电池优化」名单页。
     *
     * 两个用途：
     * - [requestIntent] 在本机打不开时的兜底（用户自己在名单里找本应用）；
     * - **唯一的「退出白名单」途径**。系统没有「请把我移出白名单」的 API，
     *   应用能做的只有把人送到这一页——这一点必须在界面文案里说清楚，
     *   否则用户会以为关掉开关就等于已经退出（见 `msp_keep_alive_switch_note`）。
     */
    fun optimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 本应用的「应用信息」页。厂商页一个都打不开时的最后兜底。 */
    fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", appContext.packageName, null),
    )

    /**
     * 这家厂商的「后台管理 / 自启动」页的候选，按「越可能生效的越靠前」排。
     *
     * 不认识 [DeviceVendor.OTHER] 返回空列表——此时界面的说法要换成
     * 「去应用信息页」（见 `KeepAliveSummaries.vendorDescription`），
     * 而不是给一个点了什么都不会发生的按钮。
     *
     * 组件名是社区长期积累的常量，没有官方文档；系统大版本升级会换包名，
     * 所以每一家都留了两三个候选，且**不保证**成功——这正是 [openVendorSettings]
     * 必须逐个 try 的原因。
     */
    fun vendorIntents(): List<Intent> = when (vendor()) {
        DeviceVendor.XIAOMI -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
            component("com.miui.securitycenter", "com.miui.powercenter.PowerCenterActivity"),
        )

        DeviceVendor.HUAWEI -> huaweiIntents()

        DeviceVendor.HONOR -> listOf(
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ) + huaweiIntents()

        DeviceVendor.OPPO -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        )

        DeviceVendor.VIVO -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity"),
        )

        DeviceVendor.MEIZU -> listOf(
            component("com.meizu.safe", "com.meizu.safe.security.SmartPermissionActivity"),
        )

        DeviceVendor.SAMSUNG -> listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )

        DeviceVendor.ONE_PLUS -> listOf(
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            component("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
        )

        DeviceVendor.OTHER -> emptyList()
    }

    /**
     * 逐个试 [vendorIntents]，第一个能启动的就算成功。
     *
     * `runCatching` 而不是先 `resolveActivity` 再启动：包可见性会让后者误判成 `null`
     * （见类注释），而前者捕获的 `ActivityNotFoundException` 正是「这个组件不存在」
     * 的准确答案。顺带也捕获了 `SecurityException`（组件存在但未导出）。
     *
     * 返回 `false` 表示**一个都没打开**，调用方此时应当退到 [appDetailsIntent]——
     * 而不是停在这里让用户对着一个没反应的按钮。
     */
    fun openVendorSettings(activity: Activity): Boolean =
        vendorIntents().any { intent -> open(activity, intent) }

    /** 启动一个页面，返回是否成功。失败的唯一信号是抛异常（见 [openVendorSettings]）。 */
    fun open(activity: Activity, intent: Intent): Boolean =
        runCatching { activity.startActivity(intent) }.isSuccess

    private fun component(packageName: String, className: String): Intent =
        Intent().setComponent(ComponentName(packageName, className))

    /** 荣耀和华为共用的一套包名：独立前后的机器都在这两个包之间。 */
    private fun huaweiIntents(): List<Intent> = listOf(
        component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    )
}
