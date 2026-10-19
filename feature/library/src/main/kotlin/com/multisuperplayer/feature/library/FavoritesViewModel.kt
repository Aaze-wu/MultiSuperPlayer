package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.favorites.Favorite
import com.multisuperplayer.core.data.favorites.FavoriteRules
import com.multisuperplayer.core.data.favorites.FavoriteStore
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.text.MspText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 收藏页的界面状态。
 *
 * [favorites] 是**可空**的，null 表示还没读到（DataStore 第一次读盘）。
 * 空列表是另一回事：确实一条都没有。混成空列表的话，每次进这一页都会先闪一下
 * 「还没有收藏」，然后记录冒出来——用户会以为收藏丢了。
 *
 * 文件和文件夹在同一个列表里（顺序就是收藏时间倒序），但 [fileEntries] 只挑出
 * 文件：点其中一条要能接着播下一条，而「下一首是文件夹」这件事没有意义。
 * 索引也必须是**文件序列里**的下标，用混排列表的下标去播会跳着播。
 */
data class FavoritesUiState(val favorites: List<Favorite>? = null) {

    val loading: Boolean get() = favorites == null

    val isEmpty: Boolean get() = favorites?.isEmpty() == true

    val count: Int get() = favorites?.size ?: 0

    /** 已满。界面据此不让用户再收藏（但**不**隐藏收藏按钮——藏起来像是坏了）。 */
    val full: Boolean get() = count >= FavoriteRules.MAX_FAVORITES

    /** 可播放的文件序列。 */
    val fileEntries: List<MediaEntry>
        get() = favorites.orEmpty().filterIsInstance<Favorite.File>().map { it.item.toEntry() }
}

/**
 * 收藏页的 ViewModel。
 *
 * 只有读和删两件事：收藏动作发生在别的页面（媒体库 / 最近播放的多选条、
 * 浏览页顶栏的星标），这一页是「结果清单」。所以这里没有 `add`——
 * 让用户在收藏页里再选一遍文件等于让他去媒体库里找，那正是收藏要省掉的事。
 */
class FavoritesViewModel(private val store: FavoriteStore) : ViewModel() {

    /**
     * `WhileSubscribed` + 5 秒超时：退出收藏页再回来不用重新读一遍 DataStore，
     * 但真的离开之后订阅会被取消，不会为了一个看不见的页面常驻观察者。
     */
    val uiState: StateFlow<FavoritesUiState> = store.favorites
        .map(::FavoritesUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = FavoritesUiState(),
        )

    private val _message = MutableStateFlow<UiMessage?>(null)

    /** 一次性提示。界面用 [UiMessage.nonce] 当 key 显示，不需要「已消费」回调。 */
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val messageNonce = AtomicLong()

    /**
     * 取消收藏一条。
     *
     * 这一页的删除**没有撤销**：列表行上就一个星标按钮，点错了再点一次就回来了
     * （而且收藏的内容本身没有丢——文件还在媒体库里）。播放列表/最近播放给撤销，
     * 是因为那里删掉的是记录本身。
     */
    fun remove(key: String) {
        viewModelScope.launch {
            store.remove(key)
            publish(favoriteRemovedText(1))
        }
    }

    /** 一次取消收藏多条（长按多选）。 */
    fun removeAll(keys: Collection<String>) {
        if (keys.isEmpty()) return
        viewModelScope.launch {
            publish(favoriteRemovedText(store.removeAll(keys)))
        }
    }

    private fun publish(text: MspText) {
        _message.value = UiMessage(text = text, nonce = messageNonce.incrementAndGet())
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
