package com.multisuperplayer.core.data.library

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry

private const val TAG = "SafTreeScanner"

/**
 * 一次查询就要回来的列。
 *
 * 这五列正好覆盖 [DocumentNode] 需要的全部元数据。**一个都不能少**：漏掉的每一列
 * 都会在旧实现里变成「每个子项一次额外 IPC」，也就是这次性能修复要消灭的东西。
 */
private val DOCUMENT_PROJECTION = arrayOf(
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_SIZE,
    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
)

/**
 * [DocumentNode] 的真身：**一行** `DocumentsContract` 查询结果。
 *
 * ## 为什么不再包装 `DocumentFile`
 *
 * `androidx.documentfile` 的 `TreeDocumentFile` 把每个属性都写成一次独立的
 * `ContentResolver.query`：`getName()` / `getType()` / `isDirectory()` /
 * `length()` / `lastModified()` 各查一次；而 `listFiles()` 只取
 * `documentId / displayName / mimeType` 三列、**把光标扔掉**，于是调用方要为每个
 * 子项重新补查。一个子节点的快照 = **5 次跨进程调用**。
 *
 * 这条乘法是致命的：系统选择器允许授权一整块存储（`primary:`），那棵树里可能有
 * **两万个 document**（照片、文档、缩略图缓存、导出文件…），而其中媒体文件只有
 * 几百个。5 × 20000 = 十万次 IPC，实测从几秒到几十秒；界面上的表现却是
 * 「启动后一直转圈」，完全不像「文件很多」该有的样子，也完全看不出瓶颈在扫描。
 *
 * 现在改成**一个目录一次查询**：子项需要的五列一起要回来，元数据直接来自这一行，
 * 不再有任何补查。IPC 次数从 `O(文件数 × 5)` 降到 `O(目录数)`。
 *
 * 用法的另一个好处：[documentId] 直接来自 `COLUMN_DOCUMENT_ID`，不再需要
 * `DocumentsContract.getDocumentId(uri)` 从 uri 里反解。旧实现在非
 * `ExternalStorageProvider` 的 provider（云盘、第三方）上会抛
 * `IllegalArgumentException: Invalid URI`，只能退化成用 uri 当 id（相对路径算不出来）；
 * 这里拿到的就是 provider 自己上报的 id，不会再走到那个退化分支。
 *
 * 命名空间内部可见：它仍是**唯一**碰 `DocumentsContract` 的地方，遍历逻辑
 * （[SafTreeWalker]）和它严格分开，那份逻辑才有单测。
 */
internal class DocumentRowNode(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    override val documentId: String,
    override val uri: String,
    override val displayName: String?,
    override val mimeType: String?,
    override val sizeBytes: Long,
    override val lastModifiedMs: Long,
) : DocumentNode {

    override val isDirectory: Boolean get() = SafScanRules.isDirectoryMime(mimeType)

    /**
     * 读不到子项就返回空表。
     *
     * 这是本类最重要的一条约定：`query()` 在授权被撤销、provider 进程被杀、
     * 目录刚好被删掉时会抛 `SecurityException` / `IllegalStateException` /
     * `IllegalArgumentException`（异常类型由 provider 决定，不敢穷举）。
     * 这些情况全部要降级成「这个目录是空的」，而不是让整次扫描炸掉——
     * 一次扫描是一整棵树的循环，中间炸掉等于用户前面所有的目录都白扫。
     */
    override fun children(): List<DocumentNode> = queryDocuments(
        resolver = resolver,
        // 子项 uri 必须用**树** uri 拼：tree uri 是目录 uri，交给 Media3 会直接报错。
        // `buildChildDocumentsUriUsingTree` / `buildDocumentUriUsingTree` 都会自己从
        // 任意带 `/tree/<树 id>` 的 uri 里取出树 id，所以这里传树 uri 就够了。
        queryUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId),
        treeUri = treeUri,
    )
}

/**
 * 查一个 uri 下的文档，把每行映射成一个 [DocumentRowNode]。
 *
 * 目录（`build...ChildDocumentsUriUsingTree`）返回 0..N 行，单个文档
 * （`buildDocumentUriUsingTree`）返回 0..1 行——两个调用点共用这段映射，
 * 免得「列的下标怎么取」写两遍。
 *
 * 失败一律降级成空表（约定见 [DocumentRowNode.children]）。
 */
private fun queryDocuments(
    resolver: ContentResolver,
    queryUri: Uri,
    treeUri: Uri,
): List<DocumentNode> {
    val cursor = runCatching { resolver.query(queryUri, DOCUMENT_PROJECTION, null, null, null) }
        .onFailure { error -> MspLog.w(TAG, error) { "读取目录失败，按空目录处理" } }
        .getOrNull()
        ?: return emptyList()
    return cursor.use { rows ->
        val idIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        if (idIndex < 0) {
            MspLog.w(TAG) { "provider 没有返回 documentId 列，跳过这一项" }
            return emptyList()
        }
        val nameIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val mimeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
        val modifiedIndex = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val nodes = ArrayList<DocumentNode>(rows.count.coerceAtLeast(0))
        while (rows.moveToNext()) {
            // 没有 documentId 的行没法用：它既是去重键又是条目 id，还决定子项 uri。
            val documentId = rows.stringOrNull(idIndex)?.takeIf { it.isNotBlank() } ?: continue
            nodes += DocumentRowNode(
                resolver = resolver,
                treeUri = treeUri,
                documentId = documentId,
                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
                displayName = rows.stringOrNull(nameIndex),
                mimeType = rows.stringOrNull(mimeIndex),
                sizeBytes = rows.longOrZero(sizeIndex),
                lastModifiedMs = rows.longOrZero(modifiedIndex),
            )
        }
        nodes
    }
}

