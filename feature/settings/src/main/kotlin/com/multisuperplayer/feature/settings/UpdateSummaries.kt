package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.common.appinfo.AppBuildInfo
import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.data.update.UpdateAvailability
import com.multisuperplayer.core.data.update.UpdateChannel
import com.multisuperplayer.core.data.update.UpdateFailureText

/**
 * 更新相关的「状态 → 文案」映射。
 *
 * 抽成纯函数（而不是写在 Composable 里）的理由和 `SettingsSummaries` 一样：
 * 这些分支全部是「一句话说清现在是什么状态」，写错的表现是**两句不同的话长得一样**
 * 或者**一种状态没有话可说**，而这两种错在界面上都不会报错——只有单测能挡住。
 *
 * 副标题一律是**当前状态**，不是功能说明：「检查更新」下面写「点这里检查新版本」
 * 是一句用户已经知道的话，写「已是最新版本」才回答了他点进来想知道的事。
 */
object UpdateSummaries {

    /** 设置入口页那一行的副标题。 */
    fun entry(buildInfo: AppBuildInfo): MspText =
        MspText.Res(R.string.msp_update_current_version_value, listOf(buildInfo.versionName))

    /**
     * 「现在是什么状态」+ 「我装的是几」，合成检查按钮下面那一行副标题。
     *
     * 两段之间用 `·` 连接而不是分成两行：这一行要回答的是同一个问题
     * （「我该不该按左边那个按钮」），而把它拆成两行之后，用户会先读完第一行
     * 才发现还有一个版本号，再回头把两件事拼起来。
     */
    fun statusLine(availability: UpdateAvailability, versionName: String): MspText = MspText.join(
        separator = MspText.Plain(" · "),
        parts = listOf(
            status(availability),
            MspText.Res(R.string.msp_update_current_version_value, listOf(versionName)),
        ),
    )

    fun status(availability: UpdateAvailability): MspText = when (availability) {
        UpdateAvailability.NotChecked -> MspText.Res(R.string.msp_update_status_not_checked)

        // 「已是最新」与「还没查过」必须分得开：前者是一次真实的确认，
        // 后者什么都不是。合并之后，刚装上、一次网都没连过的应用会显示「已是最新」——
        // 那是在替用户下一个没做过的结论。
        UpdateAvailability.UpToDate -> MspText.Res(R.string.msp_update_status_up_to_date)

        is UpdateAvailability.Available ->
            MspText.Res(R.string.msp_update_status_available, listOf(availability.release.tagName))

        // 被忽略的那一版仍然要说出版本号：只写「已忽略」的话，用户过一阵子
        // 完全想不起来自己忽略的是哪一版，也就没法判断要不要撤销。
        is UpdateAvailability.Ignored ->
            MspText.Res(R.string.msp_update_status_ignored, listOf(availability.release.tagName))
    }

    fun channel(channel: UpdateChannel): MspText = when (channel) {
        UpdateChannel.STABLE -> MspText.Res(R.string.msp_update_channel_stable)
        UpdateChannel.BETA -> MspText.Res(R.string.msp_update_channel_beta)
    }

    fun channelDescription(channel: UpdateChannel): MspText = when (channel) {
        UpdateChannel.STABLE -> MspText.Res(R.string.msp_update_channel_stable_desc)
        UpdateChannel.BETA -> MspText.Res(R.string.msp_update_channel_beta_desc)
    }

    fun token(hasToken: Boolean): MspText = if (hasToken) {
        MspText.Res(R.string.msp_update_token_set)
    } else {
        MspText.Res(R.string.msp_update_token_unset)
    }

    /** 失败原因。**八条各自一句**：分成两句话的每一种情况，下一步动作都不一样。 */
    fun failure(failure: UpdateFailureText): MspText = when (failure) {
        UpdateFailureText.Network -> MspText.Res(R.string.msp_update_failure_network)

        // 限流那句必须给出**两个**出路（等一会儿 / 填令牌）。只写「请稍后重试」的话，
        // 被限流的用户会一直重试，而限流恰恰是「重试解决不了」的那一类。
        UpdateFailureText.RateLimited -> MspText.Res(R.string.msp_update_failure_rate_limited)

        UpdateFailureText.NotFound -> MspText.Res(R.string.msp_update_failure_not_found)
        UpdateFailureText.ServerError -> MspText.Res(R.string.msp_update_failure_server_error)
        UpdateFailureText.NoAsset -> MspText.Res(R.string.msp_update_failure_no_asset)
        UpdateFailureText.DownloadCorrupted -> MspText.Res(R.string.msp_update_failure_corrupted)
        UpdateFailureText.SignatureMismatch -> MspText.Res(R.string.msp_update_failure_signature)
        UpdateFailureText.NoInstaller -> MspText.Res(R.string.msp_update_failure_no_installer)
    }
}
