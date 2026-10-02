package com.multisuperplayer.core.data.library

import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.model.MediaSource

/**
 * SAF 目录树里一个节点的**最小快照**。
 *
 * ## 为什么要这层抽象
 *
 * `DocumentFile` 在 JVM 单测里造不出来（`unitTests.isReturnDefaultValues = true`
 * 只让 `android.*` 的**普通方法**返回默认值，`DocumentFile.fromTreeUri()` 这类
 * 静态工厂仍会走真实代码，直接抛 `Method ... not mocked`）。而遍历逻辑里全是
 * 「本可以写错、但界面不报错」的判断：深度上限、循环检测、隐藏文件、类型判定。
 *
 * 把它们和「怎么访问 Android」拆开之后，遍历器 [SafTreeWalker] 就变成纯函数，
 * 可以在单测里用假树喂任意形状的目录结构（超深、自引用、读不到子项…）。
 *
 * 这一层也顺手解决了一个真实问题：`DocumentFile.listFiles()` 内部是跨进程
 * `ContentResolver.query`，可能抛 `SecurityException`（授权被撤销）。
 * 接口约定 [children] **不抛异常**、读不到就返回空表——把「授权失效」这个
 * 一定要处理的状态压成一种返回值，而不是散落在遍历逻辑里的 try/catch。
 */
internal interface DocumentNode {

    /** `DocumentsContract` 的 documentId，例如 `primary:Music/Album/a.mp3`。 */
    val documentId: String

    /** 可播放、可再次打开的 uri（**不是**树 uri）。 */
    val uri: String

    /**
     * 展示名（含后缀）。可能读不到——`DocumentsProvider` 在 `DISPLAY_NAME` 上
     * 返回 null 是允许的，那种情况下游用 [documentId] 里的文件名兜底。
     */
    val displayName: String?

    /** provider 上报的 MIME；`application/octet-stream` 和 null 都很常见。 */
    val mimeType: String?

    val isDirectory: Boolean

    /** provider 不给大小时是 0。 */
    val sizeBytes: Long

    /** `lastModified`，毫秒。给不了时是 0。 */
    val lastModifiedMs: Long

    /** 子节点。**约定不抛异常**：读不到（授权失效、IO 错误）返回空表。 */
    fun children(): List<DocumentNode>
}

/** 一次 SAF 扫描的结果。 */
internal data class SafScanOutcome(
    val entries: List<MediaEntry>,
    /** 是否因为上限/深度/不可展开而提前收工——界面据此提示「只显示了前 N 条」。 */
    val truncated: Boolean,
)

/**
 * 从一棵 [DocumentNode] 树里收出媒体条目。
 *
 * 纯函数：不碰 Android、不做 IO，所有输入都来自参数。上游只有
 * [SafTreeScanner] 一个调用者。
 */
internal object SafTreeWalker {

    /**
     * 深度优先收集。
     *
     * 四道护栏，每一道都对应一种**会把界面搞坏、而不是报错**的输入：
     *
     * 1. **已访问集合**：provider 完全可以返回一个子节点，其 documentId 等于它的
     *    祖先（外部存储的符号链接，或者干脆是 provider 的实现 bug），朴素递归会
     *    无限展开直到栈溢出——而栈溢出会杀掉进程，用户看到的是「添加文件夹就闪退」。
     *    用 documentId 去重就能截断这种环。
     * 2. **深度上限** [SafScanRules.MAX_DEPTH]：同样的理由，但对付「不重复、
     *    却一直更深」的树。
     * 3. **条数上限** [SafScanRules.MAX_ENTRIES]：用户完全可能把整块内置存储
     *    授权进来，那不设上限就是一次读几万个 document。
     * 4. **隐藏文件跳过**：`.` 开头的文件/目录在文件管理器里默认不显示，跳过它们
     *    符合用户预期，也顺手避开了 `.thumbnails` 这类缓存目录。
     */
    fun walk(root: DocumentNode): SafScanOutcome {
        if (!root.isDirectory) {
            // 授权进来的不是目录：当作「什么都扫不到」，并且标记成需要提示。
            return SafScanOutcome(entries = emptyList(), truncated = true)
        }
        val entries = mutableListOf<MediaEntry>()
        val visited = mutableSetOf<String>()
        var truncated = false

        fun visit(node: DocumentNode, depth: Int) {
            if (depth > SafScanRules.MAX_DEPTH || entries.size >= SafScanRules.MAX_ENTRIES) {
                truncated = true
                return
            }
            if (!visited.add(node.documentId)) return

            if (!node.isDirectory) {
                toEntry(node)?.let(entries::add)
                return
            }
            for (child in node.children()) {
                // 每进一个子项都重新检查上限：children() 一次可能返回几千项，
                // 只在入口检查的话，上限会被「一个宽目录」直接冲过去。
                if (entries.size >= SafScanRules.MAX_ENTRIES) {
                    truncated = true
                    return
                }
                val name = child.displayName
                if (name != null && name.startsWith('.')) continue
                visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return SafScanOutcome(entries = entries, truncated = truncated)
    }

    /**
     * 单个节点 → 媒体条目；`null` 表示「不是媒体，别收」。
     *
     * 与 [MediaStoreScanner] 构造条目的方式刻意保持一致的地方：
     * - `source = MediaSource.SAF_TREE`，「这条是从哪来的」在界面上可分辨；
     * - **`artworkUri` 留 null**：SAF 条目没有 `MediaStore` 的缩略图通道，而
     *   `content://media/external/audio/albumart/...` 那个老 scheme 从 Android 10
     *   起就不可靠。界面本来就对 null 封面有一条兜底路径（画首字母方块）。
     *
     * 刻意**不填**的：`artist` / `album` / `durationMs` 等标签信息。要拿到它们
     * 必须给每个文件开一次 `MediaMetadataRetriever`（跨进程、几十毫秒/文件），
     * 在一个可能上万条目的扫描里不可接受。代价是所有 SAF 独有的条目在
     * 「按艺术家/专辑分组」里会落到「未知」组——这是已知取舍，不是遗漏。
     */
    private fun toEntry(node: DocumentNode): MediaEntry? {
        val name = node.displayName?.takeIf { it.isNotBlank() }
            ?: SafScanRules.fileNameOf(node.documentId).takeIf { it.isNotBlank() }
            ?: return null
        val kind = SafScanRules.kindOf(name, node.mimeType) ?: return null
        return MediaEntry(
            id = SafScanRules.safIdOf(node.documentId),
            uri = node.uri,
            title = name.substringBeforeLast('.', name).takeIf { it.isNotBlank() } ?: name,
            kind = kind,
            source = MediaSource.SAF_TREE,
            displayName = name,
            relativePath = SafScanRules.relativePathOf(node.documentId),
            sizeBytes = node.sizeBytes,
            mimeType = node.mimeType,
            dateAddedSeconds = node.lastModifiedMs / 1000L,
        )
    }
}
