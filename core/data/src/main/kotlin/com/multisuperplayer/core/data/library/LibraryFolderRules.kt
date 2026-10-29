package com.multisuperplayer.core.data.library

/**
 * 「媒体库只显示选中的文件夹」这条偏好的**全部判定规则**。
 *
 * 为什么单独抽成纯函数：这些规则同时被三个人用——媒体库的界面状态（`feature:library`）、
 * 偏好子页的勾选逻辑（`feature:settings`）、以及它们的单测。而平台 API
 * （`DocumentFile`、`DocumentsContract`、`Uri`）在本项目的 JVM 单测里一律返回
 * null/0（`isReturnDefaultValues = true`），凡是挂在它们身上的判断等于没测。
 * 所以这里只吃 `String`，不碰任何 `android.*`。
 *
 * ## 路径形态
 *
 * 一律用「相对**卷根**的文件夹路径」表示，**不带结尾斜杠**：
 * - `""` = 卷根（文件就直接躺在存储根，不属于任何子文件夹）
 * - `"Music"`、`"Music/Live"` = 子文件夹
 *
 * 它和 [MediaEntry.relativePath][com.multisuperplayer.core.model.MediaEntry.relativePath]
 * 的唯一区别是后者**带**结尾斜杠（`"Music/Live/"`），因为那个值要和 MediaStore 的
 * `RELATIVE_PATH` 直接比较。转换统一走 [folderPathOf]，别在别处手写 `trimEnd('/')`。
 *
 * ## 「选中的文件夹包含它的子文件夹」
 *
 * 勾上 `Music` 就显示 `Music/Live` 里的歌，见 [covers]。于是**已选集必须保持最小**
 * （见 [minimize]）：同时存 `Music` 和 `Music/Live` 是冗余的，冗余会让
 * 「取消勾选 `Music/Live`」变得无法表达——集合里还留着一个覆盖它的 `Music`，
 * 取消之后照样显示。这正是 [toggle] 存在的理由。
 *
 * ## 空选集
 *
 * `selected.isEmpty()` ⇒ **一条都不显示**。这是产品决定（默认「都不显示，并提示相关
 * 信息」），不是「没有限制」。所以调用方绝不能把「读不出偏好」折成空集——
 * 那是 [LibraryFolderSettings][com.multisuperplayer.core.data.settings.LibraryFolderSettings]
 * 里 `null` 的语义（未知 ⇒ 不做过滤）。
 *
 * ## 算不出相对路径的条目
 *
 * [accepts] 对 `relativePath == null` 返回 **false**（严格排除）。两个真实来源：
 * 1. `MediaStore.RELATIVE_PATH` 是 API 29 才有的列，本应用 minSdk 26 ⇒ 在 API 26-28 上
 *    **每一条** MediaStore 条目都没有相对路径；
 * 2. SAF 的 documentId 里没有冒号时（不是 `ExternalStorageProvider`，例如各种云盘 provider）
 *    [SafScanRules.relativePathOf] 也返回 null。
 *
 * 选严格而不是「保留」是因为宽松会让被排除的目录里的文件照样出现——用户明明取消了勾选，
 * 文件还在，这种 bug 用户一眼就能看见且无法解释。严格的代价是这些条目在有勾选时消失，
 * 但「一条都不勾」时它们本来也不显示，所以不会出现「勾了反而少了一批」的反直觉变化。
 */
object LibraryFolderRules {

    /**
     * 把 [MediaEntry.relativePath][com.multisuperplayer.core.model.MediaEntry.relativePath]
     * 折算成规范化的文件夹路径；`null` 表示「算不出来」，和 `""`（卷根）**不是一回事**。
     *
     * MediaStore 的 `RELATIVE_PATH` 值形如 `Music/Album/`，SAF 那条路
     * （[SafScanRules.relativePathOf]）也一样带结尾斜杠，所以这里是唯一的折点。
     */
    fun folderPathOf(relativePath: String?): String? {
        val raw = relativePath?.trim() ?: return null
        if (raw.isEmpty()) return ""
        return raw.trimEnd('/')
    }

    /** [parent] 覆盖 [child] 吗？父子、自己、以及多级后代都算覆盖。 */
    fun covers(parent: String, child: String): Boolean =
        parent == child || (parent.isNotEmpty() && child.startsWith("$parent/"))

    /** [path] 是否已被 [selected] 里某一项覆盖（含「自己被选中」）。 */
    fun isCovered(selected: Set<String>, path: String): Boolean =
        selected.any { covers(it, path) }

    /**
     * 这个条目该不该进媒体库。
     *
     * 空选集 ⇒ false；算不出相对路径 ⇒ false（理由见类注释最后一节）。
     */
    fun accepts(relativePath: String?, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return false
        val path = folderPathOf(relativePath) ?: return false
        return isCovered(selected, path)
    }

    /**
     * 去掉被别的已选项覆盖的冗余项，让集合保持**最小**。
     *
     * `{"Music", "Music/Live"}` → `{"Music"}`。卷根 `""` 只覆盖自己，所以它不会被别的项吃掉。
     */
    fun minimize(selected: Set<String>): Set<String> =
        selected.filterNot { path ->
            selected.any { other -> other != path && covers(other, path) }
        }.toSet()

