package com.multisuperplayer.core.data.permissions

import android.Manifest

/**
 * 权限页里的一项。
 *
 * **只有这四项**，不是应用声明过的全部权限。分界线是「用户能不能改」：
 * 能改的才值得列成一行，改不了的（`INTERNET` 之类）列出来只会让人以为点了有用，
 * 于是它们退化成一段折叠说明（见 [PermissionRules.OTHER_PERMISSIONS]）。
 */
enum class PermissionKind {
    /** 媒体库读取：`READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO`（≤32 上是 `READ_EXTERNAL_STORAGE`）。 */
    MEDIA,

    /** 「所有文件访问」`MANAGE_EXTERNAL_STORAGE`：内置文件浏览器能进任意文件夹。 */
    ALL_FILES,

    /** 通知 `POST_NOTIFICATIONS`（33+）。 */
    NOTIFICATION,

    /** 蓝牙 `BLUETOOTH_CONNECT`（31+）。 */
    BLUETOOTH,
}

/**
 * 一项权限当前的状态。
 *
 * 这是**六态而不是三态**，因为「怎么把用户送到能改它的地方」要靠它区分：
 * - [NOT_ASKED] / [ASK_AGAIN] / [PARTIAL] → 还能就地弹系统授权框；
 * - [GO_TO_SETTINGS] → 系统不会再弹框了，只能去应用详情页开；
 * - [GRANTED] → 已经允许，能做的只有「去关掉」；
 * - [UNSUPPORTED] → 这个系统版本根本没有这一项，右侧不该有任何动作。
 *
 * 少了 [ASK_AGAIN] 和 [GO_TO_SETTINGS] 的区分，界面就只能在这两种情况下写同一句话，
 * 而它们对应的下一步**完全不同**：一个是「再点一次」，一个是「去别的地方」。
 */
enum class PermissionState {
    GRANTED,

    /** 只允许了一部分（媒体那一项：音频允许了、视频没允许，或只拿到用户勾选的视频）。 */
    PARTIAL,

    /** 从来没问过系统。 */
    NOT_ASKED,

    /** 问过、被拒了，但再问一次系统还愿意弹框。 */
    ASK_AGAIN,

    /** 问过、被拒了，而且系统决定不再弹框——只能去应用详情页手动开。 */
    GO_TO_SETTINGS,

    /** 这个系统版本没有这一项权限，永远不会有授权框。 */
    UNSUPPORTED,
}

/**
 * [PermissionRules.stateOf] 的输入：三项原始事实。
 *
 * 拆成一个数据类而不是三个参数，是为了让调用方（真机上的 [AppPermissions] 与单测）
 * 用同一份形状说话——单测里没有 `Activity`，`rationale` 只能自己编，那就必须能一眼看到
 * 它参与了判断。
 */
data class PermissionFacts(
    val granted: Boolean,
    val rationale: Boolean,
    val asked: Boolean,
)

/**
 * 媒体那一项的输入：比 [PermissionFacts] 多两条。
 *
 * [partialVisual] 必须由调用方（`MediaStoreScanRules.isPartialVisualAccess`）算好再传进来，
 * 因为「视频允许了，但只是用户勾选的那几个」在 [videoGranted] 里看起来跟「全都允许了」
 * 一模一样（两个都靠 `true` 表达）。少了这一条，Android 14 上选「仅选择部分」的用户
 * 会看到「已允许」，然后媒体库里只有他挑的那几个视频。
 */
data class MediaPermissionFacts(
    val audioGranted: Boolean,
    val videoGranted: Boolean,
    val partialVisual: Boolean,
    val rationale: Boolean,
    val asked: Boolean,
)

/** 权限页的四行状态，一次算好。 */
data class PermissionSnapshot(
    val media: PermissionState = PermissionState.NOT_ASKED,
    val allFiles: PermissionState = PermissionState.NOT_ASKED,
    val notification: PermissionState = PermissionState.NOT_ASKED,
    val bluetooth: PermissionState = PermissionState.NOT_ASKED,
) {

    operator fun get(kind: PermissionKind): PermissionState = when (kind) {
        PermissionKind.MEDIA -> media
        PermissionKind.ALL_FILES -> allFiles
        PermissionKind.NOTIFICATION -> notification
        PermissionKind.BLUETOOTH -> bluetooth
    }
}

/**
 * 权限的状态判定。**全是纯函数**，`sdkInt` 与三项事实都当参数传进来。
 *
 * 为什么不能直接读 `Build.VERSION.SDK_INT` 或 `ContextCompat.checkSelfPermission`：
 * JVM 单测里 `SDK_INT` 恒为 0、也没有真的 `Context`。而这里要钉住的恰恰是
 * 「哪种情况说哪句话、给哪个动作」——那正是只在**老系统**或**被拒绝过**的设备上
 * 才暴露的部分，真机上很难穷举。
 */
object PermissionRules {

    /**
     * 由三项事实定状态。
     *
     * 顺序不能换：
     * - `granted` 最先——已经允许了，前面问过什么都没关系；
     * - `rationale` 比 `asked` 优先——它说的是「系统还愿意弹框」，[ASK_AGAIN] 与
     *   [GO_TO_SETTINGS] 的**唯一**区别就在这里；
     * - `asked` 兜底——没允许、不能弹框、又问过 ⇒ 只剩「去设置」这条路。
     */
    fun stateOf(facts: PermissionFacts): PermissionState = when {
        facts.granted -> PermissionState.GRANTED
        facts.rationale -> PermissionState.ASK_AGAIN
        facts.asked -> PermissionState.GO_TO_SETTINGS
        else -> PermissionState.NOT_ASKED
    }

