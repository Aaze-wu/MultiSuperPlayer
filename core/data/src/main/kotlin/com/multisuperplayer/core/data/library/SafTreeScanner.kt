package com.multisuperplayer.core.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.multisuperplayer.core.common.log.MspLog
import com.multisuperplayer.core.model.MediaEntry

private const val TAG = "SafTreeScanner"

/**
 * [DocumentNode] 的真身：把 `DocumentFile` 包成不抛异常的只读快照。
 *
 * 命名空间内部可见，因为它是**唯一**碰 `DocumentFile` 的地方——遍历逻辑
 * （[SafTreeWalker]）和它严格分开，那份逻辑才有单测。
 */
internal class DocumentFileNode(
    private val file: DocumentFile,
) : DocumentNode {

    /**
     * 只在构造时算一次。
     *
     * `DocumentsContract.getDocumentId()` 是一次跨进程调用，而遍历时每个节点至少
     * 会被问一次 documentId（进已访问集合、算相对路径、拼 id）。真机上一个几千
     * 条的目录里，这能省下几千次 IPC。
     */
    override val documentId: String = runCatching { DocumentsContract.getDocumentId(file.uri) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        // 不是 ExternalStorageProvider（云盘、第三方 provider）时 getDocumentId 会抛
        // `IllegalArgumentException: Invalid URI`。退化成 uri 本身：它依然是稳定的
        // 去重键与 id，只是相对路径算不出来（[SafScanRules.relativePathOf] 会返回 null），
        // 于是这些条目不参与「与 MediaStore 去重」——保留重复总好过误删一条。
        ?: file.uri.toString()

    override val uri: String = file.uri.toString()

    override val displayName: String? = file.name

    override val mimeType: String? = runCatching { file.type }.getOrNull()

    override val isDirectory: Boolean = file.isDirectory

    override val sizeBytes: Long = runCatching { file.length() }.getOrDefault(0L)

    override val lastModifiedMs: Long = runCatching { file.lastModified() }.getOrDefault(0L)

    /**
     * 读不到子项就返回空表。
     *
     * 这是本类最重要的一条约定：`listFiles()` 在授权被撤销、provider 进程被杀、
     * 目录刚好被删掉时会抛 `SecurityException` / `IllegalStateException` /
     * `IllegalArgumentException`（异常类型由 provider 决定，不敢穷举）。
     * 这些情况全部要降级成「这个目录是空的」，而不是让整次扫描炸掉——
     * 一次扫描是一整棵树的循环，中间炸掉等于用户前面所有的目录都白扫。
     */
    override fun children(): List<DocumentNode> {
        val files = runCatching { file.listFiles() }
            .onFailure { error -> MspLog.w(TAG, error) { "读取目录失败，按空目录处理" } }
            .getOrElse { return emptyList() }
        return files.map { child -> DocumentFileNode(child) }
    }
}

/**
 * 扫描用户通过 SAF 授权的目录树。
 *
 * ## 这一层为什么存在（而不是直接在 Repository 里 foreach）
 *
 * 1. **树 uri 不一定还有效**。`takePersistableUriPermission` 拿到的授权会被系统
 *    在「用户在设置里撤销」「SD 卡拔出」「provider 被卸载」时静默收回，
 *    而我们的 DataStore 里还留着那条 uri。所以每条树都要先验权限再扫。
 * 2. **树 uri 不能播**。`content://.../tree/primary%3AMusic` 是**目录** uri，
 *    交给 Media3 会直接报错；条目必须用 `document` uri。
 *    `DocumentFile` 的 `TreeDocumentFile` 内部正是用
 *    `DocumentsContract.buildDocumentUriUsingTree` 生成子节点 uri 的，所以
 *    [DocumentFileNode.uri] 拿到的已经是可播的那种——这一点不写下来，
 *    下一个人很容易自己拼一个 tree uri 出来。
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
     * `internal`：返回的 [DocumentNode] 是模块内部类型（`MediaLibraryRepository`
     * 的公开构造参数不能是 internal 类型，所以只有这里收窄）。
     */
    internal fun open(treeUri: String): DocumentNode? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val root = runCatching { DocumentFile.fromTreeUri(context, uri) }
            .onFailure { error -> MspLog.w(TAG, error) { "打开 SAF 目录失败" } }
            .getOrNull()
            ?: return null
        // fromTreeUri 在「uri 不是 tree uri」时会返回 null；已经能打开的目录也
        // 顺手验一遍 isDirectory——文件被选中时有些 provider 会放行。
        if (!root.isDirectory) return null
        return DocumentFileNode(root)
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
