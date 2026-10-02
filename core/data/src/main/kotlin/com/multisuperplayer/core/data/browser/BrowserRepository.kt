package com.multisuperplayer.core.data.browser

import android.content.Context
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.data.library.SafTreeScanner
import com.multisuperplayer.core.data.library.SafTreeStore
import com.multisuperplayer.core.model.SafTreeInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * 内置文件浏览器的数据入口。
 *
 * ## 它是**无状态**的（重要）
 *
 * 「现在在哪一层目录」「用哪种排序」「显不显示隐藏文件」这些**不在这里**，
 * 由调用方（ViewModel）持有并作为参数传进来。原因是这三样全部是纯粹的界面状态：
 *
 * - 它们不需要持久化（重启应用回到来源列表是对的）；
 * - 它们不需要跨 ViewModel 共享；
 * - 最要紧的是，放在这里就得配一套 `setX()` + `StateFlow` + 一个长活 `CoroutineScope`，
 *   而这个 scope 的生命周期谁都说不清（`MediaLibraryRepository` 为此专门有 `release()`）。
 *
 * 无状态之后，[listing] 就是一个「输入位置 + 显示选项，输出一个可以取消的 flow」的
 * 纯查询：切目录时旧的 flow 被取消，不会有一次半路的结果盖在新目录上。
 *
 * ## 只有一个来源是可测的
 *
 * [SafDocumentSource] 要 `Context` + `DocumentsContract`，在 JVM 单测里连构造都做不到。
 * 所以这个类的定位就只是「把两个 `DirectorySource` 接起来」，**不含任何判断**：
 * 所有会被用户看见的规则都在 `BrowserRules` / `BrowserRoots` / `BrowserTrail` /
 * `FileSystemSource` 里，那些都有真单测。
 */
class BrowserRepository(
    context: Context,
    private val safTreeStore: SafTreeStore,
    private val safScanner: SafTreeScanner,
    private val storage: StorageAccess,
    private val dispatchers: DispatcherProvider,
) {

    private val fileSystem: DirectorySource = FileSystemSource(storage)

    private val safDocuments: DirectorySource = SafDocumentSource(context.applicationContext)

    /**
     * 可以开始浏览的位置清单。
     *
     * 它同时依赖两组输入，而且**两组都会变**：
     *
     * - SAF 授权清单（`safTreeStore.trees`）——用户随时可能加一个目录；
     * - 「所有文件访问」的开关状态——用户会跳出去的系统设置页里改，回来时
     *   我们的进程还活着，没有任何回调会通知我们。
     *
     * 后者不是 flow，所以这里只能「每次被订阅时重新读一次」。调用方要在
     * `ON_RESUME` 时重建订阅（`flatMapLatest` 一个自增计数即可），
     * 否则用户开完权限回来会发现条目还是灰的——一个点了没反应、怎么看都对的状态。
     */
    fun roots(): Flow<List<BrowserRoot>> = safTreeStore.trees
        .map { trees -> buildRoots(trees) }
        .flowOn(dispatchers.io)

    /**
     * 列出 [trail] 当前所在目录。
     *
     * 先发 [BrowserContent.Loading]，所以界面在慢目录（云盘 provider）上不会
     * 一边显示上一个目录的内容一边等——那会让人以为「点了没反应」。
     */
    fun listing(
        trail: BrowserTrail,
        sort: BrowserSort,
        showHidden: Boolean,
    ): Flow<BrowserContent> = flow {
        emit(BrowserContent.Loading)
        val listing = sourceOf(trail.kind).list(trail.current.ref)
        emit(BrowserContent.of(listing, sort, showHidden))
    }.flowOn(dispatchers.io)

    private fun buildRoots(trees: List<SafTreeInfo>): List<BrowserRoot> = BrowserRoots.build(
        volumes = storage.volumes(),
        fileSystemSupported = storage.supported(),
        fileSystemGranted = storage.hasAllFilesAccess(),
        safTrees = trees,
        safAccessible = safScanner::hasReadPermission,
    )

    private fun sourceOf(kind: BrowserSourceKind): DirectorySource = when (kind) {
        BrowserSourceKind.FILE_SYSTEM -> fileSystem
        BrowserSourceKind.SAF -> safDocuments
    }
}
