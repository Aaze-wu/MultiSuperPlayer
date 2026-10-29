package com.multisuperplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.library.FolderNode
import com.multisuperplayer.core.data.library.LibraryFolderRules
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaLibraryState
import com.multisuperplayer.core.data.settings.LibraryFolderSettings
import com.multisuperplayer.core.data.settings.LibraryFolderSettingsRepository
import com.multisuperplayer.core.model.text.MspText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「媒体库文件夹」页里的一行。
 *
 * [checked] 与 [FolderNode] 分开：节点是「有哪些文件夹」（扫描结果），勾是
 * 「现在算不算数」（偏好）。两者来源不同、更新时机也不同（改勾**不该**重扫），
 * 合进一个 data class 只会让「谁改了什么」变得说不清。
 */
data class FolderRow(
    val path: String,
    val name: String,
    val depth: Int,
    val totalCount: Int,
    val checked: Boolean,
)

/**
 * 「媒体库文件夹」这一页的状态。
 *
 * 三个「列表不能当空看」的情形各有独立字段，而不是挤进一个 `isEmpty`：
 * 扫描没完成、没授权、偏好读不出来，用户要做的事完全不同（等一下 / 去授权 /
 * 重装或清数据），界面给同一句话等于什么都没说。
 */
data class LibraryFolderSettingsUiState(
    /** 第一次扫描还没结束。 */
    val loading: Boolean = true,
    /** 连 MediaStore 权限和 SAF 授权都没有，扫不出任何东西。 */
    val needsPermission: Boolean = false,
    /** 扫描报错。 */
    val failed: Boolean = false,
    /** 偏好读不出来（DataStore 出错）⇒ 勾选状态不可信。 */
    val unknown: Boolean = false,
    /**
     * 这台设备上文件夹筛选**没法生效**（Android 9 及以下没有 `RELATIVE_PATH`）。
     *
     * 树照旧画出来（用户能看见库里有哪些文件夹），但勾不给点、也不落盘——
     * 能点却不生效比直接置灰更难理解。
     */
    val unsupported: Boolean = false,
    val rows: List<FolderRow> = emptyList(),
    /** 用户真正选中的文件夹个数（最小形式，不是「勾了几行」）。 */
    val selectedCount: Int = 0,
) {

    /**
     * 「扫完了，但一个文件夹都没有」。
     *
     * 与 [LibraryFolderSettingsUiState] 的上三种情形互斥——它们都说得出原因，
     * 这一种才是真的空库。
     */
    val nothingScanned: Boolean
        get() = !loading && !needsPermission && !failed && rows.isEmpty()
}

/**
 * 媒体库 › 只看哪些文件夹。
 *
 * 这一页**改的是过滤条件，不是文件**：没被选中的文件夹照样能在「浏览」页翻到、
 * 照样能播，只是不进媒体库列表。所以这里既没有删除也没有移动，只有勾。
 */
class LibraryFolderSettingsViewModel(
    private val library: MediaLibraryRepository,
    private val folderSettings: LibraryFolderSettingsRepository,
) : ViewModel() {

    val state: StateFlow<LibraryFolderSettingsUiState> = combine(
        // 建树用**未过滤**的 scannedPaths：用过滤后的 state 建树的话，勾掉一个文件夹
        // 它就从树上消失，再也勾不回来。
        library.scannedPaths,
        folderSettings.settings,
        library.state,
    ) { paths, prefs, libraryState ->
        val rows = buildFolderRows(LibraryFolderRules.folderNodes(paths), prefs)
        LibraryFolderSettingsUiState(
            loading = libraryState is MediaLibraryState.Loading,
            needsPermission = libraryState is MediaLibraryState.NeedsPermission,
            failed = libraryState is MediaLibraryState.Error,
            unknown = prefs.unknown,
            unsupported = !prefs.supported,
            rows = rows,
            selectedCount = prefs.selected.size,
        )
    }.stateIn(
        scope = viewModelScope,
        // 转屏、进出设置页时那一两秒不算「没人看」：重扫一次要几十毫秒到几秒，
        // 停订立刻重订会让树闪一下才回来。
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryFolderSettingsUiState(),
    )

    init {
        // 从设置页直接进这一页时，媒体库可能一次都没扫过（用户没打开过媒体库页），
        // 那时 scannedPaths 是空的 ⇒ 树上什么都没有。这里主动扫一次。
        // startObserving 是幂等的，refresh 不强制重扫 SAF（见仓库注释）。
        library.startObserving()
        library.refresh()
    }

    /**
     * 勾选/取消一个文件夹。
     *
     * 传的是**整棵树**的路径而不只是可见行：`LibraryFolderRules.toggle` 需要知道
     * 待取消的那个文件夹底下有哪些兄弟，才能在「取消一个被祖先覆盖的子文件夹」时
     * 把祖先展开成具体的兄弟集合。
     */
    fun toggle(path: String) {
        val allFolders = state.value.rows.map { it.path }
        viewModelScope.launch { folderSettings.toggleFolder(path, allFolders) }
    }

    /** 清空全部勾选（=媒体库什么都不显示，但文件都还在）。 */
    fun clearAll() {
        viewModelScope.launch { folderSettings.setFolders(emptySet()) }
    }
}

/**
 * 把「文件夹树 + 偏好」拼成可渲染的行。
 *
 * 勾的判定交给 [LibraryFolderRules.isCovered]：选中父文件夹时，页面上每一个
 * 子孙行都显示成勾上的——**界面上的勾必须和过滤结果一致**，否则用户看到
 * `Music` 勾着、`Music/Live` 空着，而它里面的歌其实进了库。
 */
internal fun buildFolderRows(
    nodes: List<FolderNode>,
    settings: LibraryFolderSettings,
): List<FolderRow> = nodes.map { node ->
    FolderRow(
        path = node.path,
        name = node.name,
        depth = node.depth,
        totalCount = node.totalCount,
        checked = LibraryFolderRules.isCovered(settings.selected, node.path),
    )
}

/**
 * 「媒体库文件夹」的摘要文案。
 *
 * 各种状态说不同的话——尤其是「一个都没选」：那是**有意**不看媒体库，
 * 而不是还没设置过。入口页那一行如果只写「0 个文件夹」，用户会以为哪里坏了。
 */
internal object LibraryFolderSummaries {

    /** 设置首页那一行的副标题。 */
    fun entry(settings: LibraryFolderSettings): MspText = when {
        // 系统不支持时先说这个：它是平台硬事实，既不是用户能改的，也不是「没选」，
        // 写成「一个都没勾」会让人去勾一遍，而勾了也不会生效。
        !settings.supported -> MspText.Res(R.string.msp_settings_library_folders_unsupported)
        // 读不出来也不能写成「未选择」：那句话会让用户去重新勾一遍，而真正的问题
        // 是 DataStore 读失败（媒体库此时**没有**被过滤，一条都没少）。
        settings.unknown -> MspText.Res(R.string.msp_settings_library_folders_unknown)
        settings.selected.isEmpty() -> MspText.Res(R.string.msp_settings_library_folders_none)
        else -> MspText.Res(
            R.string.msp_settings_library_folders_count,
            settings.selected.size,
        )
    }
}