/** 列不存在、值为 null 都算「没有」，不抛异常。 */
private fun Cursor.stringOrNull(index: Int): String? =
    if (index < 0 || isNull(index)) null else getString(index)

/** `SIZE` / `LAST_MODIFIED` 缺失时按 0 处理（约定与旧实现一致）。 */
private fun Cursor.longOrZero(index: Int): Long =
    if (index < 0 || isNull(index)) 0L else getLong(index)

/**
 * 扫描用户通过 SAF 授权的目录树。
 *
 * ## 这一层为什么存在（而不是直接在 Repository 里 foreach）
 *
 * 1. **树 uri 不一定还有效**。`takePersistableUriPermission` 拿到的授权会被系统
 *    在「用户在设置里撤销」「SD 卡拔出」「provider 被卸载」时静默收回，
 *    而我们的 DataStore 里还留着那条 uri。所以每条树都要先验权限再扫。
 * 2. **树 uri 不能播**。`content://.../tree/primary%3AMusic` 是**目录** uri，
 *    交给 Media3 会直接报错；条目必须用 `document` uri。子节点的 uri 由
 *    `DocumentsContract.buildDocumentUriUsingTree` 生成（[DocumentRowNode.uri]），
 *    它和 `DocumentFile` 里的 `TreeDocumentFile` 拿到的是同一种可播 uri——
 *    这一点不写下来，下一个人很容易自己拼一个 tree uri 出来。
 *
 * 类本身是 `public`（[MediaLibraryRepository] 的公开构造参数不能是 internal 类型，
 * 和 [MediaStoreScanner] 同理），但返回模块内部类型的两个方法收成 `internal`。
 */
class SafTreeScanner(private val context: Context) {

    /** 授权是否还在。 */
    fun hasReadPermission(treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.persistedUriPermissions.any { permission ->
                permission.isReadPermission && permission.uri == uri
            }
        }.onFailure { error -> MspLog.w(TAG, error) { "读取持久化 URI 权限失败" } }
            .getOrDefault(false)
    }

    /**
     * 打开一棵树；`null` 表示「打不开」（授权失效、uri 不是树）。
     *
     * 这里对根目录**只查一次**，而且这一次查询同时是三件事：
     *
     * 1. 「这棵树现在还能不能打开」的探针。授权被撤销时 `query()` 会抛
     *    `SecurityException`，[queryDocuments] 把它降级成空表，这里再变成 `null`，
     *    于是 [scan] 跳过这棵树，而不是让整次扫描崩掉。
     * 2. 拿到根目录的元数据（documentId/displayName/mimeType…）。
     * 3. 确认它是目录。树 uri 理论上只能指向目录（系统选择器只让选文件夹），
     *    但 provider 的实现不受我们约束；不是目录就当打不开——与旧实现
     *    （`fromTreeUri` + `isDirectory`）一致。
     *
     * `internal`：返回的 [DocumentNode] 是模块内部类型（`MediaLibraryRepository`
     * 的公开构造参数不能是 internal 类型，所以只有这里收窄）。
     */
    internal fun open(treeUri: String): DocumentNode? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        // `getTreeDocumentId` 是纯 uri 解析（不解码、不查 provider），失败就是「这不是树 uri」。
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val root = queryDocuments(
            resolver = context.contentResolver,
            queryUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId),
            treeUri = uri,
        ).singleOrNull() ?: return null
        return root.takeIf { it.isDirectory }
    }

    /**
     * 扫一到多棵树。
     *
     * 多棵树分开扫再合并，而不是「先合并成一棵树再扫」：树之间可能互相包含
     * （用户先后授权了 `primary:` 和 `primary:Music`），合并会重复遍历；
     * 而按 [MediaEntry.id] 去重是免费的（id 就是 documentId，卷内唯一）。
     */
    internal fun scan(treeUris: List<String>): SafScanOutcome {
        if (treeUris.isEmpty()) return SafScanOutcome(entries = emptyList(), truncated = false)
        val byId = LinkedHashMap<String, MediaEntry>()
        var truncated = false
        for (treeUri in treeUris) {
            val root = open(treeUri) ?: continue
            val outcome = SafTreeWalker.walk(root)
            truncated = truncated || outcome.truncated
            outcome.entries.forEach { entry -> byId[entry.id] = entry }
        }
        return SafScanOutcome(entries = byId.values.toList(), truncated = truncated)
    }
}
