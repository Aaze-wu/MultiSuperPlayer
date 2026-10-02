package com.multisuperplayer.core.data.subtitle

/**
 * 能当外挂字幕挂上的后缀（小写，不带点）。
 *
 * 直接引用 [SubtitleFileNaming.discoverableExtensions]，不在这里另抄一份：
 * 自动查找和手动指定必须对「什么算字幕」有一模一样的答案，否则会出现
 * 「扫目录能扫到、自己在文件浏览器里挑却挑不着」这种没法解释的差别。
 */
val subtitleFileExtensions: Set<String> = SubtitleFileNaming.discoverableExtensions.toSet()

/**
 * 用户在文件浏览器里**亲手选中**的一个字幕文件，变成一条候选。
 *
 * ## 关联分为什么直接给满分
 *
 * [SubtitleSource.matchScore] 的含义是「这个名字和那条媒体像不像」，自动查找靠它
 * 从一堆同目录文件里挑。而这里的候选**不需要猜**：用户点中的就是它。所以直接给
 * [SubtitleFileNaming.SCORE_EXACT]——它排在候选列表第一位，而且按 [isAutoMatchable]
 * 的规则本来就会生效（用户指定了却不生效才是 bug）。
 *
 * 满分下 [SubtitleSource.matchesMedia] 恒为 true，界面因此不会把它归进「名字对不上，
 * 只能手动选」那一类——那句话在这里是错的：**就是他手动选的**。
 *
 * ## 为什么不经过 MediaStore
 *
 * 这条路存在的理由就是这个：`Download/` 根、内部存储根、`.nomedia` 目录里的文件在
 * 系统媒体库里**看不见**（也正是内置文件浏览器存在的全部意义）。文件名是现成的
 * （[fileName] 来自目录列表），后缀、语言、`forced`、双语标记全部从名字上解析，
 * 一个字节都不用读盘。
 *
 * @param path 文件系统绝对路径，也接受 `file://` / `content://` uri——这一层不关心
 *   来源，需要时会按 [readableUri] 补 scheme。
 * @param sizeBytes provider / 文件系统给不出大小时传 0；只用于显示。
 */
fun subtitleSourceOf(path: String, fileName: String, sizeBytes: Long = 0L): SubtitleSource {
    val info = SubtitleFileNaming.analyze(fileName)
    return SubtitleSource(
        uri = readableUri(path),
        fileName = fileName,
        format = SubtitleFileNaming.formatOf(fileName),
        languageTag = info.languageTag,
        isForced = info.isForced,
        isBilingual = info.isBilingual,
        sizeBytes = sizeBytes.coerceAtLeast(0L),
        matchScore = SubtitleFileNaming.SCORE_EXACT,
        trailingTagCount = info.trailingTagCount,
    )
}

/**
 * 补上 `file://`。
 *
 * 文件浏览器交给我们的 [path] 是**裸的绝对路径**（`/storage/emulated/0/Download/a.srt`）。
 * 播放那条路可以直接用它——Media3 把 `file://` 和裸路径都当本地文件
 * （`Util.isLocalFileUri` 明确接受 `scheme == null`）。但**读字幕这条路不行**：
 * `SubtitleRepository.load` 走的是 `contentResolver.openInputStream(uri)`，它只认
 * `file` / `content` 两种 scheme，无 scheme 的会被当成 content uri 去问
 * `ContentProvider`（authority 为 null），直接抛异常。
 *
 * 这不是「同一个 uri 两种写法」，而是两个消费者接受范围不同。转换放在这里，
 * 因为只有这个函数知道自己在为谁准备 uri。
 */
private fun readableUri(path: String): String =
    if (path.contains("://")) path else "file://$path"
