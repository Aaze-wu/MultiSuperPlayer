package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.data.library.SafScanRules
import com.multisuperplayer.core.model.BrowserEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 用 `java.io.File` 直接列目录的来源。
 *
 * 唯一的外部依赖是 [AllFilesAccess]（一个只有一个方法的接口，见 `StorageAccess.kt`）——
 * 这样它在 JVM 单测里能真跑（`java.io.File` 是纯 JDK），而不必碰 `Environment`。
 * 本模块唯一可测的目录来源就是它，所以它承载的转换（目录 vs 文件、绝对路径怎么拼、
 * 大小/时间取哪个值）都必须留在这里，不能挪进 IO 代码。
 *
 * ## 它和另外两个来源的本质差别
 *
 * 这是**唯一能看到「全部真相」**的来源：
 *
 * - 看得到 `.nomedia` 目录里的东西（媒体库因为 `.nomedia` 直接不收）；
 * - 看得到任意后缀的文件（字幕、`.cue`、冷门容器）；
 * - 看得到 `Android/data` 下的内容（分区存储里连文件管理器通常都进不去）。
 *
 * 代价是它需要 `MANAGE_EXTERNAL_STORAGE`——而这是**唯一**能换到这三点的方式。
 * 系统选择器（`ACTION_OPEN_DOCUMENT_TREE`）在**系统层**就拒绝授予
 * `Download` 根、内部存储根、`Android/data`，换 UI / 自己画选择器都不会有差别。
 */
internal class FileSystemSource(private val access: AllFilesAccess) : DirectorySource {

    override val kind: BrowserSourceKind = BrowserSourceKind.FILE_SYSTEM

    override suspend fun list(ref: String): BrowserListing = withContext(Dispatchers.IO) {
        // 权限是**每次列目录都重新问**的：用户可以在「所有文件访问」设置页里
        // 随时关掉它，而我们这边不会收到任何回调。缓存这个布尔值会让界面
        // 在权限被关掉之后继续显示一堆进不去的目录。
        if (!access.granted()) return@withContext BrowserListing.Unreadable

        val dir = File(ref)
        // 「不存在」和「不是目录」合并成本分支：两者的界面动作是同一个——退回上一层。
        // 分开表达只会让调用方多写一个分支，然后两边做一样的事。
        if (!dir.isDirectory) return@withContext BrowserListing.NotADirectory

        // `listFiles()` 返回 `null` 有且只有两种原因：不是目录（上面挡掉了）、
        // 或者 IO 出错 / 没权限。后者正是要报成 Unreadable 的那种——
        // 把它当成空列表，用户会以为目录里的东西没了。
        val children = dir.listFiles() ?: return@withContext BrowserListing.Unreadable

        BrowserListing.Ready(children.map(::toEntry))
    }

    private fun toEntry(file: File): BrowserEntry {
        val directory = file.isDirectory
        val name = file.name
        return BrowserEntry(
            ref = file.absolutePath,
            name = name,
            isDirectory = directory,
            // 目录的 `length()` 在 Linux 上返回的是目录项占用的块数（4096 之类），
            // 对用户毫无意义，直接归零，免得界面上出现「文件夹 4 KB」。
            sizeBytes = if (directory) 0L else file.length().coerceAtLeast(0L),
            // 某些文件系统（FAT 的 1970 年时间戳、损坏的 inode）会给出 0 或负数，
            // 统一归零让 BrowserRules 的「时间未知」判定能生效。
            lastModifiedMs = file.lastModified().coerceAtLeast(0L),
            // 复用 SAF 那一套后缀判定，两种来源对「什么算媒体」的答案因此完全一致。
            // 自己再写一份清单，迟早会出现「同一个 .mka 在 SAF 里看得见、
            // 在文件系统里看不见」这种没法解释的差别。
            //
            // MIME 传 null：`MimeTypeMap` 只认 Android 内置的那张表，比
            // `SafScanRules.kindOf` 自己的后缀表还窄，传进去反而会把结论变差。
            kind = if (directory) null else SafScanRules.kindOf(name, mimeType = null),
            mimeType = null,
        )
    }
}
