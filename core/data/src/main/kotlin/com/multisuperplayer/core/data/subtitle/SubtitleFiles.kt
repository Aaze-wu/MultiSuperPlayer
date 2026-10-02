package com.multisuperplayer.core.data.subtitle

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.multisuperplayer.core.common.log.MspLog

private const val TAG = "SubtitleFiles"

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
 * 系统文件选择器（`ActivityResultContracts.OpenDocument`）交回来的那个 uri → 一条候选。
 *
 * ## 和 [subtitleSourceOf] 的关系
 *
 * 只多做一件事：**问出这个文件叫什么**。选择器只回一个 uri，而文件名是这条候选
 * 的全部信息来源——界面上的标题、后缀推出来的格式、语言标记、`forced`、双语标记
 * 都挂在它身上。拿不到名字，这条候选就没有意义（不能拿 uri 当标题：用户会看到
 * 一长串 `content://com.android.externalstorage.documents/…` 而认不出自己选了哪个）。
 *
 * ## 为什么 MIME 过滤救不了这个问题
 *
 * 打开选择器时只能传通配 MIME：字幕文件的 MIME 是供应商随手给的
 * （`text/plain` / `application/octet-stream` / 甚至 `video/mp2t` 因为 `.ts`），
 * 按 MIME 白名单过滤的后果是「我明明有这个文件，选择器里灰的/根本看不见」。
 * 所以多选进来的杂文件**不在这里拦**，交给解析器报「无法解析这个字幕文件」——
 * 那是一句说得清的话，而「选择器里找不到文件」说不清。
 *
 * 注：这一段原来写的是那个通配 MIME 的字面量（星号 斜杠 星号），
 * 结果**提前闭合了 KDoc 块注释**——后面整段注释都变成顶层垃圾代码，编译器报出
 * 几十条 `Expecting a top level declaration`，而 IDE 的语法检查当时还是绿的。
 * 真要写这个字面量，用 HTML 实体 `&#42;/&#42;` 拼，不要直接打出来。
 */
fun subtitleSourceOfDocument(resolver: ContentResolver, uri: Uri): SubtitleSource {
    val columns = queryDocumentColumns(resolver, uri)
    return subtitleSourceOf(
        path = uri.toString(),
        fileName = pickDisplayName(
            openableName = columns.openableName,
            documentName = columns.documentName,
            lastPathSegment = uri.lastPathSegment?.let(Uri::decode),
            uriText = uri.toString(),
        ),
        sizeBytes = columns.sizeBytes ?: 0L,
    )
}

/**
 * 从几路候选里挑出「这个文件叫什么」。
 *
 * 抽成纯函数（不碰 `ContentResolver`）是因为这里每一条分支都只在**别的 provider**
 * 上生效，实机上很难穷举：下载管理器给 `OpenableColumns`，文档 provider 给
 * `DocumentsContract` 的列，还有 provider 什么都不给。编不出名字时退到 uri 最后一段，
 * 再不行才退到整个 uri——是一条**有顺序的**退让，不是一个 `?:`。
 *
 * 最后一段还要切一刀：`content://…/document/primary%3ADownload%2Fa.srt` 的最后一段是
 * `primary:Download/a.srt`，那是**一条路径**而不是文件名，直接当标题用就是一长串。
 *
 * @param lastPathSegment 调用方已经 `Uri.decode` 过（纯函数不引 `android.net.Uri`，
 *   否则 JVM 单测里会撞上「not mocked」）。
 * @param uriText 兜底值，必须非空（`Uri.toString()` 保证）。
 */
internal fun pickDisplayName(
    openableName: String?,
    documentName: String?,
    lastPathSegment: String?,
    uriText: String,
): String {
    val fromPath = lastPathSegment
        ?.substringAfterLast('/')
        ?.substringAfterLast(':')
    return listOf(openableName, documentName, fromPath)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
        ?: uriText
}

/** [queryDocumentColumns] 的结果。三个字段都可以是 null——provider 有权什么都不给。 */
private class DocumentColumns(
    val openableName: String?,
    val documentName: String?,
    val sizeBytes: Long?,
)

/**
 * 一次查询同时问两组列名：不同 provider 认的列名不一样，而多查一组列名的代价是零。
 *
 * 不抛异常：这里是「问不出名字」，不是「读不了文件」。读不了的判断在真正读盘那一层
 * （`SubtitleRepository.readBytes` 的 `openInputStream == null`）——两件事合起来报，
 * 用户会收到一句和真实原因不符的提示。
 */
private fun queryDocumentColumns(resolver: ContentResolver, uri: Uri): DocumentColumns {
    val projection = arrayOf(
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    val empty = DocumentColumns(null, null, null)
    return try {
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use empty
            DocumentColumns(
                openableName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME),
                documentName = cursor.stringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                sizeBytes = cursor.longOrNull(OpenableColumns.SIZE)
                    ?: cursor.longOrNull(DocumentsContract.Document.COLUMN_SIZE),
            )
        } ?: empty
    } catch (error: Exception) {
        // SecurityException（授权被回收）最常见，但这里一律降级而不是失败：
        // 名字能从 uri 上推出来，文件本身还是能读的。
        MspLog.w(TAG, error) { "查不出所选文件的名字/大小，退回 uri 推断：$uri" }
        empty
    }
}

private fun Cursor.stringOrNull(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun Cursor.longOrNull(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index < 0 || isNull(index)) null else getLong(index)
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
 *
 * 调用方有两个，而且**必须共用这一处规则**：用户手选的那条（[subtitleSourceOf]）
 * 与自动发现的同目录字幕（[FileSystemSubtitleLocator]）。`uri` 是解析缓存的键，
 * 同一个文件按两条路拼出两个不同的 uri，就等于同一个文件被解析两次、缓存白建。
 */
internal fun readableUri(path: String): String =
    if (path.contains("://")) path else "file://$path"
