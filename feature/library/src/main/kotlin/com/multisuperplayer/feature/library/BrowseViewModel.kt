package com.multisuperplayer.feature.library

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.browser.BrowserContent
import com.multisuperplayer.core.data.browser.BrowserRepository
import com.multisuperplayer.core.data.browser.BrowserRoot
import com.multisuperplayer.core.data.browser.BrowserSort
import com.multisuperplayer.core.data.browser.BrowserTrail
import com.multisuperplayer.core.data.browser.StorageAccess
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.model.BrowserEntry
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.Playlist
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 「浏览」这一页的界面状态。
 *
 * 一个页面两个形态（**不是两个页面**）：
 *
 * - [trail] 为 null：**来源清单**。列出「内部存储 / 存储卡」和已授权的 SAF 目录。
 * - [trail] 非 null：**目录内容**。逐级进入，面包屑就是 [trail] 自己。
 *
 * 合成一个状态、而不是做成两个导航目的地，是因为「返回」必须是**逐级**的：
 * 在 `Download/电影/` 里按返回要回到 `Download`，而不是直接跳回来源清单。
 * 若把目录内容做成独立路由，返回键就属于导航图，会一路弹回来源清单，
 * 用户在深层目录里想退一层就只能点面包屑。
 *
 * 于是导航栈由 ViewModel 持有（而不是 `remember`）：旋转屏幕、切到别的应用
 * 再回来，位置都不该丢——那正是 ViewModel 活得比组合函数久的意义。
 */
data class BrowseUiState(
    /** 来源清单；null 表示还没读到（和「一个来源都没有」区分开）。 */
    val roots: List<BrowserRoot>? = null,
    /** 当前浏览位置；null 表示停在来源清单。 */
    val trail: BrowserTrail? = null,
    val content: BrowserContent = BrowserContent.Idle,
    val sort: BrowserSort = BrowserSort.NAME_ASC,
    val showHidden: Boolean = false,
) {
    val loading: Boolean get() = roots == null

    /** 正在浏览某个目录（而不是停在来源清单）。 */
    val browsing: Boolean get() = trail != null

    /** 还能往上一层。false 时「上一层」的动作等于退出浏览，见 [BrowseViewModel.goBack]。 */
    val canGoUp: Boolean get() = trail?.canGoUp == true
}

/**
 * 导航相关的三个输入。
 *
 * 单独包一层是为了能用一次 `combine` 把「当前位置 / 排序 / 隐藏开关」搬成单个对象，
 * 而不是让 `uiState` 去啃五元组（Kotlin 没有五元组，硬写的后果是每个位置都靠
 * `map[key]` 取，类型全丢）。
 */
private data class BrowseNav(
    val trail: BrowserTrail?,
    val sort: BrowserSort,
    val showHidden: Boolean,
)

