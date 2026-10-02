package com.multisuperplayer.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.model.SafTreeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 一条已授权的目录。
 *
 * [accessible] 是**现在**还能不能打开它，来自
 * `MediaLibraryRepository.isTreeAccessible`。清单和系统实际持有的授权是两份数据，
 * 系统那份可以被单方面收回（用户在设置里撤销、SD 卡拔了、provider 卸载），
 * 所以展示清单时必须顺手把这个问出来——否则会出现一个「看起来还在授权着但扫描
 * 什么都扫不到」的目录，而用户完全不知道该做什么。
 */
data class BrowseTree(
    val info: SafTreeInfo,
    val accessible: Boolean,
)

/**
 * 浏览页的界面状态。[trees] 为 null 表示还没读到（和「一个目录都没授权」区分开）。
 */
data class BrowseUiState(
    val trees: List<BrowseTree>? = null,
) {
    val loading: Boolean get() = trees == null
}

/**
 * 拼出界面状态。
 *
 * [accessible] 做成入参（而不是直接在这里调仓库）是为了让这条规则能被单测覆盖：
 * 「授权还在不在」正是那种只能靠在真机上拔 SD 卡才能复现、于是永远没人测的分支。
 */
internal fun buildBrowseUiState(
    trees: List<SafTreeInfo>,
    accessible: (String) -> Boolean,
): BrowseUiState = BrowseUiState(
    trees = trees.map { info -> BrowseTree(info = info, accessible = accessible(info.uri)) },
)

/**
 * 浏览页的 ViewModel。
 *
 * 这一页**只**管 SAF 授权：系统媒体库（MediaStore）里的内容不需要任何操作就会
 * 出现在媒体库页，把它也列成一张需要「添加」的表，等于暗示用户得手动导入一遍。
 * 所以这里没有「系统媒体库」条目，只有一行说明文案。
 */
class BrowseViewModel(private val library: MediaLibraryRepository) : ViewModel() {

    val uiState: StateFlow<BrowseUiState> = library.safTrees
        .map { trees -> buildBrowseUiState(trees, library::isTreeAccessible) }
        // isTreeAccessible 是一次 ContentResolver 查询（Binder 调用），别放在主线程上。
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = BrowseUiState(),
        )

    fun addTree(uri: String) = library.addTree(uri)

    fun removeTree(uri: String) = library.removeTree(uri)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
