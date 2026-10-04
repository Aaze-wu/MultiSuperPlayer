package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.remote.RemoteUrlRules
import com.multisuperplayer.core.data.remote.RemoteUrlStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 网络地址页的状态。
 *
 * ## 「能不能播」是算出来的，不是一个字段
 *
 * [url] 每次都现算（[RemoteUrlRules.normalize] 本身是纯函数、开销可以忽略），
 * 而不是在 `onValueChange` 里写一份缓存。分开存必然会分叉：用户在地址框里删掉一个
 * 字符之后，缓存说「能播」而地址已经不合法——按下去就是一次注定失败的播放。
 *
 * [invalid] 只在**框里有东西**的时候才为真：空框不是「输错了」，不该一进来就报错。
 */
data class NetworkUiState(
    /** 地址框里的原文（没规范化过，用户看到的就是自己敲的）。 */
    val input: String = "",
    /** 历史地址，最新的在前；`null` = 还没读出来（和 `BrowseUiState.roots` 同一个约定）。 */
    val history: List<String>? = null,
) {

    /** 规范化之后的地址；`null` = 现在还不能播。 */
    val url: String? get() = RemoteUrlRules.normalize(input)

    /** 框里有东西，但那不是一个能播放的地址。 */
    val invalid: Boolean get() = input.isNotBlank() && url == null
}

/**
 * 网络地址（手输 URL 直接播放）页。
 *
 * ## 为什么没有「加载历史」这一步
 *
 * 历史是 `RemoteUrlStore` 的一条 [StateFlow] 流出来的，进来就订阅、离开就断
 * （`WhileSubscribed`），不需要用户点一下刷新。第一次发射是**异步的**（DataStore 要走磁盘），
 * 所以状态里用的是 `List?`：`null` 表示「还在读」，空列表表示「确实一条都没有」。
 * 两者在界面上不一样（转圈 vs 空状态），合成一个就必然有一边是错的。
 *
 * ## 为什么输入框的内容住在 ViewModel 里
 *
 * 它同时要撑过两件事：**键盘弹出引起的重排**和**转屏**。放进 `remember` 会在转屏时丢，
 * 放进 `rememberSaveable` 又得为一个字符串写 Saver；ViewModel 天生就活得比这两个都久，
 * 而这一页的 ViewModel 本来就只为这一页存在。
 */
class NetworkViewModel(private val remoteStore: RemoteUrlStore) : ViewModel() {

    private val input = MutableStateFlow("")

    private val history: StateFlow<List<String>?> = remoteStore.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val uiState: StateFlow<NetworkUiState> = combine(input, history, ::NetworkUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NetworkUiState())

    fun setInput(value: String) {
        input.value = value
    }

    /**
     * 记下一条地址（已有的挪到最前面）。
     *
     * 调用时机是「用户真的按了播放」，而不是「他什么时候输完的」：跟着输入记的话，
     * 敲一个字符写一次盘，而且半截地址也会进历史。
     */
    fun remember(url: String) {
        viewModelScope.launch { remoteStore.add(url) }
    }

    fun remove(url: String) {
        viewModelScope.launch { remoteStore.remove(url) }
    }

    private companion object {
        /**
         * 离开页面多久之后断开历史订阅再回来重新读。
         *
         * 和历史同源的 `BrowseViewModel` / `LibraryViewModel` 用同一个值：留一点
         * 余量是为了「进播放页再返回」这种几秒的往返不重新订阅一次磁盘。
         */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
