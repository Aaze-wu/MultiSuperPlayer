package com.multisuperplayer.core.data.update

/**
 * 更新通道。
 *
 * 两种而不是三种（没有「只收 beta」这一档）：本项目只发 `alpha` 与正式版，
 * 多出来的那一档会在界面上变成一个永远选不到任何东西的选项。
 */
enum class UpdateChannel {
    /** 只收正式版。装的是正式版的用户默认走这一条。 */
    STABLE,

    /** 正式版 + 预发行版都收。装的是预发行版的用户默认走这一条。 */
    PRERELEASE,
    ;

    /**
     * 这一条发行版是否属于这个通道。
     *
     * `PRERELEASE` 也收正式版，而且是**必须**收：一个装着 `0.7.0-alpha.1` 的用户
     * 如果不能收到 `0.7.0` 正式版，他就永远得手动下载才能退出预览通道——
     * 「不想当小白鼠了」这件事必须有一条出路。
     */
    fun allows(release: UpdateRelease): Boolean = when (this) {
        STABLE -> !release.isPreRelease
        PRERELEASE -> true
    }
}

/**
 * 一次检查的结论。
 *
 * 四态而不是一个布尔：界面要说的话至少有四句不同的，而把它们压成一个
 * 「有没有更新」会丢掉两条最关键的信息——
 * - **还没查过**和**查过且已是最新**必须分开。合成一个的话，用户点进「检查更新」
 *   之前就会看到一句「已是最新版本」，而这句是**没有依据**的。
 * - **用户主动忽略了这一版**和**真的没有新版本**必须分开。合成一个的话，
 *   用户忽略之后会以为自己的忽略没生效（下次进来还写着「有新版本」），
 *   或者会以为已经是最新（于是不会再去手动找）。忽略要能看见、也要能撤销。
 */
sealed interface UpdateAvailability {

    /** 还没查过（或当前版本号读不出来，没有比较的基准）。 */
    data object NotChecked : UpdateAvailability

    /** 查过了，没有比当前更新的。 */
    data object UpToDate : UpdateAvailability

    /** 有新版本。 */
    data class Available(val release: UpdateRelease) : UpdateAvailability

    /**
     * 有更新的版本，但用户选择过忽略它。
     *
     * 说的是**这一条**：等到下一条更新的版本出现，结论会变回 [Available]。
     * 忽略的是版本而不是「永远不再提示」——后者会让用户错过所有后续版本，
     * 而他当时只想跳过这一个。
     */
    data class Ignored(val release: UpdateRelease) : UpdateAvailability
}

object UpdateRules {

    /**
     * 从候选里挑出「该提示的那一条」。
     *
     * 筛选顺序是有意的，每一步都排除掉一种「看起来有更新、实际装不上」的情况：
     * 1. **通道**——正式版用户不该被推预发行版；
     * 2. **比当前新**——同一个版本号重复出现（重新上传过资产）不该被提示；
     * 3. **有 APK**——没有可下载资产的发行版（只写了说明、或者资产名不匹配）
     *    提示了也装不上，那是把用户骗去一个死页面。
     *
     * 取**版本最高**的一条而不是最新发布的那一条：发布顺序和版本号顺序在
     * 补发旧版本时会不一致（给 0.6.5 补一个 alpha.3），这时按发布时间选会把
     * 用户从 0.7.0 劝回 0.6.5。
     *
     * @param current 当前安装的版本。读不出来（构建信息缺失）时传 null ⇒ 结论是
     *   [UpdateAvailability.NotChecked]，界面说「无法确定当前版本」，
     *   而不是拿一个猜的版本号去比。
     * @param ignoredTag [UpdateRelease.tagName]，用户点过「忽略这一版」的那一条。
     */
    fun decide(
        current: UpdateVersion?,
        releases: List<UpdateRelease>,
        channel: UpdateChannel,
        ignoredTag: String?,
    ): UpdateAvailability {
        if (current == null) return UpdateAvailability.NotChecked

        val newest = releases
            .filter { channel.allows(it) }
            .filter { it.isInstallable }
            .filter { it.version > current }
            .maxByOrNull { it.version }
            ?: return UpdateAvailability.UpToDate

        return if (newest.tagName == ignoredTag) {
            UpdateAvailability.Ignored(newest)
        } else {
            UpdateAvailability.Available(newest)
        }
    }

    /**
     * 首次启动（用户没选过通道）时的默认通道。
     *
     * 跟着**当前装的是哪一路**走：装的预发行版就继续收预发行版，否则用户下一次
     * 就再也收不到 alpha 了（而正是他手上这个包装的是 alpha）；装的正式版则
     * 默认只收正式版——把一个正式版用户推去 alpha 是对他信任的滥用。
     */
    fun defaultChannelFor(currentIsPreRelease: Boolean): UpdateChannel =
        if (currentIsPreRelease) UpdateChannel.PRERELEASE else UpdateChannel.STABLE
}
