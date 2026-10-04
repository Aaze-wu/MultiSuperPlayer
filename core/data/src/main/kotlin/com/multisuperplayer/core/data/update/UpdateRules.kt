package com.multisuperplayer.core.data.update

import com.multisuperplayer.core.common.appinfo.AppChannelNames

/**
 * 更新通道。
 *
 * 两档，对应项目实际会**对外**走的两条线：
 *
 * | 版本号 | 是什么 | [STABLE] | [BETA] |
 * | --- | --- | --- | --- |
 * | `X.Y.Z` | 正式版 | ✅ | ✅ |
 * | `X.Y.Z-beta.N` | 公开测试版 | ❌ | ✅ |
 * | `X.Y.Z-alpha.N` | 内部构建 | ❌ | ❌ |
 *
 * **`alpha` 不是「第三档」，而是根本不进公开发布的东西**：它只在本机构建、内部
 * 试用，不上传 Releases。那为什么两条通道都还要专门排除它？因为「不上传」是一个
 * 人的约定，而这里是一条会真的执行的规则——万一哪天手滑把 alpha 传上去了，或者
 * 更新源上还留着历史遗留的 alpha，选了「测试版」的用户就会被劝去装一个我们自己
 * 都没打算公开的包。约定靠不住，代码得靠得住。
 *
 * 判据看 **tag**（[UpdateVersion.preReleaseKind]）而不是 GitHub 的 `prerelease`
 * 标记：标记是发布时手填的开关，tag 是构建流程写进包里的东西，前者可以忘、后者不会。
 *
 * 那 [STABLE] 为什么还要多看眼标记？因为两个方向的代价不对称：把一个正式版误勾成
 * 预发行，代价只是「晚一点再推给你」；把测试版误当正式版推出去，代价是全体稳定用户
 * 一起升到一个测试包上。所以往安全的那边走，宁可保守。
 */
enum class UpdateChannel {
    /** 只收正式版。装的是正式版的用户默认走这一条。 */
    STABLE,

    /** 正式版 + 公开测试版。装了 beta 的用户默认走这一条。 */
    BETA,
    ;

    /**
     * 这一条发行版是否属于这个通道。
     *
     * [BETA] 也收正式版，而且是**必须**收：一个装着 `0.7.0-alpha.1` 的用户
     * 如果不能收到 `0.7.0` 正式版，他就永远得手动下载才能退出测试通道——
     * 「不想当小白鼠了」这件事必须有一条出路。
     *
     * [STABLE] 多判一个 `isPreRelease`：`isPreRelease` 是 GitHub 的发布开关，
     * `version.isPreRelease` 是 tag 写的。两个都说不是预发行，才当正式版推给稳定用户。
     */
    fun allows(release: UpdateRelease): Boolean = when (this) {
        STABLE -> !release.version.isPreRelease && !release.isPreRelease
        BETA -> !release.version.isPreRelease ||
            release.version.preReleaseKind == AppChannelNames.BETA
    }
}

/**
 * 这一次检查是**谁**发起的。
 *
 * 只有两档，而第二档刻意涵盖两种场景（**启动时一次 + 进更新页一次**）：
 * 界面上只有一个「自动检查更新」开关，两处检查受它管、也共用同一段节流窗口。
 * 如果给两处各留一档，就会出现「开关关掉之后启动不查、进页面还查」这种
 * 只有读代码才看得出来、界面上完全说不通的分裂。
 *
 * 那为什么不用一个 `manual: Boolean`：`false` 只说得清「不是用户点的」，
 * 而这两处对**失败**的处理正好相反（启动检查失败要完全静默、进页面那次要把
 * 失败写在页面上），调用点读一个布尔值看不出自己属于哪一边。
 */
enum class UpdateCheckTrigger {
    /** 用户亲手点的「检查更新」。跳过节流——点了就得真的去问。 */
    MANUAL,

    /**
     * 自动检查：启动时一次、进更新页一次。
     *
     * 受「自动检查更新」开关与 [UpdateRules.AUTO_CHECK_INTERVAL_MS] 限制。
     */
    AUTO,
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
     * 自动检查的最小间隔：12 小时。
     *
     * 为什么是「顺手查」而不是「后台定时查」：后台定时要一个常驻的调度器和一个
     * 长命的状态容器，而那两样东西的代价（多一个进程级的生命周期、多一个
     * 「这份状态是谁在管」的问题）换来的只是「用户会发现新版早了几个小时」。
     * 12 小时对应「一天里最多两次」，而 GitHub 对未认证请求的限制是每小时 60 次
     * ——量级上完全够用，也远不至于被当成滥用。
     *
     * **启动检查和进页面检查共用这一个窗口**（见 [skipsAutoCheck]）：一次启动就是
     * 一次检查，紧接着进更新页不该再问同一个问题。
     */
    const val AUTO_CHECK_INTERVAL_MS: Long = 12L * 60 * 60 * 1000

    /**
     * 这一次自动检查该不该**直接跳过**（跳过 = 什么都不做，见 `UpdateManager.check`）。
     *
     * 两条闸门，顺序不能换：
     * 1. **开关**——用户在设置里关掉「自动检查更新」之后，启动和进更新页都不该再问。
     *    这一条以前**根本没人读**（开关写进 DataStore，之后没有任何地方看它，
     *    是个纯装饰品）：界面上关掉它，启动时照样请求；
     * 2. **节流**——[AUTO_CHECK_INTERVAL_MS] 之内不重复问。
     *
     * [MANUAL][UpdateCheckTrigger.MANUAL] 一律不跳：用户点了「检查」就得真的去问，
     * 哪怕他十秒前刚点过一次。
     *
     * 单独提成一个纯函数，是因为它有两个必须钉住的反例（开关关掉、窗口没到），
     * 而它们很容易被「顺手把 `markChecked` 也记了」这类改动悄悄改掉——
     * 那会让「被跳过的检查」占用掉 12 小时的窗口，用户关一下开关再打开，
     * 第一次启动检查就没了。
     */
    fun skipsAutoCheck(
        trigger: UpdateCheckTrigger,
        autoCheck: Boolean,
        lastCheckAtEpochMs: Long?,
        atEpochMs: Long,
    ): Boolean {
        if (trigger == UpdateCheckTrigger.MANUAL) return false
        if (!autoCheck) return true
        val last = lastCheckAtEpochMs ?: return false
        return atEpochMs - last < AUTO_CHECK_INTERVAL_MS
    }

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
     * 就再也收不到 beta 了（而正是他手上这个包装的就是 beta）；装的正式版则
     * 默认只收正式版——把一个稳定用户推去测试版是对他信任的滥用。
     *
     * 参数说的是「预发行」而不是「beta」：内部 alpha 构建同样落进 [UpdateChannel.BETA]，
     * 因为 alpha 不会再发下一版了，那个人要往下走只能走公开的测试版或正式版。
     */
    fun defaultChannelFor(currentIsPreRelease: Boolean): UpdateChannel =
        if (currentIsPreRelease) UpdateChannel.BETA else UpdateChannel.STABLE
}
