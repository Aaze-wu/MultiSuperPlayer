package com.multisuperplayer.feature.settings

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.permissions.AppPermissions
import com.multisuperplayer.core.data.permissions.PermissionKind
import com.multisuperplayer.core.data.permissions.PermissionState
import com.multisuperplayer.core.data.settings.DesktopLyricsFontSize
import com.multisuperplayer.core.data.settings.DesktopLyricsOpacity
import com.multisuperplayer.core.data.settings.DesktopLyricsSettings
import com.multisuperplayer.core.data.settings.DesktopLyricsSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "DesktopLyricsSettings"

/**
 * 「桌面歌词」设置页的状态与动作。
 *
 * ## 为什么读写都直接走仓库，而不是转给 `DesktopLyricsController`
 *
 * 那个控制器住在 `:feature:player`，而这一页在 `:feature:settings`——反过来依赖
 * 会把两个 feature 模块绑在一起（`feature:settings` 现在只依赖 `core:*`）。
 * 而且这里根本不需要它：仓库本身就是**唯一**的数据源，播放页的浮窗通过
 * 同一个 Flow 观察结果，谁写的都一样（见 [DesktopLyricsSettingsRepository] 的类注释）。
 *
 * ## 悬浮窗权限为什么要单独问
 *
 * `SYSTEM_ALERT_WINDOW` 是 special access，没有「申请」这一条路：只能把用户送到
 * 系统的「显示在其他应用上层」页面，而且**没有任何回调**告诉我们他开了没有。
 * 所以这里的做法和「后台保活」「所有文件访问」同一套：页面 `ON_RESUME` 时重读一次
 * （[refreshOverlayPermission]），而不是相信一次性的读取结果。
 */
class DesktopLyricsSettingsViewModel(
    private val repository: DesktopLyricsSettingsRepository,
    private val permissions: AppPermissions,
) : ViewModel() {

    /**
     * 用 `Eagerly` + 默认值初值：这一页一打开就要显示开关和两个当前值，
     * `WhileSubscribed` 的首帧是空的，会让开关先画成「关」再弹成「开」。
     */
    val settings: StateFlow<DesktopLyricsSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DesktopLyricsSettings())

    private val overlayFlow = MutableStateFlow(overlayGranted())

    /** 现在有没有「显示在其他应用上层」。`false` 时浮窗根本挂不上（见 `DesktopLyricsGate`）。 */
    val overlayGranted: StateFlow<Boolean> = overlayFlow.asStateFlow()

    /** 从系统页面回来时重问一次。没有回调，只有 `ON_RESUME` 这一个时机。 */
    fun refreshOverlayPermission() {
        overlayFlow.value = overlayGranted()
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(enabled) }
    }

    fun setLocked(locked: Boolean) {
        viewModelScope.launch { repository.setLocked(locked) }
    }

    fun setFontSize(size: DesktopLyricsFontSize) {
        viewModelScope.launch { repository.setFontSize(size) }
    }

    fun setOpacity(opacity: DesktopLyricsOpacity) {
        viewModelScope.launch { repository.setOpacity(opacity) }
    }

    /**
     * 恢复默认外观（字号 + 不透明度），**不动**位置和开关。
     *
     * 「恢复默认」只该撤销「我把字号调到看不清了」这类改动；连开关一起关掉的话，
     * 用户按完会以为这个功能坏了。
     */
    fun resetAppearance() {
        viewModelScope.launch { repository.resetAppearance() }
    }

    /** 丢掉摆过的位置，下次回到「水平居中、屏幕偏下」。同样不动开关。 */
    fun resetPosition() {
        viewModelScope.launch { repository.resetPosition() }
    }

    /**
     * 把用户送到系统的「显示在其他应用上层」页面。
     *
     * 传 `null`（Activity 已经没了）时什么都不做：用 `applicationContext.startActivity`
     * 从后台拉起系统页面会抛 `AndroidRuntimeException`。
     *
     * 跳不出去也不能静默：那样用户点「去开启」看到的是「没有反应」，
     * 而原因（这台设备的系统没有这个页面）在界面之外。所以失败时留一条日志，
     * 并退一步打开本应用的详情页——那里同样能走到这一项。
     */
    fun openOverlaySettings(activity: Activity?) {
        val target = activity ?: return
        val intent = permissions.settingsIntent(PermissionKind.OVERLAY)
        val launched = runCatching { target.startActivity(intent) }
        if (launched.isSuccess) return
        MspLog.w(TAG, launched.exceptionOrNull()) { "打不开「显示在其他应用上层」页面，退到应用详情页" }
        runCatching { target.startActivity(permissions.settingsIntent(PermissionKind.MEDIA)) }
            .onFailure { error -> MspLog.w(TAG, error) { "连应用详情页都打不开" } }
    }

    private fun overlayGranted(): Boolean =
        permissions.snapshot(activity = null)[PermissionKind.OVERLAY] == PermissionState.GRANTED
}