    /**
     * 勾选/取消勾选 [target] 之后的新选集。
     *
     * 三件事必须一起做，缺一个都会出现「勾了没反应」或「取消不掉」：
     * 1. **打开**：把 [target] 加进去，并顺手丢掉它的后代（[minimize] 的活）；
     * 2. **关闭自己**（[target] 就在集合里）：直接去掉，后代替它变回未选中；
     * 3. **关闭被祖先覆盖的项**（勾了 `Music` 之后取消 `Music/Live`）：不能只把 `Music`
     *    删掉再什么都不加——那会连 `Music` 里别的文件夹一起关掉。正确做法是把祖先
     *    **展开**：删掉 `Music`，再把 `Music` 下除了 `target` 那一支的**其余子文件夹**
     *    显式加进去。展开需要完整的文件夹清单，所以 [allFolders] 是必填参数。
     *
     * @param allFolders 界面上存在的**全部**文件夹路径（[folderNodes] 的 `path` 列）。
     *   它只用来做第 3 步的展开；传空集合时第 3 步退化成「整支关掉」。
     */
    fun toggle(
        selected: Set<String>,
        target: String,
        allFolders: Collection<String>,
    ): Set<String> {
        val current = minimize(selected)
        val owner = current.firstOrNull { covers(it, target) }
            ?: return minimize(current + target)
        val rest = current - owner
        // owner 已展开：保留 owner 底下除了 target 那一支之外的所有文件夹。
        //
        // `path != owner` 不能省：`covers(owner, owner)` 为真，不排掉自己的话这一行
        // 会把刚删掉的 owner 原样加回来 ⇒ 「取消勾选」看起来完全没反应（而集合里
        // 确实少了目标项，更难查）。
        val siblings = allFolders.filter { path ->
            path != owner && path != target && !covers(target, path) && covers(owner, path)
        }
        return minimize(rest + siblings)
    }

    /**
     * 全选：把整个文件夹清单折成最小形式。
     *
     * 卷根 `""` 只覆盖自己，所以结果里一定同时有 `""` 和各层顶级文件夹——
     * 那是这条语义的必然结果，不是漏优化（见 [covers]）。
     */
    fun selectAll(allFolders: Collection<String>): Set<String> = minimize(allFolders.toSet())

    /**
     * 偏好子页要显示的文件夹树，**先序**排列。
     *
     * 排序只用 `String` 自然序，**不是**随便选的：`'/'`(0x2F) 比所有数字和字母都小，
     * 于是 `"" < "A" < "A/B" < "A2" < "AB"` —— 父永远排在子前面，同一层的兄弟
     * 挨在一起，正好就是树的先序遍历，不需要再写一个带递归的排序器。
     *
     * 每个路径的**所有祖先都会被补进来**（见 [ancestorsOf]）：`Music/Live/` 里只有歌
     * 的时候，`Music` 这一行也必须存在，否则界面上冒出一个没有父节点的缩进行。
     *
     * @param relativePaths 每条媒体条目的 `relativePath`；条目来自 MediaStore 与 SAF，
     *   其中可能有 `null`（算不出来）——这些条目不属于任何文件夹，直接忽略。
     */
    fun folderNodes(relativePaths: Iterable<String?>): List<FolderNode> {
        val direct = mutableMapOf<String, Int>()
        relativePaths.forEach { raw ->
            val path = folderPathOf(raw) ?: return@forEach
            direct[path] = (direct[path] ?: 0) + 1
        }
        if (direct.isEmpty()) return emptyList()
        val all = mutableSetOf<String>()
        direct.keys.forEach { path ->
            all += path
            all += ancestorsOf(path)
        }
        return all.sorted().map { path ->
            FolderNode(
                path = path,
                name = nameOf(path),
                depth = if (path.isEmpty()) 0 else path.count { it == '/' } + 1,
                directCount = direct[path] ?: 0,
                totalCount = direct.entries.sumOf { (other, count) ->
                    if (covers(path, other)) count else 0
                },
            )
        }
    }

    /**
     * [path] 的所有祖先（不含自己），从最外层开始。
     *
     * `"A/B/C"` → `["", "A", "A/B"]`。卷根 `""` 永远是第一个祖先：
     * 树里得有它，否则根目录下的文件在界面上无处可去。
     */
    fun ancestorsOf(path: String): List<String> {
        if (path.isEmpty()) return emptyList()
        val parts = path.split('/')
        return buildList {
            add("")
            for (index in 1 until parts.size) {
                add(parts.subList(0, index).joinToString("/"))
            }
        }
    }

    /** 文件夹的**显示名**：路径最后一段；卷根没有名字（调用方给「存储根目录」这条文案）。 */
    fun nameOf(path: String): String = if (path.isEmpty()) "" else path.substringAfterLast('/')
}

/**
 * 偏好子页里的一行。
 *
 * [depth] 是给界面缩进用的（`""` 为 0，`"Music"` 为 1，`"Music/Live"` 为 2），
 * **放在这里算而不是在 composable 里**：层级是数据属性，且这样能被单测钉住。
 *
 * [directCount] 与 [totalCount] 必须分开：勾选是「包含子文件夹」的，所以界面上要显示
 * [totalCount]，但只看 [totalCount] 会把「这个文件夹自己是空的、全是子文件夹里的东西」
 * 显示成有货——两个数字都在，界面想怎么解释都行。
 */
data class FolderNode(
    val path: String,
    val name: String,
    val depth: Int,
    val directCount: Int,
    val totalCount: Int,
)
