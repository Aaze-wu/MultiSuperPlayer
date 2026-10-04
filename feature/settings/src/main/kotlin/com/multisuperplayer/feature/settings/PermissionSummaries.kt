package com.multisuperplayer.feature.settings

import android.Manifest
import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.permissions.PermissionKind
import com.multisuperplayer.core.data.permissions.PermissionRules
import com.multisuperplayer.core.data.permissions.PermissionSnapshot
import com.multisuperplayer.core.data.permissions.PermissionState

/**
 * 权限页上每一行的文案，以及设置入口页那一行的副标题。
 *
 * 和 [SettingsSummaries] 同一个理由抽成纯函数：这一页的全部价值就是「一眼看出这一项
 * 现在是什么状态、下一步该点哪里」，所以「哪种状态说哪句话」必须能被 JVM 单测钉住。
 * 状态本身由 [PermissionRules] 判定（那边也是纯函数），这里只管**把状态说成人话**。
 *
 * 两处刻意的分工：
 * - **动作词只有两个**（[R.string.msp_permissions_action_request] /
 *   [R.string.msp_permissions_action_settings]）。「点下去会发生什么」由
 *   [PermissionRules.canAskInPlace] 决定，而不是每一行各写一套——同一个动作
 *   在四行里有四种说法，用户就得逐个猜哪个是弹框、哪个是跳页。
 * - **状态词只有一套**，唯一的例外是「所有文件访问」：它沿用设置页原来的三态文案，
 *   那三句已经写进 README，换掉会让文档和界面说的不是一回事。
 */
internal object PermissionSummaries {

    /**
     * 权限页上会列出来的四条，顺序即显示顺序。
     *
     * 从 [PermissionKind] 的声明顺序取，而不是在这里再排一遍：两处各写一份，
     * 加第五项时必然漏掉一处（而那时界面是安静的，不会报错）。
     */
    val changeable: List<PermissionKind> = PermissionKind.entries.toList()

    /** 折叠区里列的那些「安装即授予」的权限，顺序也来自 [PermissionRules]。 */
    val otherPermissions: List<String> = PermissionRules.OTHER_PERMISSIONS

    fun title(kind: PermissionKind): MspText = when (kind) {
        PermissionKind.MEDIA -> MspText.Res(R.string.msp_permissions_media)
        // 这一项的标题沿用设置页原来那一行（「文件访问」）：它同时出现在 README
        // 的设计说明里，换名字会让文档与界面各说一套。
        PermissionKind.ALL_FILES -> MspText.Res(R.string.msp_settings_file_access)
        PermissionKind.NOTIFICATION -> MspText.Res(R.string.msp_permissions_notification)
        PermissionKind.BLUETOOTH -> MspText.Res(R.string.msp_permissions_bluetooth)
    }

    /**
     * 这一项「是干什么用的」。放在标题右边的问号里，不占副标题的位置
     * （理由见 `SettingHelpIcon` 的注释）。
     */
    fun description(kind: PermissionKind): MspText = when (kind) {
        PermissionKind.MEDIA -> MspText.Res(R.string.msp_permissions_media_desc)
        PermissionKind.ALL_FILES -> MspText.Res(R.string.msp_permissions_all_files_desc)
        PermissionKind.NOTIFICATION -> MspText.Res(R.string.msp_permissions_notification_desc)
        PermissionKind.BLUETOOTH -> MspText.Res(R.string.msp_permissions_bluetooth_desc)
    }

    /**
     * 现在的状态。
     *
     * [PermissionState.PARTIAL] 只有媒体那一项会出现（「允许了音频、没允许视频」或
     * Android 14 上「只允许了勾选的那几个视频」），它必须和 [PermissionState.GRANTED]
     * 分开说：说成「已允许」的用户会以为自己音乐视频都能看了，然后发现视频列表是空的。
     */
    fun state(kind: PermissionKind, state: PermissionState): MspText {
        if (kind == PermissionKind.ALL_FILES) {
            return SettingsSummaries.fileAccess(
                supported = state != PermissionState.UNSUPPORTED,
                granted = state == PermissionState.GRANTED,
            )
        }
        return when (state) {
            PermissionState.GRANTED -> MspText.Res(R.string.msp_permissions_state_granted)
            PermissionState.PARTIAL -> MspText.Res(R.string.msp_permissions_state_partial)
            PermissionState.NOT_ASKED -> MspText.Res(R.string.msp_permissions_state_not_asked)
            PermissionState.ASK_AGAIN -> MspText.Res(R.string.msp_permissions_state_ask_again)
            PermissionState.GO_TO_SETTINGS -> MspText.Res(R.string.msp_permissions_state_denied)
            PermissionState.UNSUPPORTED -> MspText.Res(R.string.msp_permissions_state_unsupported)
        }
    }

