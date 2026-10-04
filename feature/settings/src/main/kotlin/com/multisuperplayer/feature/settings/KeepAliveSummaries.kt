package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.data.power.DeviceVendor
import com.multisuperplayer.core.data.power.KeepAliveState

/**
 * 「后台保活」页上每一行的文案，以及设置入口页那一行的副标题。
 *
 * 和 [SettingsSummaries] / [PermissionSummaries] 同一个理由抽成纯函数：
 * 这一页要说清两件互相独立的事（**电池优化白名单**和**厂商后台管理**），
 * 而「哪一档说哪句」正好是最容易写错、又最难在真机上复查的地方——
 * 逐条钉成单测最便宜。状态本身由 [com.multisuperplayer.core.data.power.KeepAliveRules] 判定。
 *
 * 两处刻意的用词：
 * - **开关标题叫「后台不受限制」**，不叫「后台保活」。后者是需求里的说法，
 *   但它在系统设置里没有对应物：用户拨完开关弹出来的是一个写着
 *   「允许后台不受限制地运行吗」的框，两边用词不一致他会以为拨错了开关。
 * - **不写「关闭开关」**，写「拨回去会带你去系统的电池设置」——
 *   那是这个动作真实会发生的事，而「关闭」听起来像应用自己能关掉（它不能）。
 */
internal object KeepAliveSummaries {

    /**
     * 设置入口页那一行的副标题。
     *
     * 只说白名单那一档，不提厂商：入口页一行放不下两套机制，
     * 而没放开白名单是**任何机型**上都成立的那半个问题。
     */
    fun entry(state: KeepAliveState): MspText = when (state) {
        KeepAliveState.UNRESTRICTED -> MspText.Res(R.string.msp_keep_alive_entry_on)
        KeepAliveState.RESTRICTED -> MspText.Res(R.string.msp_keep_alive_entry_off)
    }

    /** 主开关下面那句：现在在哪一档，以及这一档下系统会怎么对待它。 */
    fun switchSubtitle(state: KeepAliveState): MspText = when (state) {
        KeepAliveState.UNRESTRICTED -> MspText.Res(R.string.msp_keep_alive_switch_on)
        KeepAliveState.RESTRICTED -> MspText.Res(R.string.msp_keep_alive_switch_off)
    }

    /**
     * 厂商那一行的标题。
     *
     * 认不出厂商时（[DeviceVendor.OTHER]）换成「系统设置」，而不是随便挑一家的名字：
     * 认错厂商会跳到一个不存在的页面，用户看到的是「按钮没反应」，
     * 而真因（这台机器不在已知名单里）藏在一次静默的降级里。
     */
    fun vendorTitle(vendor: DeviceVendor): MspText = when (vendor) {
        DeviceVendor.XIAOMI -> MspText.Res(R.string.msp_keep_alive_vendor_xiaomi)
        DeviceVendor.HUAWEI -> MspText.Res(R.string.msp_keep_alive_vendor_huawei)
        DeviceVendor.HONOR -> MspText.Res(R.string.msp_keep_alive_vendor_honor)
        DeviceVendor.OPPO -> MspText.Res(R.string.msp_keep_alive_vendor_oppo)
        DeviceVendor.VIVO -> MspText.Res(R.string.msp_keep_alive_vendor_vivo)
        DeviceVendor.MEIZU -> MspText.Res(R.string.msp_keep_alive_vendor_meizu)
        DeviceVendor.SAMSUNG -> MspText.Res(R.string.msp_keep_alive_vendor_samsung)
        DeviceVendor.ONE_PLUS -> MspText.Res(R.string.msp_keep_alive_vendor_one_plus)
        DeviceVendor.OTHER -> MspText.Res(R.string.msp_keep_alive_vendor_other)
    }

    /**
     * 厂商那一行的副标题。
     *
     * 这两句话**必须分开**（和 `PermissionSummaries.state` 的三态是同一个理由）：
     * 有厂商页时要说的是「这一套不看上面那个白名单」（否则用户以为加了白名单就完事），
     * 没有厂商页时要说的是「会退到应用信息页」（否则用户不知道自己会被带到哪儿）。
     * 合成一句就没法同时说清这两件事。
     */
    fun vendorSubtitle(vendor: DeviceVendor): MspText = when (vendor) {
        DeviceVendor.OTHER -> MspText.Res(R.string.msp_keep_alive_vendor_note_fallback)
        else -> MspText.Res(R.string.msp_keep_alive_vendor_note_page)
    }
}
