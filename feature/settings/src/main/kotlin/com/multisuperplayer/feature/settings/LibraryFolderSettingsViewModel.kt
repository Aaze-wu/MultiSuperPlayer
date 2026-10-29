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
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** 底下还有子文件夹 ⇒ 这一行要带展开/收起按钮；叶子行不带（按了也没有反应）。 */
    val hasChildren: Boolean = false,
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
    /** 收起来了的文件夹路径。只影响这一页画几行，**不影响**媒体库过滤。 */
    val collapsed: Set<String> = emptySet(),
    /** 用户真正选中的文件夹个数（最小形式，不是「勾了几行」）。 */
    val selectedCount: Int = 0,
) {

    /**
     * 当前真正要画出来的行（把收起来的子树的子孙去掉）。
     *
     * [rows] 本身保持整棵树：勾选要用到**所有**路径（`LibraryFolderRules.toggle`
     * 得知道被取消的那个文件夹底下有哪些兄弟），一旦按可见行去算，收起状态下取消
     * 一个父目录就会少展开一批兄弟。
     */
    val visibleRows: List<FolderRow>
        get() = visibleFolderRows(rows, collapsed)

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

    /**
     * 收起来了的文件夹。`null` = 还没定过（第一次拿到树时按 [defaultCollapsedPaths] 算一份）。
     *
     * 只活在这个 ViewModel 里、**不落盘**：它是「这次怎么看」，不是偏好。离开这一页
     * 再进来回到「只展开根」比记住上次收了哪几个更可预期；何况 DataStore 多一个键就
     * 多一份可能读坏、需要迁移的东西，而这一页是极低频入口。
     */
    private val collapsed = MutableStateFlow<Set<String>?>(null)

    val state: StateFlow<LibraryFolderSettingsUiState> = combine(
        // 建树用**未过滤**的 scannedPaths：用过滤后的 state 建树的话，勾掉一个文件夹
        // 它就从树上消失，再也勾不回来。
        library.scannedPaths,
        folderSettings.settings,
        library.state,
        collapsed,
    ) { paths, prefs, libraryState, collapsedPaths ->
        val nodes = LibraryFolderRules.folderNodes(paths)
        LibraryFolderSettingsUiState(
            loading = libraryState is MediaLibraryState.Loading,
            needsPermission = libraryState is MediaLibraryState.NeedsPermission,
            failed = libraryState is MediaLibraryState.Error,
            unknown = prefs.unknown,
            unsupported = !prefs.supported,
            rows = buildFolderRows(nodes, prefs),
            // 还没定过就现算一份默认值；算出来就**不写回** [collapsed]，
            // 这样用户还没动过手时，勾选变化仍然会重新算「该展开哪几条链」。
            collapsed = collapsedPaths ?: defaultCollapsedPaths(nodes, prefs.selected),
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
     * 展开/收起一个文件夹的下一层。
     *
     * 它只改**这一页画几行**：不碰勾、不碰过滤、不重扫，收起来的子树里的文件该进库
     * 还是进库（与「勾选」是两件事，别让用户以为收起来等于不选）。
     */
    fun toggleExpanded(path: String) {
        // 树没到（或这一行已经不在了）时别把默认值当成用户的选择记下来。
        if (state.value.rows.none { it.path == path }) return
        val current = state.value.collapsed
        collapsed.value = if (path in current) current - path else current + path
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
): List<FolderRow> {
    val allPaths = nodes.map { it.path }
    return nodes.map { node ->
        FolderRow(
            path = node.path,
            name = node.name,
            depth = node.depth,
            totalCount = node.totalCount,
            checked = LibraryFolderRules.isCovered(settings.selected, node.path),
            hasChildren = hasChildFolder(node.path, allPaths),
        )
    }
}

/**
 * [path] 底下还有没有子文件夹（[allPaths] 是整棵树的路径）。
 *
 * 不直接用 `LibraryFolderRules.covers`：它把根（`""`）定义成「只覆盖自己」
 * （这样才能勾「整个存储」而不被当成覆盖所有子目录），于是「根有没有子目录」
 * 永远算出 false——而根恰恰是最常被展开的那一行。
 */
internal fun hasChildFolder(path: String, allPaths: Collection<String>): Boolean {
    val prefix = if (path.isEmpty()) "" else "$path/"
    return allPaths.any { other -> other != path && other.startsWith(prefix) }
}

/**
 * 当前真正该画出来的行：被收起来的文件夹**自己不消失**，只遮住它的子孙。
 *
 * 判据用 [LibraryFolderRules.ancestorsOf]（`A/B/C` 的祖先是 `""`/`A`/`A/B`），
 * 所以根行（祖先列表为空）永远可见——根被藏起来的话整页就只剩一行空白，
 * 这个功能会变成「把清单藏起来」。
 *
 * [collapsed] 为空时原样返回（最常见的情形走不到 filter）。
 */
internal fun visibleFolderRows(
    rows: List<FolderRow>,
    collapsed: Set<String>,
): List<FolderRow> {
    if (collapsed.isEmpty()) return rows
    return rows.filter { row ->
        LibraryFolderRules.ancestorsOf(row.path).none { it in collapsed }
    }
}

/**
 * 第一次拿到树时的折叠集合（=默认只看顶层）。
 *
 * 之前这一页是**全展开**的：`Music/<歌手>/<专辑>` 这种目录一多，首屏就是一屏
 * 名字，想勾的东西要滚很久才找得到——这正是要修的那个「不方便」。默认只展开根，
 * 首屏就是那几个顶层目录，点一下再往里走。
 *
 * 但**已勾选的文件夹那条链要跟着展开**：上次勾的是 `Music/Live`，这次进来要是
 * 一棵收起来的树、看不见任何勾，用户只会以为自己白勾了。根不在集合里，永远展开。
 */
internal fun defaultCollapsedPaths(
    nodes: List<FolderNode>,
    selected: Set<String>,
): Set<String> {
    val allPaths = nodes.map { it.path }
    val reveal = selected.flatMapTo(mutableSetOf()) { LibraryFolderRules.ancestorsOf(it) }
    return allPaths
        .filter { path ->
            path.isNotEmpty() && path !in reveal && hasChildFolder(path, allPaths)
        }
        .toSet()
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