/**
 * 浏览页的 ViewModel。
 *
 * ## 它只是「状态」，不是「数据源」
 *
 * 排序、是否显示隐藏项、当前在哪个目录，全部由这里持有并作为参数传给
 * `BrowserRepository`。仓库本身是无状态的（见它的 KDoc）：同一个目录在两种排序下
 * 是两次独立的查询，仓库不该替调用方记住「上一次用的是哪种」。
 *
 * ## 为什么有个手动的「重读来源」
 *
 * 「所有文件访问」是个**系统设置项**，不是一个运行时权限：用户在系统设置页里打开它
 * 再回到应用，进程一直活着，我们**收不到任何回调**。于是「内部存储」那一行会保持
 * 灰色的旧状态，看起来像点了没反应。
 *
 * 所以 [reloadSources] 用一个自增计数当触发器，由界面在 `ON_RESUME` 时调一次；
 * 它会把 `roots()` 整条链重新订阅一遍（`StateFlow` 不发射相同的值，
 * 而订阅重放会真的再问一次系统）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModel(
    private val library: MediaLibraryRepository,
    private val browser: BrowserRepository,
    private val storage: StorageAccess,
    playlistStore: PlaylistStore,
) : ViewModel() {

    private val playlistEditing = PlaylistEditing(playlistStore)

    private val trail = MutableStateFlow<BrowserTrail?>(null)
    private val sort = MutableStateFlow(BrowserSort.NAME_ASC)
    private val showHidden = MutableStateFlow(false)

    /** 见类 KDoc：这不是数据，是「请重新问一次系统」的触发器。 */
    private val reloadToken = MutableStateFlow(0)

    private val sources: StateFlow<List<BrowserRoot>?> = reloadToken
        .flatMapLatest { browser.roots() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    private val nav: StateFlow<BrowseNav> = combine(trail, sort, showHidden, ::BrowseNav)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = BrowseNav(trail = null, sort = BrowserSort.NAME_ASC, showHidden = false),
        )

    private val content: Flow<BrowserContent> = nav.flatMapLatest { current ->
        val target = current.trail
        if (target == null) {
            // 停在来源清单：这里不是「空目录」，而是「还没有选目录」。
            // 混成 `Ready(emptyList())` 会让界面显示一个空目录的空状态文案。
            flowOf(BrowserContent.Idle)
        } else {
            browser.listing(target, current.sort, current.showHidden)
        }
    }

    val uiState: StateFlow<BrowseUiState> =
        combine(sources, nav, content) { roots, current, listing ->
            BrowseUiState(
                roots = roots,
                trail = current.trail,
                content = listing,
                sort = current.sort,
                showHidden = current.showHidden,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = BrowseUiState(),
        )

    /** 从来源清单里的一行开始浏览。 */
    fun enterSource(root: BrowserRoot) {
        trail.value = BrowserTrail.root(root)
    }

    /** 进入当前目录下的一个子目录（点列表里的目录行）。 */
    fun enterDirectory(entry: BrowserEntry) {
        // 判据放在这里而不是只靠界面：列表行与点击目标在滚动时可能错位，
        // 而「进了个文件」在界面上只表现为一列空白，很难回头找原因。
        if (!entry.isDirectory) return
        trail.value = trail.value?.enter(entry)
    }

    /**
     * 返回上一层；已经在来源那一层时回到来源清单。
     *
     * 两种情况合成一个动作，是因为**用户按的是同一个返回键**：
     * 在 `Download/电影/` 里按返回要回 `Download`，在 `Download` 里按返回
     * 要回来源清单。若在这里就退出浏览、把「上一层」交给系统导航，
     * 用户在深层目录里想退一层就只能点面包屑（而且系统返回会直接退出应用）。
     */
    fun goBack() {
        val current = trail.value ?: return
        trail.value = if (current.canGoUp) current.up() else null
    }

    /** 无条件回到来源清单（面包屑最左端那个按钮）。 */
    fun leaveSource() {
        trail.value = null
    }

    /** 跳到面包屑的第 [index] 段。 */
    fun openCrumb(index: Int) {
        trail.value = trail.value?.jumpTo(index)
    }

    fun selectSort(value: BrowserSort) {
        sort.value = value
    }

    fun setShowHidden(value: Boolean) {
        showHidden.value = value
    }

    /** 用户在系统设置页里改过「所有文件访问」之后回到应用时调（`ON_RESUME`）。 */
    fun reloadSources() {
        reloadToken.update { it + 1 }
    }

    fun addTree(uri: String) = library.addTree(uri)

    fun removeTree(uri: String) = library.removeTree(uri)

    /**
     * 「加入播放列表」要选的列表清单。
     *
     * 只在多选的操作条按下去的时候才会用到，所以照样是 `WhileSubscribed`：
     * 用户没进多选时不该常驻一个 DataStore 观察者。
     */
    val playlists: StateFlow<List<Playlist>> = playlistEditing.playlists.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList(),
    )

    private val _message = MutableStateFlow<UiMessage?>(null)

    /** 一次性提示（「已加入 N 项」/「新建失败」）。用 [UiMessage.nonce] 当 key 显示。 */
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val messageNonce = AtomicLong()

    /**
     * 把多选中的条目追加到某个已有播放列表。
     *
     * 这里传的是 [MediaEntry]（渲染时由 `BrowserEntry.toMediaEntry()` 得到）而不是
     * 浏览条目本身：写进列表的就是它们，而它们带着 `file:` 前缀的 id 与
     * [com.multisuperplayer.core.model.MediaSource.FILE_SYSTEM]——回放时靠这两个字段
     * 才能还原成「这是个文件系统里的文件」（见 `PlaylistItem.sourceOf`）。
     */
    fun addToPlaylist(playlistId: String, entries: List<MediaEntry>) {
        if (entries.isEmpty()) return
        viewModelScope.launch {
            val added = playlistEditing.addTo(playlistId, entries)
            publish(addedToPlaylistText(added, entries.size))
        }
    }

    /** 新建一个播放列表并把多选中的条目放进去。 */
    fun createPlaylistWith(name: String, entries: List<MediaEntry>) {
        viewModelScope.launch {
            val added = playlistEditing.createWith(name, entries)
            if (added == null) {
                publish(MspText.Res(R.string.msp_library_playlist_create_failed))
                return@launch
            }
            publish(addedToPlaylistText(added, entries.size))
        }
    }

    private fun publish(text: MspText) {
        _message.value = UiMessage(text = text, nonce = messageNonce.incrementAndGet())
    }

    /**
     * 「所有文件访问」的授权入口。
     *
     * 这个权限**没有**运行时对话框可弹，只能把用户送到系统设置页——所以它必须
     * 在界面上有一个看得见的入口，否则用户只会看到一行写着「未开启」的灰条目
     * 却不知道去哪里开。
     */
    fun allFilesAccessIntent(): Intent = storage.preferredSettingsIntent()

    private companion object {
        /**
         * 比导航切换的间隔长一点就够了：这只影响「离开页面后多久释放订阅」，
         * 太短会在频繁切页时反复重读 DataStore 和卷列表。
         */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