    /**
     * 媒体那一项。
     *
     * 一条都没允许时，退化成 [stateOf] 的普通判定（因为「拒绝」这件事要区分
     * 还能不能再问）。只要有允许，就不再看 `rationale`：这时的问题已经不是
     * 「怎么再问一次」，而是「缺的那一半怎么办」——而系统对「已允许的权限」
     * 永远答 `rationale = false`，照 [stateOf] 走会给出 [GO_TO_SETTINGS]，
     * 于是「音频允许、视频没允许」的用户看到的是「已在系统里拒绝」，
     * 而其实只要再申请一次就能补上视频。
     */
    fun mediaState(facts: MediaPermissionFacts): PermissionState = when {
        !facts.audioGranted && !facts.videoGranted -> stateOf(
            PermissionFacts(
                granted = false,
                rationale = facts.rationale,
                asked = facts.asked,
            ),
        )

        facts.audioGranted && facts.videoGranted && !facts.partialVisual -> PermissionState.GRANTED

        else -> PermissionState.PARTIAL
    }

    /**
     * 这一项现在能不能**就地弹系统授权框**。
     *
     * 判的就是 [PermissionState] 文档里那一句「哪几态还弹得出来」：只有
     * [PermissionState.NOT_ASKED] / [PermissionState.ASK_AGAIN] /
     * [PermissionState.PARTIAL] 会返回 `true`。
     *
     * [PermissionState.GO_TO_SETTINGS] 是 `false`，否则界面会给出一个**按了没反应**的按钮：
     * 这一态的含义正是「上次问完，系统已经决定不再弹框」，此时再调一次
     * `requestPermissions` 只会被静默拒绝——用户点了「申请」，屏幕毫无变化，
     * 而同一行左边还写着「已被拒绝」。矛盾的两个信号摆在一起，用户只能怀疑应用坏了。
     * 送他去应用详情页则**永远不会错**：那里既有开关（能开就能开），
     * 也能看出这一项是不是真的开不了。
     *
     * 「所有文件访问」也永远是 `false`：它是特权权限，系统不给应用弹框这一条路，
     * 只能把用户送到那个专属设置页。这一条如果写反，用户点「申请」同样什么都不会发生
     * （系统对 `MANAGE_EXTERNAL_STORAGE` 的 `requestPermissions` 直接静默拒绝）。
     */
    fun canAskInPlace(kind: PermissionKind, state: PermissionState): Boolean = when (state) {
        // 这三态的共同点：系统还愿意为此弹框（PARTIAL 也是——缺的那一半照样能申请）。
        PermissionState.NOT_ASKED,
        PermissionState.ASK_AGAIN,
        PermissionState.PARTIAL,
        -> kind != PermissionKind.ALL_FILES

        // GRANTED：已经允许了，没有可申请的东西。
        // GO_TO_SETTINGS：系统不再弹框，只能去设置页。
        // UNSUPPORTED：这个系统版本根本没有这一项。
        PermissionState.GRANTED,
        PermissionState.GO_TO_SETTINGS,
        PermissionState.UNSUPPORTED,
        -> false
    }

    /**
     * 「安装时就已授予、在权限页里没有单独动作」的那几条。
     *
     * 顺序 = 权限页折叠区里列出来的顺序。**必须和 `AndroidManifest.xml` 对得上**
     * （`PermissionManifestCoverageTest` 会盯着）：漏一条，权限页就少说了一个
     * 应用实际拥有的权限；多一条，就会白列一个并不存在的权限。
     *
     * [Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] 是个例外，值得单独说：
     * 它其实**有**动作可做（申请加入电池优化白名单），但那个动作的后果是「长时间
     * 后台播放不被系统限制」，属于「后台保活」那一页。权限页只承认「应用拥有这条权限」，
     * 动作不在这里给——同一件事开两个入口之后，两边读到的状态会各自刷新，早晚不一致。
     */
    val OTHER_PERMISSIONS: List<String> = listOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
        Manifest.permission.WAKE_LOCK,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
    )

    /**
     * 清单里声明了、但**故意不单独列**成一项的权限。
     *
     * - `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`）是媒体读取在 ≤32 上的名字，
     *   它不是「另一项权限」，而是同一项权限在旧系统上的形态；
     * - `READ_MEDIA_VISUAL_USER_SELECTED`（34+）永远和 `READ_MEDIA_VIDEO` 一起申请，
     *   它是那次申请的一个结果分支，单独列只会让用户以为有两个视频权限。
     *
     * 这是一份**解释**，不是一个垃圾桶：往里加东西时应该问「它是不是某一项的旧名字
     * 或结果分支」，答不上来就说明它该是权限页上的一行。
     */
    val NOT_LISTED_PERMISSIONS: List<String> = listOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    /**
     * 权限页上四行加起来覆盖到的权限名（**与系统版本无关**的全集）。
     *
     * 和 [NOT_LISTED_PERMISSIONS] 一起，正好应当等于清单里声明的全部权限。
     * 这是 `PermissionManifestCoverageTest` 的对照表——新增一条权限声明时，
     * 要么把它放进这里（= 页面上会多一行），要么放进 [NOT_LISTED_PERMISSIONS]（= 说明理由）。
     */
    val LISTED_PERMISSIONS: List<String> = listOf(
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
}
