package com.multisuperplayer.core.data.external

import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PendingExternalPlayback"

/**
 * 「有外部请求进来，还没人播它」这一个状态。
 *
 * ## 为什么是一个 Koin `single` 而不是 ViewModel
 *
 * 收到 `Intent` 的地方是 `MainActivity`（`onCreate` / `onNewIntent`），
 * 而真正播放的地方是 Compose 里的 `MspApp`（它拿着 `PlaybackController` 和
 * `NavController`）。两边分属不同的生命周期对象：
 *
 * - `MainActivity` 收到请求时，Compose 可能还没组合出来（冷启动），
 *   所以**不能直接调用**播放；
 * - 界面可能在收到请求时正停在任意页面（甚至播放页），
 *   所以也**不能**假设「重新组合时一定会重新读一次 Intent」。
 *
 * 一个进程级的 `StateFlow` 正好连接这两头：写在收 Intent 的地方，
 * 读在 `MspApp` 的一个 `LaunchedEffect` 里。用 ViewModel 的话，
 * `koinViewModel()` 的作用域取决于 `LocalViewModelStoreOwner`
 * （根组合是 Activity、`NavHost.composable{}` 里是 `NavBackStackEntry`），
 * 同一个类型在两个位置拿到的是**两个实例**——这一条在这个项目里已经踩过。
 *
 * ## 值一直留着直到被消费
 *
 * 冷启动时 `MspApp` 的组合比 `onNewIntent` 晚（也可能早，取决于进程是否活着）。
 * 两边谁先谁后都不影响正确性：`StateFlow` 的当前值会在订阅的那一刻发给收集方，
 * 所以「先收到请求、后组合」也能拿到。消费方播完必须调 [consume]，
 * 否则配置变更（旋转屏幕）重新组合时会**重播一次**。
 */
class PendingExternalPlayback(private val resolver: ExternalMediaResolver) {

    private val _entries = MutableStateFlow<List<MediaEntry>?>(null)

    /** 待播放的条目；没有待播请求时是 `null`。 */
    val entries: StateFlow<List<MediaEntry>?> = _entries.asStateFlow()

    /**
     * 解析并登记一次外部请求。
     *
     * ## 为什么是「全部入队」而不是「只播第一个」
     *
     * 多选分享（一次分享一整个文件夹的视频）是这个入口最常见的用法，
     * 只播第一个等于把用户选的其他文件悄悄丢掉；而入队当播放列表既保住了
     * 用户的选择，又保留了「从第一个开始播」这个直觉。
     *
     * 解析结果为空（分享的全是图片、或者一个都没读出来）时**什么都不做**：
     * 不留一个空的待播状态，免得下游多一种要判的空值。
     */
    suspend fun submit(payload: ExternalPayload) {
        val resolved = resolver.resolve(payload)
        if (resolved.isEmpty()) {
            MspLog.w(TAG) { "外部请求里没有可播放的条目：${payload.action}（收了 ${payload.refs.size} 条）" }
            return
        }
        MspLog.i(TAG) { "收到外部播放请求：${payload.action}，共 ${resolved.size} 项" }
        _entries.value = resolved
    }

    /** 消费方已经接手，清掉待播状态。 */
    fun consume() {
        _entries.value = null
    }
}
