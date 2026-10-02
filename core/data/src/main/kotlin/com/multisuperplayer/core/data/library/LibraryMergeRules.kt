package com.multisuperplayer.core.data.library

import com.multisuperplayer.core.model.MediaEntry

/**
 * 把多来源的条目合并成一个媒体库，并去掉「其实是同一个文件」的重复。
 *
 * ## 为什么需要去重（而不是「反正多显示一条也没事」）
 *
 * 用户授权 SAF 目录的动机几乎总是「MediaStore 没扫到我的文件」。但系统选择器
 * 允许他授权 `primary:`（整块内置存储），而那种情况下 SAF 会扫出**和 MediaStore
 * 完全一样的一堆文件**。不去重的话，媒体库立刻从 500 条变成 1000 条，
 * 每首歌、每个视频都出现两次——而且是那种「看起来不像 bug、像你手机里真有两份」
 * 的现象，用户很难判断该删哪一个。
 *
 * ## 去重的判据只能是「文件身份」，不能是 id
 *
 * 同一条 [`MediaEntry`] 的两个可能 id：
 * - MediaStore：`audio:123`（数据库行号）
 * - SAF：`saf:primary:Music/a.mp3`（volum 内路径）
 *
 * 它们之间没有任何可比之处，所以只能回到「文件系统的身份」上比：
 * **同一个相对路径 + 同一个文件名 + 同样的大小**。
 * 见 [SafScanRules.identityKey] 里对「为什么带 size」和「跨卷误判概率」的说明。
 *
 * ## 冲突时保留谁
 *
 * **永远保留 MediaStore 的那条**，理由不是「谁先来」而是字段质量：
 * MediaStore 有条目级别的 `artist`/`album`/`duration`/`trackNumber`（专辑封面也能
 * 通过 `ContentResolver.loadThumbnail` 拿到），而 SAF 那条只有路径和大小。
 * 丢掉 MediaStore 那条，用户会看到「艺术家全变成未知」。
 */
internal object LibraryMergeRules {

    fun merge(
        mediaStore: List<MediaEntry>,
        saf: List<MediaEntry>,
    ): List<MediaEntry> {
        if (saf.isEmpty()) return mediaStore
        if (mediaStore.isEmpty()) return saf

        val taken = HashSet<String>(mediaStore.size)
        for (entry in mediaStore) {
            identityKeyOf(entry)?.let(taken::add)
        }

        val result = ArrayList<MediaEntry>(mediaStore.size + saf.size)
        result += mediaStore
        for (entry in saf) {
            val key = identityKeyOf(entry)
            // 算不出身份的条目一律保留：宁可多显示一条，也不要因为「大小读成 0」
            // 这种临时状态把一个真实存在的文件判成重复而删掉。
            if (key != null && !taken.add(key)) continue
            result += entry
        }
        return result
    }

    /** 见 [SafScanRules.identityKey]。 */
    fun identityKeyOf(entry: MediaEntry): String? = SafScanRules.identityKey(
        relativePath = entry.relativePath,
        displayName = entry.displayName,
        sizeBytes = entry.sizeBytes,
    )
}
