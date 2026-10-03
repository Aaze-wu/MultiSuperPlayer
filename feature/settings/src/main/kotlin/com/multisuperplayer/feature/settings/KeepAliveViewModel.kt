package com.multisuperplayer.feature.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import com.multisuperplayer.core.data.power.DeviceVendor
import com.multisuperplayer.core.data.power.KeepAliveAccess
import com.multisuperplayer.core.data.power.KeepAliveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「后台保活」页的状态与三个动作。
 *
 * ## 为什么这里的状态可以住在 ViewModel 里（和 `AppPermissions` 不一样）
 *
 * `AppPermissions` 把状态搬进 Koin 单例，是因为它有**两个消费者**（应用根的 launcher
 * 和权限页），而它们拿到的是两个不同的 ViewModel 实例。这里没有这个问题：
 * 拨开关弹出来的系统框是这一页自己 `startActivity` 的，没有第二个观察者。
 * 所以状态跟着页面走就够了，不必再引入一个长命单例——单例是要还的，
 * 它会带来「这一页刷新了，别处那份是谁在管」这类新问题。
 *
 * ## 为什么每个动作都要一个 `Activity?`
 *
 * 三个动作都是 `startActivity`，而系统**不保证**目标页面存在：
 * - 申请白名单那个框在少数 ROM 上被吃掉了；
 * - 厂商页面在系统大版本升级后换了包名。
 *
 * 所以每个动作都带兜底（见各自的 KDoc）。传 `null`（Activity 已经没了）时直接不做——
 * 用 `applicationContext.startActivity` 从后台拉起一个系统设置页会抛
 * `AndroidRuntimeException`，而且就算不抛也是个不该发生的行为。
 */
class KeepAliveViewModel(
    private val keepAlive: KeepAliveAccess,
) : ViewModel() {

    private val _state = MutableStateFlow(keepAlive.state())

    /** 现在在不在白名单里。 */
    val state: StateFlow<KeepAliveState> = _state.asStateFlow()

    /**
     * 这台设备是哪家厂商。**不是**流：`Build.MANUFACTURER` 在一次进程生命周期里不会变，
     * 做成流只会让界面多一次无意义的订阅。
     */
    val vendor: DeviceVendor = keepAlive.vendor()

    /**
     * 重新问一次系统。
     *
     * 从系统设置页回来时必须调（`ON_RESUME`）：白名单是**这一页之外**发生的改变，
     * 没有任何回调会通知我们——和「所有文件访问」是同一类状态
     * （见 `StorageAccess` 的类注释）。
     */
    fun refresh() {
        _state.value = keepAlive.state()
    }

    /**
     * 拨开开关：请系统弹「允许它在后台不受限制地运行吗」。
     *
     * 弹不出来（少数 ROM 没有这个框）就退到系统「电池优化」名单页，
     * 让用户自己在里面找本应用。**不能什么都不做**：那样用户看到的是
     * 「开关拨了又弹回去」（`refresh` 之后状态没变），而原因在界面之外。
     */
    fun requestUnrestricted(activity: Activity?) {
        val target = activity ?: return
        if (keepAlive.open(target, keepAlive.requestIntent())) return
        keepAlive.open(target, keepAlive.optimizationListIntent())
    }

    /**
     * 拨回开关：把人送到系统的「电池优化」名单页。
     *
     * 这里**没有**「退出白名单」的 API 可调（系统只允许应用申请加入，移出是用户在
     * 系统页面里的操作），所以这个动作的实际内容就是跳页。界面上的说明文案
     * （`msp_keep_alive_switch_note`）必须把这一点说清楚，否则用户会以为
     * 拨回去就等于已经退出了。
     */
    fun openOptimizationList(activity: Activity?) {
        val target = activity ?: return
        keepAlive.open(target, keepAlive.optimizationListIntent())
    }

    /**
     * 厂商后台管理页：逐个候选试，一个都打不开就退到「应用信息」页。
     *
     * 兜底为什么是「应用信息」而不是系统设置首页：厂商页里要改的是
     * 「这个应用能不能自启动」，而应用信息页是**唯一**所有 ROM 都有的、
     * 通往应用级设置的入口（部分 ROM 的电池设置在那一页里）。
     */
    fun openVendorSettings(activity: Activity?) {
        val target = activity ?: return
        if (keepAlive.openVendorSettings(target)) return
        keepAlive.open(target, keepAlive.appDetailsIntent())
    }
}
