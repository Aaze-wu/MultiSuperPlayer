package com.multisuperplayer.core.model

/**
 * 「不是从本机媒体库 / 本机目录来的」那些条目的 id 前缀。
 *
 * ## 为什么需要前缀
 *
 * [MediaEntry.id] 是这个项目里唯一能跨重启活着的东西：播放位置、最近播放、
 * 播放列表条目全部按 id 记。而 id 一旦写进播放列表，**来源就丢了**
 * （`PlaylistItem` 只存 id / uri / title / kind），回放时只能靠 id 反推
 * （见 `PlaylistItem.sourceOf`）。
 *
 * 现在有两个新来源是「外面递进来的」：
 *
 * | 前缀 | 谁造的 | uri 长什么样 | 回放时的 [MediaSource] |
 * |---|---|---|---|
 * | `url:` | 网络地址页里手输的地址 | `https://host/path/a.mp4` | [MediaSource.REMOTE] |
 * | `shared:` | 别的应用分享 / 用本应用打开的文件 | `content://…`、`file://…` | [MediaSource.SHARED] |
 *
 * 两者都**不能**复用 `file:`（`BrowserEntry.MEDIA_ID_PREFIX`）：那个前缀的含义是
 * 「本机文件系统里的一个绝对路径」，回放时会被送去按**文件路径**找同目录字幕。
 * 一个 `content://` 或 `https://` 的地址套上它，字幕查找会去问一个根本不存在的
 * 目录，然后安静地返回「没找到字幕」——不报错，只是永远不生效。
 *
 * ## 为什么 id 是「前缀 + uri」而不是别的稳定 id
 *
 * 网页/分享进来的文件没有 MediaStore 数字 id，也没有可用的稳定标识；
 * 而 uri 本身在这个场景里就是稳定的（同一个地址再次打开应当命中同一条播放记录）。
 * 让 id 从 uri 推导的另一个好处是它**可以被重新造出来**：播放列表导入后
 * `uri` 还在，id 就能还原，来源也跟着还原。这正是 [PlaylistItem.sourceOf]
 * 唯一需要的信息。
 *
 * ## 与 `saf:` 的区别
 *
 * `saf:` 同样不是媒体库 id，但它是**我们自己扫描出来的**、有目录层级的树
 * （见 `SafScanRules.safIdOf`），回放时字幕查找走「同目录」那条路。
 * 这里两个前缀描述的是「没有目录可查」的条目，字幕查找对它们是
 * `Unavailable`（`SubtitleLookup` 里显式列出的分支），所以不能混用。
 */
object ExternalMediaIds {

    /** 网络地址（http/https）的前缀。 */
    const val REMOTE_PREFIX = "url:"

    /** 别的应用递进来的文件（`ACTION_VIEW` / `ACTION_SEND`）的前缀。 */
    const val SHARED_PREFIX = "shared:"

    /** 网络地址页里手输/选择的那条地址的 id。 */
    fun remoteIdOf(url: String): String = REMOTE_PREFIX + url

    /** 外部应用递进来的那个 uri 的 id。 */
    fun sharedIdOf(uri: String): String = SHARED_PREFIX + uri

    fun isRemoteId(id: String): Boolean = id.startsWith(REMOTE_PREFIX)

    fun isSharedId(id: String): Boolean = id.startsWith(SHARED_PREFIX)
}
