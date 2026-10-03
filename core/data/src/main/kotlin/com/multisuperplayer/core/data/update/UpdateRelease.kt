package com.multisuperplayer.core.data.update

/**
 * 更新源上的一条发行版。
 *
 * 它是**已经解析好的**结果，不是网络层的原始响应：`tagName` 已经翻译成
 * [version]，APK 资产也已经是「挑出来的那一个」（[apkUrl] / [apkSizeBytes] /
 * [apkSha256] 三个字段同属同一个资产）。
 *
 * 之所以不让上层自己去读「assets 里的第几个」或者自己切 tag：
 * 那些判断在遇到一个 `v0.7.0.zip` 资产、一个不带 `v` 的 tag、或者一次
 * 上传了两个 APK 的发布时会**安静地**给出错的结果——拿错的资产去下载，
 * 用户看到的是「下载完了但装不上」，而代码里没有任何地方报错。
 */
data class UpdateRelease(
    /** 原始 tag（`v0.7.0-alpha.1`）。它是**忽略某一版**的键，必须原样保留。 */
    val tagName: String,
    val version: UpdateVersion,
    val isPreRelease: Boolean,
    val publishedAtEpochMs: Long?,
    /** 发行说明原文（Markdown）。可能很长，怎么截断是界面的事。 */
    val notes: String?,
    /** APK 的下载地址。这一条发行版没有可下载的 APK 时为 null。 */
    val apkUrl: String?,
    val apkSizeBytes: Long?,
    /** 校验值（`sha256:` 之后的十六进制部分）。源没提供时为 null。 */
    val apkSha256: String?,
) {

    /** 有没有一个能装的东西。没有的话「有更新」这句话是无法兑现的。 */
    val isInstallable: Boolean get() = !apkUrl.isNullOrBlank()
}

/**
 * 一个更新源：能列出可用的发行版。
 *
 * 抽成接口是为了让「检查更新」这件事与 **GitHub** 解耦：版本更新系统以后还要加
 * 别的渠道（自建服务、Gitee 镜像……），而那些渠道的差别只在「怎么把一页 JSON
 * 变成 `List<UpdateRelease>`」。把 GitHub 的 URL 写死在 ViewModel 或管理器里，
 * 加第二个渠道时就得把整条链路复制一遍——连同「哪个版本才算新」这套判断一起，
 * 于是两份判断迟早不一致。
 */
interface UpdateSource {

    /**
     * 列出候选发行版，**不做任何筛选**：渠道过滤与「比当前版本新」都属于
     * [UpdateRules]，那是纯逻辑、有单测的地方。源里再筛一遍就会出现两套规则，
     * 而它们的分歧表现为「某个版本被静默地漏掉了」。
     */
    suspend fun listReleases(): List<UpdateRelease>
}

/**
 * 更新源的连接参数。
 *
 * [token] 是**可选**的：GitHub 未认证时按 IP 限流 60 次/小时，够偶尔手动检查一次；
 * 频繁检查会被限流（403），这时填一个自己的 token 把额度提到 5000 次/小时。
 * 所以它是「可选填」而不是「必须」，界面上也不该写得像必填项。
 */
data class UpdateSourceConfig(
    val repository: String,
    val token: String?,
) {
    companion object {
        /** 本项目自己的仓库。用户没有理由改它，界面上也就没有这一栏。 */
        const val DEFAULT_REPOSITORY = "Aaze-wu/MultiSuperPlayer"
    }
}
