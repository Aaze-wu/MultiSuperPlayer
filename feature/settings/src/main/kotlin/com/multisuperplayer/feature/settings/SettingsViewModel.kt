package com.multisuperplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.settings.ThemeSettings
import com.multisuperplayer.core.data.settings.ThemeSettingsRepository
import com.multisuperplayer.core.ui.theme.MspAccent
import com.multisuperplayer.core.ui.theme.MspBaseTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "SettingsViewModel"

/**
 * 设置页的状态。
 *
 * 这里**只装载当前内核已经会读的设置项**。像「字幕字号」「翻译服务地址」这些
 * 还没有消费者的项，等对应的内核做实了再加——写一个没人读的开关，
 * 用户拨它只会得到一个「看起来生效了但什么都没发生」的界面。
 */
class SettingsViewModel(
    private val themeSettings: ThemeSettingsRepository,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    /**
     * 用 `Eagerly` 而不是 `WhileSubscribed`：主题要在界面出现之前就位，
     * 否则会先闪一下默认主题再跳到用户选的主题。
     *
     * 初值用 `ThemeSettings()`（全空）而不是去读盘等第一帧：全空恰好就是
     * 「全部用 UI 层默认值」，和「用户没设置过」等价，所以第一帧不会跳。
     */
    val theme: StateFlow<ThemeSettings> = themeSettings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettings())

    fun selectBaseTheme(theme: MspBaseTheme) = persist("主题基底=${theme.id}") {
        themeSettings.setBaseTheme(theme.id)
    }

    fun selectAccent(accent: MspAccent) = persist("强调色=${accent.id}") {
        themeSettings.setAccent(accent.id)
    }

    fun setDynamicColor(enabled: Boolean) = persist("系统取色=$enabled") {
        themeSettings.setUseDynamicColor(enabled)
    }

    fun setColorFromArtwork(enabled: Boolean) = persist("封面取色=$enabled") {
        themeSettings.setColorFromArtwork(enabled)
    }

    /**
     * 写盘失败不能只吞掉——那会表现为「点了没反应」，而且**下次启动又变回去**，
     * 用户完全无从判断是没点到还是没存上。所以至少要留一条日志。
     *
     * 不弹 Toast/Snackbar：主题偏好属于低风险设置，为它打断操作不值得；
     * 真出问题（磁盘满、文件损坏）日志里能查到。
     */
    private fun persist(what: String, block: suspend () -> Unit) {
        viewModelScope.launch(dispatchers.io) {
            runCatching { block() }
                .onFailure { error ->
                    MspLog.w(TAG, error) { "保存设置失败（$what）" }
                }
        }
    }
}
