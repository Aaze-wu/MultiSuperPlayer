package com.multisuperplayer.feature.player

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.permissions.AppPermissions
import com.multisuperplayer.core.data.permissions.PermissionKind
import com.multisuperplayer.core.data.settings.DesktopLyricsSettings
import com.multisuperplayer.core.data.settings.DesktopLyricsSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "DesktopLyrics"

/**
 * 桌面歌词的「开关」：决定浮窗此刻该不该在，然后开/关 [DesktopLyricsWindow]。
 *
 * ## 为什么不是 Service
 *
 * 挂 `TYPE_APPLICATION_OVERLAY` 窗口只需要一个有 `SYSTEM_ALERT_WINDOW` 权限的
 * `Context`，用 application context 挂的窗口不会随 Activity 销毁而消失。所以
 * 不需要前台服务：播放中的进程本来就活在 `MspPlaybackService` 的前台服务里，
 * 而不在播放时浮窗也没有意义。省掉一个前台服务就省掉一条
 * `FOREGROUND_SERVICE_*` 权限、一条用户可见的常驻通知，以及「凭什么它一直在跑」
 * 的质疑。
 *
 * ## 为什么由它自己观察前台状态
 *
 * 「离开应用才显示」是用户选的模式，而「应用在前台」这件事只有 Activity 生命
 * 周期知道。用 `registerActivityLifecycleCallbacks` 而不是 `ProcessLifecycleOwner`：
 * 后者要 `lifecycle-process` 依赖，而这里只数一下「有几个 Activity 在 resumed」。
 *
 * 计数为 0 ≠ 立刻离开——应用内换页会先 pause 再 resume，中间那几毫秒要是也当成
 * 「离开了」，每次点开另一个页面都会闪一块歌词出来。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DesktopLyricsOverlayHost(
    context: Context,
    private val controller: DesktopLyricsController,
    private val settings: DesktopLyricsSettingsRepository,
    private val permissions: AppPermissions,
) {

    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val window = DesktopLyricsWindow(appContext, controller)

    /**
     * 「现在能不能画悬浮窗」。
     *
     * **只有这一个地方读** 它（`sync` 和 [entry] 都从这里取），因为它同时决定
     * 浮窗挂不挂得上、播放页上那个按钮说什么——两处各读一次会在用户授权回来的
     * 那一帧里出现「按钮已经说已开启、浮窗却还没出现」。
     *
     * 初值在构造时读一次（此时进程刚起来，读到什么就是什么），之后**只在
     * `onActivityResumed` 重读**：系统没有提供任何回调，用户改完权限必然要从
     * 那个页面回到我们这里、而我们自己也不会在后台被改。
     */
    private val permissionGranted = MutableStateFlow(Settings.canDrawOverlays(appContext))

    /**
     * 播放页控制条上那个按钮要的全部状态。
     *
     * 用 `Eagerly`：控制条一出现就要显示正确的那一档，`WhileSubscribed` 的首帧
     * 会让按钮先闪一下「关」。
     */
    internal val entry: StateFlow<DesktopLyricsEntryState> = combine(
        settings.settings,
        permissionGranted,
    ) { desktop, granted ->
        DesktopLyricsEntryState(
            enabled = desktop.enabled,
            locked = desktop.locked,
            permissionGranted = granted,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DesktopLyricsEntryState())

    /** 有几个 Activity 处在 resumed。全部回调都在主线程，不需要原子类型。 */
    private var resumedCount = 0

    private val appVisible = MutableStateFlow(false)

    /**
     * 给浮窗用的「应用在前台」。
     *
     * 离开要等一下再成立（[LEAVE_GRACE_MS]）：应用内换页、弹出权限对话框
     * （授权悬浮窗时系统设置会盖上来）都会短暂地离开，立刻显示等于在那些界面上
     * 硬塞一块歌词。
     */
    private val appInBackground: StateFlow<Boolean> = appVisible
        .mapLatest { visible ->
            if (!visible) delay(LEAVE_GRACE_MS)
            !visible
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedCount++
            // 旋转/改分辨率之后窗口可能落在屏幕外；resumed 是唯一稳定的
            // 「尺寸可能变了」的信号（应用不处理配置变化时 Activity 会重建）。
            window.reclamp()
            // 悬浮窗权限、以及窗口尺寸都可能在这段时间里被改过（用户刚从系统页面回来）。
            // 写进 Flow 而不是只读一次：它会顺带触发一次 [sync]，浮窗不必等下一个
            // 播放位置回调才出现。
            permissionGranted.value = Settings.canDrawOverlays(appContext)
            appVisible.value = true
        }

        override fun onActivityPaused(activity: Activity) {
            resumedCount = (resumedCount - 1).coerceAtLeast(0)
            appVisible.value = resumedCount > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    init {
        (appContext as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
        scope.launch {
            combine(
                controller.state,
                settings.settings,
                appInBackground,
                // 权限也是输入之一：用户从系统页面回来时，可能**除此之外什么都没变**
                // （同一首歌、同一个位置、甚至还是暂停着），少这一路就会出现
                // 「授权了但要等下一次状态变化浮窗才出现」。
                permissionGranted,
            ) { state, desktop, background, granted ->
                GateInputs(state, desktop, background, granted)
            }.collect(::sync)
        }
    }

    private fun sync(inputs: GateInputs) {
        val gate = desktopLyricsGate(
            enabled = inputs.desktop.enabled,
            // 用同一个缓存值（每次 resume 重读）：和播放页那个按钮同源，
            // 不会出现「按钮说已开启、浮窗却还没出现」。
            permissionGranted = inputs.permissionGranted,
            appVisible = !inputs.appInBackground,
            hasLyrics = inputs.state.hasLyrics,
            displayMode = inputs.state.mode,
        )
        // 只在**结论变化**时打一条：这个函数跟着播放位置每秒几十次被调用，
        // 每次都打会把 logcat 冲干净。而「浮窗没出来」唯一有效的排查线索就是
        // 它是被哪一档挡住的（`TURNED_OFF` 与 `NO_LYRICS` 的修法完全不同）。
        if (gate != lastGate) {
            MspLog.d(TAG) { "浮窗结论 ${lastGate ?: "（初始）"} → $gate" }
            lastGate = gate
        }
        if (gate == DesktopLyricsGate.SHOWN) {
            window.show(inputs.desktop.position)
            window.setLocked(inputs.state.locked)
        } else {
            window.hide()
        }
    }

    /** 上一次 [sync] 得出的结论，只用于日志去重。 */
    private var lastGate: DesktopLyricsGate? = null

    /**
     * 一次 [sync] 要的全部输入。
     *
     * 打成对象而不是用 `Triple` 一路往下传：这四路里已经有两路是「一类东西」
     * （两份设置），位置写错不会有编译错误，只会在真机上表现为「改了字号浮窗没反应」。
     */
    private data class GateInputs(
        val state: DesktopLyricsUiState,
        val desktop: DesktopLyricsSettings,
        val appInBackground: Boolean,
        val permissionGranted: Boolean,
    )

    // ------------------------------------------------------------------ 播放页入口

    /**
     * 把用户送到系统的「显示在其他应用上层」页面。
     *
     * 直接用当前 `Activity` 起（不经过 `applicationContext`）：那个 context 起
     * Activity 必须带 `NEW_TASK`，而带上之后系统页面会被顶成**另一个任务**，
     * 用户按返回时回到的是桌面而不是播放页。
     *
     * `Activity` 为 `null`（已销毁）时什么都不做：从后台拉起系统页面会抛
     * `AndroidRuntimeException`，而这个动作永远来自用户手指，重新点一下即可。
     */
    internal fun openPermissionSettings(activity: Activity?) {
        val target = activity ?: return
        val launched = runCatching {
            target.startActivity(permissions.settingsIntent(PermissionKind.OVERLAY))
        }
        if (launched.isSuccess) return
        MspLog.w(TAG, launched.exceptionOrNull()) { "打不开「显示在其他应用上层」页面" }
    }

    /** 播放页按钮：把开关拨到目标值。真正的开窗由 [sync] 完成。 */
    internal fun setEnabled(enabled: Boolean) = controller.setEnabled(enabled)

    /** 播放页按钮：解锁（`enable = false` 走的就是这一条，不再另设方法）。 */
    internal fun setLocked(locked: Boolean) = controller.setLocked(locked)

    private companion object {
        /**
         * 离开应用之后等多久才把歌词浮出来。
         *
         * 300ms 足够跨过应用内换页那一次 pause→resume，又短到「按 Home 之后
         * 歌词几乎是立刻出现的」。
         */
        const val LEAVE_GRACE_MS = 300L
    }
}

/**
 * 播放页上那个入口按钮的三种状态。
 *
 * 三种都要各自说出来，不能只分「开/关」：
 * - 没有权限时开了等于没开（浮窗挂不上去），按钮必须说「需要权限」；
 * - 锁定时浮窗点不动，用户在想调位置的时刻按这一下，得到的是「已锁定」。
 */
internal data class DesktopLyricsEntryState(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    /** 默认 `false`：**保守且大概率正确**的那一档（新装的用户都没有这个权限）。 */
    val permissionGranted: Boolean = false,
) {
    /** 这一下点下去会做什么。见 [desktopLyricsEntryAction]。 */
    val action: DesktopLyricsEntryAction
        get() = desktopLyricsEntryAction(enabled, locked, permissionGranted)
}

/**
 * 从浮窗回到应用。
 *
 * 用 `getLaunchIntentForPackage` 而不是指名 `MainActivity`：那个类在 `:app`
 * 模块里，而 `feature:player` 依赖不到它（依赖方向是 app → feature）。
 * 由系统给出「这个应用的启动入口」还有一个好处：`MainActivity` 什么时候改名、
 * 加没加别名，这里一个字都不用改。
 *
 * 必须带 `NEW_TASK`：从 application context 起 Activity 而没有 NEW_TASK 会抛
 * `AndroidRuntimeException`。后台起 Activity 的限制在这里不成立——
 * 持有 `SYSTEM_ALERT_WINDOW` 权限的应用不受该限制约束，而且这本来就是用户
 * 亲手点的那一下。
 */
internal object DesktopLyricsIntents {

    fun openApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (intent == null) {
            MspLog.w(TAG) { "拿不到应用启动入口，无法从桌面歌词回到应用" }
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { MspLog.w(TAG, it) { "从桌面歌词回到应用失败" } }
    }

    private const val TAG = "DesktopLyrics"
}
