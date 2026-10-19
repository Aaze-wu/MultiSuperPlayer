package com.multisuperplayer.core.data.browser

/**
 * 一个可以「列一层目录」的东西。
 *
 * ## 只有列目录，没有别的
 *
 * 这里刻意**不放**「复制 / 移动 / 删除」：那三个操作在三种来源上需要的 API
 * 完全不同（`File` 直接做、SAF 要用 `DocumentsContract` 的 rename/delete、
 * 而且 SAF 删除还会失败得很晚），塞进这个接口只会让每个实现都要写一堆
 * `UnsupportedOperation`。文件操作单独一层，见 `BrowserFileOps`。
 *
 * ## [ref] 是不透明的
 *
 * 实现**不应该**让调用方知道 `ref` 长什么样。界面只做一件事：把
 * `BrowserEntry.ref` 原样交回来。任何「看起来像路径就自己拼一下」的优化，
 * 都会在换成 provider 来源时静默失效。
 */
internal interface DirectorySource {

    val kind: BrowserSourceKind

    /**
     * 列出 [ref] 这一层。
     *
     * ## 契约
     *
     * - 返回的条目**不排序、不过滤**（含隐藏项）。排序与隐藏项开关由
     *   `BrowserRules.arrange` 统一处理——三个来源各排一次，行为迟早不一致。
     * - **必须**区分「空目录」(`Ready(emptyList())`) 和「读不到」([BrowserListing.Unreadable])。
     *   把读不到当成空目录，等于把「去重新授权」这条唯一的出路藏起来。
     * - 不该抛异常。调用方在 UI 线程链路上，一个漏出的异常会直接杀进程；
     *   各种 IO / provider 异常都收敛成 [BrowserListing.Unreadable]。
     */
    suspend fun list(ref: String): BrowserListing

    /**
     * [ref] 这一层有多少个**直接子项**（文件 + 文件夹各算 1，不递归）。
     *
     * 返回 null 表示「问不到」：授权被收回、provider 不支持、读目录时出错。
     * 界面必须把 null 当成「这次不显示数量」，**不能**当成 0——那会写成
     * 「0 项」，而用户明明点得进去。这个区分的理由和 [BrowserListing] 里
     * 「空目录 ≠ 读不到」完全一样。
     *
     * 默认实现返回 null：不是每个实现都值得为它多问一次 provider。
     */
    suspend fun count(ref: String): Int? = null
}
