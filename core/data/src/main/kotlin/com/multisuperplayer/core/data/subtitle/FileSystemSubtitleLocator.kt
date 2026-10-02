package com.multisuperplayer.core.data.subtitle

import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.data.library.SafScanRules
import java.io.File

private const val TAG = "SubtitleLocatorFile"

/**
 * 在**文件系统**的目录里找外挂字幕。
 *
 * ## 为什么需要第三条来源
 *
 * 另外两条都建立在「有索引 / 有授权」之上，而内置文件浏览器打开的条目两样都没有
 * （这正是它存在的理由）：
 *
 * - [SubtitleFileLocator] 查的是 `MediaStore.Files.RELATIVE_PATH`——`Download/` 根、
 *   内部存储根、带 `.nomedia` 的目录在媒体库里**一条记录都没有**；
 * - [SafSubtitleLocator] 问的是 SAF provider——文件浏览器打开的条目没有任何 tree 授权，
 *   拼不出 children uri。
 *
 * 所以这一条直接 `File.listFiles()`：用户的视频旁边躺着什么，就看见什么。
 *
 * ## 它和另外两条在「列不出来」这件事上的口径不同（刻意）
 *
 * 另外两条要先用一次查询确认目录**可见**（查回 0 行既可能是「没有字幕」，也可能是
 * 「这次查询根本没通」），所以它们还要再数一次目录里的文件数来区分。
 *
 * 这里不需要：`listFiles()` 返回非 `null` 数组**本身就是「这个目录读到了」的证据**
 * （读不到只会返回 `null`）。于是判据只剩一条：
 *
 * - `null` → [DirectoryScan.Invisible]（权限被关掉、目录已不存在）；
 * - 非 `null` 且没有字幕 → [DirectoryScan.NoSubtitles]，**哪怕它是个空目录**。
 *
 * 把空目录说成 [DirectoryScan.Invisible] 会把用户送去检查权限，而那里什么毛病都没有。
 *
 * ## 依赖为零，所以它是这一层里唯一能真单测的
 *
 * `java.io.File` 是纯 JDK：没有 `Context`、没有 `ContentResolver`。
 * 于是「列不出来怎么办、目录算不算文件、uri 怎么拼」这些判断用**真的临时目录树**
 * 验证（见 `FileSystemSubtitleLocatorTest`），而不是造一个假的 `DirectoryScan`。
 */
class FileSystemSubtitleLocator {

    /**
     * 扫 [directoryPath] 目录里的字幕候选。
     *
     * 不抛异常：目录在播放过程中被删掉、SD 卡被拔掉、权限被关掉都是**正常状态**，
     * 一律落成 [DirectoryScan.Invisible]，由界面说「读不到这个文件夹」。
     *
     * @param directoryPath 绝对路径（不是 uri）。调用方用 [localDirectoryOf] 从媒体
     *   自己的路径上取上一级，见 [subtitleLookupOf]。
     */
    fun scanDirectory(directoryPath: String): DirectoryScan {
        // 「不是目录」和「不存在」在这里合并成同一个结果：`listFiles()` 对两者都返回
        // `null`，而且界面动作也一样（退回上一层 / 检查权限），分开表达只会让调用方
        // 多写一个两边做同样事情的 `when` 分支。
        val children = File(directoryPath).listFiles()
            ?: run {
                MspLog.d(TAG) {
                    "目录「$directoryPath」列不出来：可能已不存在，也可能没有读取权限"
                }
                return DirectoryScan.Invisible
            }

        val extensions = SubtitleFileNaming.discoverableExtensions
        val subtitles = children
            .filter { it.isFile && SafScanRules.extensionOf(it.name) in extensions }
            // 排序只为了让日志和测试可复现：最终顺序由 `sortedForSelection` 决定，
            // 而 `listFiles()` 给的是文件系统顺序（换台机器就可能变）。
            .sortedBy { it.name }

        if (subtitles.isNotEmpty()) {
            MspLog.d(TAG) { "目录「$directoryPath」发现 ${subtitles.size} 个字幕文件" }
            return DirectoryScan.Found(subtitles.map(::row))
        }

        MspLog.d(TAG) { "目录「$directoryPath」读到了，但没有字幕文件（共 ${children.size} 项）" }
        return DirectoryScan.NoSubtitles(children.size)
    }

    private fun row(file: File) = SubtitleFileRow(
        // 读字幕走 `ContentResolver.openInputStream`，它只认 `file:` / `content:`
        // 两种 scheme——补 scheme 的规则和「用户手选的那个文件」共用一处，
        // 见 `SubtitleFiles.readableUri`。两边各写一份的话，同一个文件会得到两个
        // 不同的 uri，而 uri 正是解析缓存的键。
        uri = readableUri(file.absolutePath),
        fileName = file.name,
        // 目录已在上面被滤掉，所以 `length()` 拿到的是真实文件大小。
        sizeBytes = file.length().coerceAtLeast(0L),
    )
}
