package com.multisuperplayer.core.model

/**
 * 用户通过 SAF 授权给本应用的一个目录树。
 *
 * ## 为什么只存 uri 和展示名
 *
 * 授权本身由系统持有（`ContentResolver.persistedUriPermissions`），我们这边存的
 * 只是「用户授权过哪些树」的清单。系统那份是可以被单方面收回的（用户在设置里撤销、
 * SD 卡拔出、provider 被卸载），所以**清单里的条目随时可能失效**——凡是拿它去做
 * 事的代码都必须容忍「这条已经打不开了」，而不是把它当成事实。
 *
 * [label] 是加进来的那一刻从树 uri 里解出来的目录名（`primary:Music/Album` → `Album`）。
 * 之所以在加入时就算好、而不是让界面自己去解析 uri：
 *
 * 1. 解析规则是 `DocumentsContract` 的知识，属于数据层；
 * 2. 这条 uri 在**撤销授权之后仍然要能显示**（否则「管理已授权的目录」这种界面
 *    会退化成一行行的乱码 uri），届时 `DocumentsContract` 已经解不出它了。
 *
 * [label] 为 null 表示「这棵树就是某个存储卷的根」，界面应该换成
 * 「内置存储 / 存储卡」这种本地化的说法——而本地化的字符串不能存在数据层。
 */
data class SafTreeInfo(
    /** 树 uri 的原样字符串，例如 `content://com.android.externalstorage.documents/tree/primary%3AMusic`。 */
    val uri: String,
    /** 展示名；null = 「这是某个卷的根」。 */
    val label: String?,
    /** 加入时间，用于稳定排序。 */
    val addedAtMs: Long,
)
