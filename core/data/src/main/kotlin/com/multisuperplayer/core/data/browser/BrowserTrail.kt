package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.BrowserEntry

/**
 * 面包屑的一段：显示名 + 回到这里要用的 [ref]。
 *
 * [label] 的类型是 [MspText] 而不是 `String`，因为这条面包屑的**第一段**
 * 来自 `BrowserRoot.label`——那里可能是 `R.string.msp_browser_storage_internal`
 * 这类要按用户语言解析的资源，而后面几段是目录名（用户自己的数据，不需要翻译）。
 * 两种来源混在同一条链上，所以类型只能是 [MspText]；
 * 界面在渲染时一次性把它解析掉。
 *
 * 反过来若把它做成 `String`，数据层就必须持有 `Resources` 才能拼出第一段——
 * 那正是本仓库一直避免的（数据层不说人话）。
 */
data class BrowserCrumb(val label: MspText, val ref: String)

/**
 * 当前浏览位置的**导航栈**，也是界面上那条面包屑。
 *
 * ## 为什么是「栈」而不是「当前路径」
 *
 * 因为「上一层」在三种来源里有三种算不出来的情况：
 *
 * - SAF 的 document 没有父指针。`DocumentsContract` 给不出「这个 document 的
 *   父 document id」，只有从 tree 根往下逐级 `buildChildDocumentsUriUsingTree`
 *   才走得通。想从 `/Music/Album` 反推出 `/Music`，得把 tree 根到当前层
 *   整条链重新走一遍。
 * - 文件系统倒是能靠 `File.parent` 算，但**列目录**（不是路径运算）才是唯一可靠的
 *   判据：软链接、大小写不敏感的卷、`/storage/emulated/0` 与 `/sdcard` 互为别名，
 *   都会让「算出来的父路径」和「实际的父目录」不一致。
 * - 而逐级进入时，**每一层的 ref 本来就拿在手上**（就是上一个目录里那一行的 `ref`）。
 *
 * 所以这里把经过的每一层都记下来，[up] / [jumpTo] 只是把列表截断——
 * 零路径运算，因此对三种来源都成立。
 *
 * ## 代价（必须知道）
 *
 * 这个栈**只在应用进程内有效**，而且**不跟着外部变化走**：如果用户切到别的应用
 * 把我们栈里某一层的目录删了，`up()` 回到那一层的 ref 就失效了。这不是需要修的 bug，
 * 而是「记得来路」的必然结果——[BrowserListing.NotADirectory] / `Unreadable`
 * 就是为这种情况准备的出口：界面应该退回上一层，而不是留在原地重试。
 */
data class BrowserTrail(
    val kind: BrowserSourceKind,
    val crumbs: List<BrowserCrumb>,
) {
    init {
        require(crumbs.isNotEmpty()) { "BrowserTrail 至少要有一段，用 BrowserTrail.root(...) 构造" }
    }

    /** 当前所在的目录。 */
    val current: BrowserCrumb get() = crumbs.last()

    /** 能不能回上一层（已经在根上就不能）。 */
    val canGoUp: Boolean get() = crumbs.size > 1

    /** 进入当前目录下的一个子目录。 */
    fun enter(label: MspText, ref: String): BrowserTrail =
        copy(crumbs = crumbs + BrowserCrumb(label, ref))

    /**
     * 进入列表里的一个目录条目。
     *
     * 只有目录能进——传文件进来是调用方的编程错误（目录名与文件名的语义完全不同，
     * 静默接受会让面包屑上出现一个「能点开但打不开」的假目录）。
     */
    fun enter(entry: BrowserEntry): BrowserTrail {
        require(entry.isDirectory) { "只能进入目录：${entry.ref}" }
        return enter(MspText.Plain(entry.name), entry.ref)
    }

    /** 回上一层；已经在根上时返回自己。 */
    fun up(): BrowserTrail =
        if (canGoUp) copy(crumbs = crumbs.dropLast(1)) else this

    /** 跳到面包屑的第 [index] 段（点面包屑用）。越界时返回自己。 */
    fun jumpTo(index: Int): BrowserTrail =
        if (index in crumbs.indices) copy(crumbs = crumbs.subList(0, index + 1).toList()) else this

    companion object {
        /** 从某个来源的某个位置开始浏览。 */
        fun root(kind: BrowserSourceKind, label: MspText, ref: String): BrowserTrail =
            BrowserTrail(kind, listOf(BrowserCrumb(label, ref)))

        /** 从来源清单里的一行开始浏览（`BrowserRoot` 与 `BrowserCrumb` 字段一一对应）。 */
        fun root(root: BrowserRoot): BrowserTrail =
            BrowserTrail(root.kind, listOf(BrowserCrumb(root.label, root.ref)))
    }
}
