package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.history.RecentPlayRepository
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.model.RecentPlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 最近播放页的界面状态。
 *
 * [rows] 是**可空**的，null 表示还没读到。空列表是另一回事（确实没有记录）。
 * 把这两件事混成一个空列表，用户每次进入这一页都会先看到「还没有播放记录」，
 * 然后一帧之后记录冒出来——一个纯粹的视觉效果，但它会让人怀疑记录是不是丢了。
 */
data class RecentUiState(
    val rows: List<RecentPlay>? = null,
    /** 媒体库根本没数据（没给权限或读失败），这时「没有记录」是误导。 */
    val blocked: Boolean = false,
    /**
     * 用户在设置里把「记录最近播放」关掉了。
     *
     * 它和 [blocked] 是两回事，所以另开一位而不是复用：`blocked` 说的是「我拿不到」，
     * 这一位说的是「你自己让我别记的」。同一个空页面挂上完全不同的一句话——
     * 前者要用户去授权，后者要用户去设置里把开关打开，教错了就是死循环。
     */
    val disabled: Boolean = false,
) {
    val loading: Boolean get() = rows == null
}

/**
 * 把「媒体库状态 + 读到的记录 + 开关」拼成界面状态。
 *
 * 规则只有四条，但每一条都有理由：
 *
 * - 开关关着：不管其它状态，直说「没在记录」。用户刚刚亲手关掉了它，此时给他
 *   看「还没有播放记录」等于把开关这件事从他记忆里抹掉。
 * - 库 `Ready`：显示读到的（哪怕还是空的，那就是真的没有）。
 * - 库 `Loading`：**什么都不显示**（转圈）。这时候去读记录只会得到空，
 *   而把「还不知道」说成「没有」是最容易被当成 bug 的一种说法。
 * - 其它（没权限 / 读失败）：说清是「拿不到媒体库」而不是「没有记录」。
 *
 * 最后一条是必须的：`reload()` 会一直等着库变成 Ready，而没权限时它永远不会变，
 * 于是 `rows` 永远是 null——不做区分的话这一页就永远在转圈。
 *
 * @param enabled 「记录最近播放」开关。默认 `true` 是**正常情况**，不是「兜底」：
 *   它对应 `PlaybackSettings.recordRecentPlays` 的默认值，两者必须一致。
 */
internal fun recentUiState(
    library: MediaLibraryState,
    rows: List<RecentPlay>?,
    enabled: Boolean = true,
): RecentUiState = when {
    !enabled -> RecentUiState(rows = emptyList(), disabled = true)
    library is MediaLibraryState.Ready -> RecentUiState(rows = rows)
    library is MediaLibraryState.Loading -> RecentUiState(rows = null)
    else -> RecentUiState(rows = emptyList(), blocked = true)
}

/**
 * 最近播放页的 ViewModel。
 *
 * `RecentPlayRepository.recent()` 是一次性挂起调用（不是 Flow）：它把续播记录和
 * 当前媒体库对一次。所以这里在「库就绪」之后读一次并缓存，之后靠 [refresh] 重读。
 * 之所以要等库就绪：库没扫完时它一律返回空，等待比返回空更接近事实。
 *
 * [refresh] 必须由界面在**每次回到前台/切回本页**时调一次：这个 ViewModel 挂在
 * 导航栈上的「最近」入口上，而那个入口在切标签时**不会**被销毁（见 `MspApp` 里的
 * 长注释），所以 `init` 里的那一次读取只代表「应用启动时的样子」。不刷新的话，
 * 用户刚播完一条回到列表，看到的还是播放之前的快照——记录明明写进了磁盘。
 */
class RecentViewModel(
    private val recent: RecentPlayRepository,
    private val library: MediaLibraryRepository,
    playbackSettings: PlaybackSettingsRepository,
) : ViewModel() {

    private val cached = MutableStateFlow<List<RecentPlay>?>(null)

    /**
     * 「记录最近播放」开关。
     *
     * 用 `Eagerly` + 同步可读的 `value`：[reload] 要在读磁盘**之前**就知道开关的
     * 状态，否则开关关着时还会白白读一次盘（而且读出来的东西马上被丢掉）。
     */
    private val enabled: StateFlow<Boolean> = playbackSettings.settings
        .map { it.recordRecentPlays != false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val uiState: StateFlow<RecentUiState> = combine(
        library.state,
        cached,
        enabled,
        ::recentUiState,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = RecentUiState(),
    )

    init {
        reload()
    }

    fun refresh() = reload()

    /**
     * 删掉一条记录。
     *
     * 删的是**磁盘上的记录**（连带它的续播位置，见 [RecentPlayRepository]），
     * 不是只从列表里拿掉一行——所以这里做完之后要重读一次，而不是本地过滤。
     * 万一读失败（库不再就绪），列表会退回「拿不到」而不是「少了你删的那一条」，
     * 那是更诚实的画面。
     *
     * 界面负责在这之后弹一句带「撤销」的提示，并把**这一条**原样交给
     * [undoDelete]——撤销要还的是那一条，见 [undoDelete]。
     */
    fun delete(row: RecentPlay) {
        viewModelScope.launch {
            recent.remove(row)
            readRows()
        }
    }

    /**
     * 撤销一次删除，把 [row] 原样写回去（位置和时间戳都按删除前的样子）。
     *
     * 参数是**那一条记录本身**，不是「最后删掉的那条」之类由 ViewModel 记住的
     * 状态。连着删两条时界面会先后弹两句提示，第二句会把第一句顶掉；如果撤销去读
     * 一个共享的「最后删除」字段，第一句提示上的「撤销」会还原**第二条**，
     * 于是用户既没撤销成功、第一条记录又永远回不来了。把记录跟着提示走，
     * 每个提示就都是自洽的。
     */
    fun undoDelete(row: RecentPlay) {
        viewModelScope.launch {
            recent.restore(row)
            readRows()
        }
    }

    /**
     * 清空全部记录。
     *
     * 这里直接把 [cached] 置空而不重读：清空之后存储里一条记录都不剩，而这一页
     * 显示的只能是「记录 ∩ 媒体库」，所以「空」是**已知的事实**，不需要再等
     * 媒体库就绪。重读反而会在库没就绪时把这个确定的结论拖成转圈。
     */
    fun clearAll() {
        viewModelScope.launch {
            recent.clearAll()
            cached.value = emptyList()
        }
    }

    private fun reload() {
        viewModelScope.launch { readRows() }
    }

    /** 读一次记录并要求媒体库已就绪。失败/未就绪的语义见 `recentUiState`。 */
    private suspend fun readRows() {
        // 开关关着就没什么可显示的，读了也是白读（见 [enabled]）。
        // 这里不清空 [cached] 里已有的内容：重新打开开关时界面会先显示
        // 上一次读到的列表，然后由下面的重读把它对齐——比先闪一个空列表好。
        if (!enabled.value) return
        library.state.filterIsInstance<MediaLibraryState.Ready>().first()
        cached.value = recent.recent()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
