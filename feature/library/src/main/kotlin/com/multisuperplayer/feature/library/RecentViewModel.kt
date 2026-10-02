package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.history.RecentPlayRepository
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.model.RecentPlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
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
) {
    val loading: Boolean get() = rows == null
}

/**
 * 把「媒体库状态 + 读到的记录」拼成界面状态。
 *
 * 规则只有三条，但每一条都有理由：
 *
 * - 库 `Ready`：显示读到的（哪怕还是空的，那就是真的没有）。
 * - 库 `Loading`：**什么都不显示**（转圈）。这时候去读记录只会得到空，
 *   而把「还不知道」说成「没有」是最容易被当成 bug 的一种说法。
 * - 其它（没权限 / 读失败）：说清是「拿不到媒体库」而不是「没有记录」。
 *
 * 第三条是必须的：`reload()` 会一直等着库变成 Ready，而没权限时它永远不会变，
 * 于是 `rows` 永远是 null——不做区分的话这一页就永远在转圈。
 */
internal fun recentUiState(library: MediaLibraryState, rows: List<RecentPlay>?): RecentUiState =
    when (library) {
        is MediaLibraryState.Ready -> RecentUiState(rows = rows)
        is MediaLibraryState.Loading -> RecentUiState(rows = null)
        else -> RecentUiState(rows = emptyList(), blocked = true)
    }

/**
 * 最近播放页的 ViewModel。
 *
 * `RecentPlayRepository.recent()` 是一次性挂起调用（不是 Flow）：它把续播记录和
 * 当前媒体库对一次。所以这里在「库就绪」之后读一次并缓存，之后靠 [refresh] 手动重读。
 * 之所以要等库就绪：库没扫完时它一律返回空，等待比返回空更接近事实。
 */
class RecentViewModel(
    private val recent: RecentPlayRepository,
    private val library: MediaLibraryRepository,
) : ViewModel() {

    private val cached = MutableStateFlow<List<RecentPlay>?>(null)

    val uiState: StateFlow<RecentUiState> = combine(
        library.state,
        cached,
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

    private fun reload() {
        viewModelScope.launch {
            library.state.filterIsInstance<MediaLibraryState.Ready>().first()
            cached.value = recent.recent()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
