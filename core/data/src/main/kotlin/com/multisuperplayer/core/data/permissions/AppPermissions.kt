package com.multisuperplayer.core.data.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.multisuperplayer.core.data.browser.StorageAccess
import com.multisuperplayer.core.data.library.MediaStoreScanRules
import com.multisuperplayer.core.data.library.MediaStoreScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「这项权限现在怎么样、还能不能申请、该带用户去哪儿」——应用权限的三件事都在这里。
 *
 * ## 为什么要有这一层
 *
 * 判定权限要用到三样东西：系统的授予结果、`shouldShowRequestPermissionRationale`
 * （**只有 `Activity` 有**）和「这个系统版本有没有这项权限」。把它们散在界面上写，
 * 结果是每一处都要自己拼一遍版本判断，而 `rationale` 那一栏最容易漏——
 * 漏了它，「被拒过一次」和「被永久拒绝」就会显示成同一句话，用户点下去什么都不发生。
 *
 * 判定本身（[PermissionRules]）是纯函数、可 JVM 单测；这里只负责**取事实**：
 * 权限名、版本号、[Activity]、以及「问过没有」这个只能记在磁盘上的事实。
 *
 * ## 为什么「问过没有」要自己记
 *
 * `shouldShowRequestPermissionRationale` 只能在**问过之后**才说得出话，它无法区分
 * 「还没申请过」和「申请过、用户点了拒绝」——两种情况它都答 `false`。而这两句话
 * 对应的界面文案不一样（「还没申请过」vs「已在系统里拒绝」），所以第一次申请时
 * 自己在 [markAsked] 记一笔，之后才有得读。
 *
 * 只有**这一个**地方记这件事，且只在真正调起系统框之前记：记早了（比如一进页面就记）
 * 会让「还没问过」永远读不到；记晚了（申请之后再记）用户中途把应用切掉就丢失。
 */
