package com.multisuperplayer.feature.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.update.InstallLaunch
import com.multisuperplayer.core.data.update.UpdateAvailability
import com.multisuperplayer.core.data.update.UpdateChannel
import com.multisuperplayer.core.data.update.UpdateException
import com.multisuperplayer.core.data.update.UpdateFailureText
import com.multisuperplayer.core.data.update.UpdateInstaller
import com.multisuperplayer.core.data.update.UpdateManager
import com.multisuperplayer.core.data.update.UpdateProgress
import com.multisuperplayer.core.data.update.UpdateRelease
import com.multisuperplayer.core.data.update.UpdateSettings
import com.multisuperplayer.core.data.update.describeUpdateFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「检查更新」页的状态。
 *
 * [progress] 非 null 就等于「正在下载」——不另设一个 `downloading: Boolean`：
 * 两个字段表达同一件事时，总有一天会只更新其中一个，界面就会出现
 * 「进度条停住了但按钮还写着正在下载」。
 */
data class UpdateUiState(
    val availability: UpdateAvailability = UpdateAvailability.NotChecked,
    val checking: Boolean = false,
    val progress: UpdateProgress? = null,
    val failure: UpdateFailureText? = null,
    val canInstallPackages: Boolean = true,
)

/**
 * 「检查更新」页的动作与状态。
 *
 * ## 为什么下载完的包住在 ViewModel 里而不是 DataStore 里
 *
 * 它是一份有生命周期的临时文件（在 `cacheDir/updates/`，系统可以回收），
 * 记进设置只会在下次启动时指向一个已经被删掉的文件。放在内存里意味着
 * 进程被杀之后要重下一遍——这是**正确**的取舍：重下一遍花流量，
 * 安装一个来源不明的残留文件花的是安全。
 *
 * ## 事件（拉起系统页面）为什么走 Channel 而不是状态
 *
 * 「打开未知来源授权页」「拉起安装器」都是一次性动作。做成状态的话，
 * 每次重组都会重新触发（旋转屏幕就能弹出第二个安装器）；做成 Channel
 * 则每个事件只会被消费一次。
 */