    /**
     * 右侧那个按钮上该写什么；`null` = 这一行不该有按钮。
     *
     * 「本来就不存在」的一项（老的 Android 上没有「所有文件访问」、12 以下没有蓝牙权限）
     * 给 `null` 而不是给一个灰掉的「申请」：灰按钮仍然在说「这里有个动作，只是现在不行」，
     * 而事实是这个系统里**永远**不会有。
     */
    fun action(kind: PermissionKind, state: PermissionState): MspText? = when {
        state == PermissionState.UNSUPPORTED -> null
        PermissionRules.canAskInPlace(kind, state) -> MspText.Res(R.string.msp_permissions_action_request)
        else -> MspText.Res(R.string.msp_permissions_action_settings)
    }

    /**
     * 设置入口页那一行的副标题。
     *
     * **只看媒体那一项**，不是四项的汇总。理由：
     * - 媒体读取没允许时媒体库就是空的，用户会以为扫描坏了——这是四项里唯一
     *   「不处理就会误解」的一项；
     * - 「所有文件访问」「通知」「蓝牙」都是**可选能力，没允许是默认状态**。
     *   把它们汇总进来，一行「4 项里有 3 项未允许」会让每个用户都以为自己出了问题，
     *   而进去看三项都是「未开启」的默认值。
     *
     * 换句话说：副标题回答的是「要不要进去处理一下」，而不是「这里一共几项」。
     */
    fun entry(snapshot: PermissionSnapshot): MspText = when (snapshot[PermissionKind.MEDIA]) {
        PermissionState.PARTIAL -> MspText.Res(R.string.msp_settings_summary_permissions_partial)
        PermissionState.GRANTED -> MspText.Res(R.string.msp_settings_summary_permissions_ok)
        // NOT_ASKED / ASK_AGAIN / GO_TO_SETTINGS / UNSUPPORTED（媒体那一项不可能不支持，
        // 但真出现了也不该显示成「已允许」）都归到「没允许」这一句。
        else -> MspText.Res(R.string.msp_settings_summary_permissions_missing)
    }

    /**
     * 折叠区里一条权限的名字。
     *
     * 认不出来的权限名原样显示（[MspText.Plain]）而不是报错：这一份清单是从
     * `AndroidManifest.xml` 抄过来的，将来有人加权限而忘了加标签时，界面会露出
     * `android.permission.XXX` —— 难看，但**看得见**。显示成空串或者崩掉，
     * 就会变成「折叠区少了一行」这种没人会发现的错。
     */
    fun otherLabel(permission: String): MspText = when (permission) {
        Manifest.permission.INTERNET -> MspText.Res(R.string.msp_permissions_other_internet)
        Manifest.permission.ACCESS_NETWORK_STATE ->
            MspText.Res(R.string.msp_permissions_other_network_state)

        Manifest.permission.FOREGROUND_SERVICE ->
            MspText.Res(R.string.msp_permissions_other_foreground_service)

        Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK ->
            MspText.Res(R.string.msp_permissions_other_foreground_service_media_playback)

        Manifest.permission.WAKE_LOCK -> MspText.Res(R.string.msp_permissions_other_wake_lock)
        Manifest.permission.MODIFY_AUDIO_SETTINGS ->
            MspText.Res(R.string.msp_permissions_other_modify_audio_settings)

        // 标签用系统设置里那一项的名字（「忽略电池优化」）：用户要对照的是那个页面，
        // 这里换个说法（「后台不受限」）他就找不到该关哪一条。
        Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS ->
            MspText.Res(R.string.msp_permissions_other_ignore_battery_optimizations)

        else -> MspText.Plain(permission)
    }
}