class AppPermissions(
    context: Context,
    private val scanner: MediaStoreScanner,
    private val storage: StorageAccess,
) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------- 状态与待办（**跨实例共享**）
    //
    // 这两条流必须住在这个单例里，不能住在 `PermissionsViewModel` 里。
    // 原因是 ViewModel 会有**不止一个实例**：应用根（`MspApp`）拿到的那个挂在
    // Activity 上，而权限页（`NavHost` 里的 `composable`）拿到的是挂在
    // `NavBackStackEntry` 上的另一个实例。
    //
    // 这个区别曾经造成一个很难看的 bug：权限页点了「申请」，请求写进了**它自己**
    // 那份待办，而唯一会去调系统框的那个 launcher 挂在根上、观察的是**另一份**
    // 待办——于是按下去什么都不弹，而「问过系统了」那笔账已经记下了。
    // 把状态收进单例之后，谁发起、谁显示、谁弹框看到的都是同一份。

    /** 待弹的系统授权框（`null` = 没有待办，见 [request]）。 */
    private val _pending = MutableStateFlow<List<String>?>(null)
    val pending: StateFlow<List<String>?> = _pending.asStateFlow()

    /** 最近一次读到的四行状态。界面只读它，改动一律走 [refresh]。 */
    private val _snapshot = MutableStateFlow(snapshot(activity = null))
    val snapshotState: StateFlow<PermissionSnapshot> = _snapshot.asStateFlow()

    /**
     * 四行的当前状态。
     *
     * [activity] 可以为 `null`：那时拿不到 `rationale`，只能退化成
     * [stateOf][PermissionRules.stateOf] 里 `rationale = false` 的那一支。这不影响
     * 「已允许 / 未允许」的结论，只影响「被拒一次」和「被永久拒绝」的分辨——
     * 所以设置入口页那一行的摘要（只看是否允许）用 `null` 也够，而权限页自己
     * 会带着真的 `Activity` 再算一次。
     */
    fun snapshot(activity: Activity?): PermissionSnapshot = PermissionSnapshot(
        media = mediaState(activity),
        allFiles = allFilesState(),
        notification = notificationState(activity),
        bluetooth = bluetoothState(activity),
    )

    /**
     * 重新问一次系统，并把结果推给界面（[snapshotState]）。
     *
     * [activity] 用来读 `shouldShowRequestPermissionRationale`（**只有 Activity 有**），
     * 它决定了「被拒过一次」和「已被永久拒绝」要不要分开说。没有 Activity 时只少
     * 这一层分辨，已允许/未允许的结论不受影响（见 [snapshot]）。
     */
    fun refresh(activity: Activity?) {
        _snapshot.value = snapshot(activity)
    }

    /**
     * 用户点了某一行的「申请」：记账，然后把待办交给界面去弹框。
     *
     * 记账（[markAsked]）必须在**真正调起系统框之前**，理由见类注释。
     */
    fun request(kind: PermissionKind) {
        launchRequest(requestFor(kind))
    }

    /**
     * 应用启动后那一次申请：**整个安装只做一次**。
     *
     * 三件刻意不做的事见 [startupRequest]；「做过了」先记再申请——中途把应用切掉的话，
     * 下一次不该又弹一次。
     */
    fun requestAtStartup() {
        if (hasAskedAtStartup()) return
        markStartupAsked()
        launchRequest(startupRequest())
    }

    /**
     * 界面已经把系统框弹出去了，或者一次没有框的静默答复回来了。
     *
     * **顺手带 Activity 重算一次状态**，不能只等页面的 `ON_RESUME`：有些权限系统
     * 不弹框就直接拒（例如没有蓝牙适配器的设备上的 `BLUETOOTH_CONNECT`），那种情况
     * 不会产生 resume；不在这里刷新的话，权限页会一直停在「未开启，可以在这里申请」
     * ——一个按下去什么都不会发生的按钮。
     */
    fun onRequestLaunched(activity: Activity?) {
        _pending.value = null
        refresh(activity)
    }

    private fun launchRequest(requested: List<String>) {
        // 空列表 = 现在没有能申请的东西（例如 ≤32 上直接去设置的那两项）。
        // 不能把空列表当成待办：界面会去弹一个什么都没有的框。
        if (requested.isEmpty()) return
        // 同时只允许一个框：同一个 launcher 弹两个框时后一个会把前一个挤掉，
        // 而两个框都会以为「已经申请过了」，永久拒绝与「没问过」就分不开了。
        if (_pending.value != null) return
        markAsked(requested)
        _pending.value = requested
    }

    /**
     * 这一项现在**该申请哪些权限**（空列表 = 现在没有能申请的东西）。
     *
     * 权限名必须按系统版本给准：申请一个当前版本不存在的权限，系统的授权框
     * **直接不弹**，用户看到的就是「点了没反应」。
     */
    fun requestFor(kind: PermissionKind): List<String> = when (kind) {
        PermissionKind.MEDIA -> scanner.requiredPermissions().toList()

        PermissionKind.NOTIFICATION ->
            if (Build.VERSION.SDK_INT < VERSION_TIRAMISU) {
                emptyList()
            } else {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            }

        PermissionKind.BLUETOOTH ->
            if (Build.VERSION.SDK_INT < VERSION_S) {
                emptyList()
            } else {
                listOf(Manifest.permission.BLUETOOTH_CONNECT)
            }

        // 特权权限没有「申请」这一条路（见 PermissionRules.canAskInPlace）。
        PermissionKind.ALL_FILES -> emptyList()
    }

    /**
     * 记下「这些权限问过系统了」。
     *
     * **必须在真正调起授权框之前调**（见类注释）。
     */
    fun markAsked(permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        prefs.edit { permissions.forEach { permission -> putBoolean(askedKey(permission), true) } }
    }

    /**
     * 该带用户去哪个系统页面。
     *
     * 「所有文件访问」有专属页面（[StorageAccess.preferredSettingsIntent]）；其余三项
     * 统一去**应用详情页**。不给每一类各挑一个更精确的页面，是因为通知那一类更精确的
     * `ACTION_APP_NOTIFICATION_SETTINGS` 在部分定制系统上根本没有 Activity 承接，
     * 而应用详情页每个系统都有，且四类权限在那里都能改。
     */
    fun settingsIntent(kind: PermissionKind): Intent = when (kind) {
        PermissionKind.ALL_FILES -> storage.preferredSettingsIntent()
        else -> appDetailsIntent()
    }

    // --------------------------------------------------------------- 首次启动

    /**
     * 首次启动那次申请做过了没有。
     *
     * 这是个**安装级**的一次性开关：它保证「自动申请」这件事只发生一次。之后用户
     * 再拒绝，就得自己进权限页——自动重复申请正是系统设计上讨厌的行为（用户拒绝后
     * 一律视为永久拒绝，再弹也是白弹）。
     */
    fun hasAskedAtStartup(): Boolean = prefs.getBoolean(KEY_STARTUP_ASKED, false)

    /** 标记首次启动那次申请已经做过（无论用户同意还是拒绝）。 */
    fun markStartupAsked() {
        prefs.edit { putBoolean(KEY_STARTUP_ASKED, true) }
    }

    /**
     * 首次启动要申请的那一批：**媒体读取 + 通知**。
     *
     * 三件刻意不做的事：
     * - **不含「所有文件访问」**：它是可选能力、默认关闭，README 里承诺过不在首次启动申请；
     * - **不含蓝牙**：本版本没有任何代码用它，申请一个用不上的权限只会让人起疑；
     * - **不申请已经允许的**：降级安装 / 恢复备份之后再进来，不该为已有的权限再弹一次。
     *
     * 通知那一项只在 [PermissionState.NOT_ASKED] 时才带进去：一旦用户拒绝过
     * （`rationale` 为真），自动再弹一次就是骚扰。
     */
    fun startupRequest(): List<String> {
        val result = mutableListOf<String>()

        if (!scanner.hasAudioPermission() || !scanner.hasVideoPermission()) {
            result += requestFor(PermissionKind.MEDIA)
        }
        if (notificationState(activity = null) == PermissionState.NOT_ASKED) {
            result += requestFor(PermissionKind.NOTIFICATION)
        }
        return result
    }

    // --------------------------------------------------------------- 取事实

    private fun mediaState(activity: Activity?): PermissionState =
        PermissionRules.mediaState(
            MediaPermissionFacts(
                audioGranted = scanner.hasAudioPermission(),
                videoGranted = scanner.hasVideoPermission(),
                partialVisual = MediaStoreScanRules.isPartialVisualAccess(
                    sdkInt = Build.VERSION.SDK_INT,
                    hasFullVideoPermission = isGranted(Manifest.permission.READ_MEDIA_VIDEO),
                    hasUserSelectedVisualPermission =
                    isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                ),
                rationale = rationale(activity, unrequestedMediaPermission()),
                asked = scanner.requiredPermissions().any { hasAsked(it) },
            ),
        )

    /**
     * 「所有文件访问」没有中间态：要么系统版本没有这一项，要么开着，要么关着。
     *
     * 关着时报 [PermissionState.NOT_ASKED] 而不是别的新状态：它没有「被拒绝」这回事
     * （从来没有申请过它），而且 [PermissionRules.canAskInPlace] 对它的 `false` 已经
     * 把动作定成了「去设置」，不需要状态再表达一遍。
     */
    private fun allFilesState(): PermissionState = when {
        !storage.supported() -> PermissionState.UNSUPPORTED
        storage.hasAllFilesAccess() -> PermissionState.GRANTED
        else -> PermissionState.NOT_ASKED
    }

    private fun notificationState(activity: Activity?): PermissionState {
        if (Build.VERSION.SDK_INT < VERSION_TIRAMISU) return PermissionState.UNSUPPORTED
        return PermissionRules.stateOf(
            PermissionFacts(
                granted = isGranted(Manifest.permission.POST_NOTIFICATIONS),
                rationale = rationale(activity, Manifest.permission.POST_NOTIFICATIONS),
                asked = hasAsked(Manifest.permission.POST_NOTIFICATIONS),
            ),
        )
    }

    private fun bluetoothState(activity: Activity?): PermissionState {
        if (Build.VERSION.SDK_INT < VERSION_S) return PermissionState.UNSUPPORTED
        return PermissionRules.stateOf(
            PermissionFacts(
                granted = isGranted(Manifest.permission.BLUETOOTH_CONNECT),
                rationale = rationale(activity, Manifest.permission.BLUETOOTH_CONNECT),
                asked = hasAsked(Manifest.permission.BLUETOOTH_CONNECT),
            ),
        )
    }

    /**
     * 拿**当前版本真的会申请的那一条**去问系统「该不该显示理由」。
     *
     * 不能写死 `READ_MEDIA_AUDIO`：≤32 上申请的是 `READ_EXTERNAL_STORAGE`，
     * 拿一个不存在的权限名去问，系统永远答 `false`，于是「拒绝过一次」被读成
     * 「没申请过」，界面就会一直劝用户「申请」，而点下去什么都不会发生。
     *
     * 走 `scanner.requiredPermissions()` 而不是在这里再写一遍版本分支：那一份版本
     * 判断已经有三处（扫描器、这里、将来的界面），多一处就多一处会漂移的地方。
     */
    private fun unrequestedMediaPermission(): String {
        val request = scanner.requiredPermissions()
        return request.firstOrNull { !isGranted(it) } ?: request.first()
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 系统还会不会为这一条弹框。
     *
     * 没有 `Activity` 时只能答 `false`（见 [snapshot] 的说明）——**不能**答 `true`：
     * 那会让「被永久拒绝」显示成「可以再申请」，而这是两句话里更坏的那一种错
     * （它承诺了一件不会发生的事）。
     */
    private fun rationale(activity: Activity?, permission: String): Boolean =
        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    private fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", appContext.packageName, null),
    )

    private fun hasAsked(permission: String): Boolean = prefs.getBoolean(askedKey(permission), false)

    /**
     * 「问过没有」的存储键。
     *
     * 去掉包名前缀只留最后一段（`READ_MEDIA_AUDIO`）：这些键人会去 `adb shell run-as …`
     * 或备份文件里读，短名字一眼能认，而权限名本身在各类之间不会重名。
     */
    private fun askedKey(permission: String): String = "asked." + permission.substringAfterLast('.')

    private companion object {
        /**
         * 单独一份 `SharedPreferences`，**不**混进 `msp_settings` 那份 DataStore。
         *
         * 两件事分开的理由：「问过没有」是系统状态的镜像，不是用户的设置——
         * 用户改了主题不该让权限记忆跟着重建，用户清了应用数据则两份一起没，正合适。
         * 也正因为它是 SharedPreferences（同步读、进程内），[markAsked] 才能在
         * 调起系统框之前完成，而不用挂一个 suspend。
         */
        const val FILE_NAME = "msp_permissions"

        const val KEY_STARTUP_ASKED = "startup.asked"

        // 写成字面量而不是 Build.VERSION_CODES.*：让「这条分支对应哪个版本」
        // 在阅读时无需跳转就能确认（MediaStoreScanRules 里是同样的写法）。
        const val VERSION_S = 31
        const val VERSION_TIRAMISU = 33
    }
}