class UpdateViewModel(
    private val manager: UpdateManager,
    private val installer: UpdateInstaller,
    val buildInfo: AppBuildInfo,
) : ViewModel() {

    val settings: StateFlow<UpdateSettings> = manager.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = manager.defaultSettings,
    )

    private val _state = MutableStateFlow(UpdateUiState(canInstallPackages = installer.canInstall()))
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _launch = Channel<Intent>(Channel.BUFFERED)

    /** 要拉起的系统页面（安装器 / 未知来源授权页），界面收到就 `startActivity`。 */
    val launch: Flow<Intent> = _launch.receiveAsFlow()

    /** 已下载并通过校验的包。 */
    private var pendingApk: File? = null

    /** 「去开未知来源」→ 用户回来后自动接着装，但只自动接一次。 */
    private var autoResumedInstall = false

    fun refresh() {
        val allowed = installer.canInstall()
        val wasBlocked = !_state.value.canInstallPackages
        _state.update { it.copy(canInstallPackages = allowed) }

        // 用户刚刚去把「安装未知应用」打开然后回来了。这时他唯一想做的事
        // 就是接着装——再让他点一次「安装」是多余的，而他会以为刚才那一下丢了。
        // 只自动接一次：装完（或又失败）之后不再自己弹，否则这一页会反复
        // 拉起安装器，看起来像按钮卡住了。
        if (allowed && wasBlocked && !autoResumedInstall && pendingApk != null) {
            autoResumedInstall = true
            install()
        }
    }

    /**
     * 查一次。
     *
     * @param manual 用户亲手点的（true）还是进页面顺手查的（false，受 12 小时节流）。
     */
    fun check(manual: Boolean = true) {
        if (_state.value.checking || _state.value.progress != null) return
        _state.update { it.copy(checking = true, failure = null) }
        viewModelScope.launch {
            try {
                // null = 被节流跳过。**必须什么都不改**：把它当成「已是最新」
                // 会让每次进这一页都把「有新版」擦掉一次。
                val result = manager.check(manual) ?: return@launch
                // 用户可能在这一次请求期间按了「忽略」，那时判定结果已经过时。
                if (_state.value.availability is UpdateAvailability.Ignored && result is UpdateAvailability.Available) {
                    return@launch
                }
                _state.update { it.copy(availability = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                // 失败也要留一行日志。界面文案只说「哪一类错」，而定位时要的是
                // 「哪一个请求、什么原因」——实测在模拟器上连续两次下载失败，
                // logcat 里一条自己的记录都没有，排查全靠看图。
                MspLog.w(TAG, e) { "检查更新失败：${e.javaClass.simpleName}" }
                _state.update { it.copy(failure = e.describeUpdateFailure()) }
            } catch (e: Exception) {
                // 兜底。`viewModelScope` 上的未捕获异常 = **进程崩溃**，而这一页
                // 恰恰是用户「想修好这个应用」才进来的，崩在这里尤其难看。
                // 数据源已经把 IOException 归成 Network 了，这一层只是保证
                // 「将来新加的任何异常类型」都不会变成闪退。
                MspLog.w(TAG, e) { "检查更新时出现未预期的异常" }
                _state.update { it.copy(failure = UpdateFailureText.Network) }
            } finally {
                _state.update { it.copy(checking = false) }
            }
        }
    }

    /** 下载并校验当前可用的那一版。 */
    fun download() {
        val release = availableRelease() ?: return
        if (_state.value.progress != null) return
        _state.update { it.copy(progress = UpdateProgress(0, release.apkSizeBytes), failure = null) }
        viewModelScope.launch {
            try {
                val apk = manager.downloadAndVerify(release) { p ->
                    _state.update { it.copy(progress = p) }
                }
                pendingApk = apk
                _state.update { it.copy(progress = null) }
                install()
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                // 进度必须在这里清掉：失败之后进度条还挂在 43% 是最容易被误读成
                // 「还在下」的画面，而用户会一直等。
                MspLog.w(TAG, e) { "下载更新包失败：${e.javaClass.simpleName}" }
                _state.update { it.copy(progress = null, failure = e.describeUpdateFailure()) }
            } catch (e: Exception) {
                // 同 `check()`：下载阶段真的崩过一次（`SocketException` 从
                // `HttpURLConnection` 里逃出来），而现在下载器已经包装了 IOException，
                // 这层是给「下次新加的异常」留的。
                MspLog.w(TAG, e) { "下载更新包时出现未预期的异常" }
                _state.update { it.copy(progress = null, failure = UpdateFailureText.Network) }
            }
        }
    }

    /** 把已下载的包交给系统安装器。 */
    fun install() {
        val apk = pendingApk ?: return
        when (val prepared = installer.prepareInstall(apk)) {
            is InstallLaunch.Ready -> {
                _launch.trySend(prepared.intent)
            }

            InstallLaunch.NeedsUnknownSourcePermission -> {
                _state.update { it.copy(canInstallPackages = false) }
                _launch.trySend(installer.unknownSourceSettingsIntent())
            }

            InstallLaunch.NoInstaller -> {
                _state.update { it.copy(failure = UpdateFailureText.NoInstaller) }
            }
        }
    }

    /** 用户点「去设置」：打开本应用的「安装未知应用」授权页。 */
    fun openUnknownSourceSettings() {
        _launch.trySend(installer.unknownSourceSettingsIntent())
    }

    /**
     * 拉起系统页面失败（目标不存在 / 被 ROM 拦住）。
     *
     * 落到「没有安装器」这一条上：它能说的就是「这台机器现在装不了」，
     * 而用户能做的是去设置里找找。比一个空白反应好。
     */
    fun onLaunchFailed() {
        _state.update { it.copy(failure = UpdateFailureText.NoInstaller) }
    }

    fun ignore() {        val release = availableRelease() ?: return
        viewModelScope.launch {
            manager.setIgnoredTag(release.tagName)
            _state.update { it.copy(availability = UpdateAvailability.Ignored(release)) }
        }
    }

    fun undoIgnore() {
        viewModelScope.launch {
            manager.setIgnoredTag(null)
            // 撤销之后必须重新问一次，而不是直接把状态改成「已是最新」——
            // 被忽略的那一版（以及它之后发布的版本）到底算不算更新，只有规则层知道。
            _state.update { it.copy(availability = UpdateAvailability.NotChecked) }
            check(manual = true)
        }
    }

    fun setChannel(channel: UpdateChannel) {
        viewModelScope.launch {
            manager.setChannel(channel)
            // 换了通道之后原来的判定就不成立了：
            // 从「预发行版」切到「正式版」时，界面上那个预发行版必须消失。
            _state.update { it.copy(availability = UpdateAvailability.NotChecked) }
            check(manual = true)
        }
    }

    fun setAutoCheck(enabled: Boolean) {
        viewModelScope.launch { manager.setAutoCheck(enabled) }
    }

    fun putToken(input: String) {
        viewModelScope.launch { manager.putToken(input) }
    }

    fun clearToken() {
        viewModelScope.launch { manager.clearToken() }
    }

    fun dismissFailure() {
        _state.update { it.copy(failure = null) }
    }

    private fun availableRelease(): UpdateRelease? =
        (_state.value.availability as? UpdateAvailability.Available)?.release

    private companion object {
        const val TAG = "UpdateViewModel"
    }
}
