package com.multisuperplayer.core.data.browser

import com.multisuperplayer.core.common.text.MspText

/**
 * 文件浏览器里一个「目录来源」的类型。
 *
 * 只有两个值，这是**故意的**：
 *
 * - 「系统媒体库」不是一个来源。它的内容本来就在媒体库页，而「按文件夹看」
 *   由媒体库的 `LibraryGroupMode.FOLDER` 分组提供。把它也做成一个来源，
 *   等于让同一份数据有两条显示路径，两边迟早会不一致。
 * - 「网络位置」也不在这一版。它需要一个统一的连接/凭据模型，
 *   和本地目录共用一套 `ref` 只会把两种失败原因（路径不存在 / 连不上）混在一起。
 */
enum class BrowserSourceKind {

    /**
     * 内部存储 / SD 卡上的**绝对路径**，用 `java.io.File` 直接读写。
     *
     * 需要「所有文件访问」(`MANAGE_EXTERNAL_STORAGE`)。这是 Android 10+ 上
     * 唯一能真正自由浏览 `/sdcard`（含 `Download`、`.nomedia` 目录、任意后缀文件）
     * 的途径——系统选择器的文档树授权**永远**拿不到那几个位置。
     */
    FILE_SYSTEM,

    /**
     * 用户通过系统选择器授权的文档树（`DocumentsProvider`）。
     *
     * 零特殊权限，但范围固定：就是用户授权的那一棵树，以及它下面的所有层级。
     */
    SAF,
}

/**
 * 来源列表里的一项，也就是「从哪里开始浏览」。
 *
 * [ref] 的含义随 [kind] 变化，和 `BrowserEntry.ref` 同一套规则：
 * [FILE_SYSTEM] 是绝对路径，[SAF] 是授权树的 tree uri。
 *
 * [label] 用 [MspText] 而不是 `String`：内部存储 / SD 卡 / 云盘这些名字里，
 * 「内部存储」要翻译，而卷名（`1234-5678`）是数据、不能翻译。同一个字段要同时
 * 装这两种东西，只能让值本身携带「要不要翻译」的信息。
 */
data class BrowserRoot(
    val kind: BrowserSourceKind,
    val ref: String,
    val label: MspText,
    /** 副标题：卷路径或 uri，方便用户对上号。可能为空。 */
    val detail: String? = null,
    /**
     * 现在**用不了**的原因；`null` 表示可以用。
     *
     * 原始设计是一个 `available: Boolean`，但那个字段有个藏不住的问题：
     * 「不能用」在两种来源上要做的事完全不同（去开权限 / 重新授权），
     * 而布尔值只能让界面去猜。猜错的症状是「引导用户点一个没反应的按钮」。
     * 改成枚举之后界面可以 `when` 穷举，也就再也编不出「不知道该干什么」的分支。
     */
    val issue: BrowserRootIssue? = null,
) {
    /** 现在能不能进去。 */
    val available: Boolean get() = issue == null
}

/**
 * 一个来源现在为什么用不了。
 */
enum class BrowserRootIssue {

    /**
     * 这个系统版本上**根本没有**「所有文件访问」这个概念（API < 30）。
     *
     * 与 [ACCESS_OFF] 分开是必须的：`NOT_SUPPORTED` 时给一个「去开启」的按钮，
     * 会把用户送到一个**没有这一项**的设置页里，什么也做不了。
     */
    NOT_SUPPORTED,

    /** 有这个概念，但用户还没开。去设置里开一下就能用。 */
    ACCESS_OFF,

    /** SAF 授权被收回了（用户在设置里撤销、SD 卡拔出、provider 被卸载）。 */
    GRANT_REVOKED,
}
