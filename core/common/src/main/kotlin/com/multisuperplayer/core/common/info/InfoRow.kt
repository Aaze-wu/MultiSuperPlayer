package com.multisuperplayer.core.common.info

import android.content.res.Resources
import com.multisuperplayer.core.common.text.MspText

/**
 * 「关于」页 / 日志抬头里的一行「标签 : 值」。
 *
 * 放在 `core:common` 而不是设置模块，是因为**日志抬头和关于页必须显示同一份设备信息**：
 * 用户在关于页看到「机型：X」，导出的日志抬头里也必须是同一个 X。两处各自拼一遍，
 * 迟早会出现「日志里有而界面上没有」的字段，排障时对不上。
 *
 * 两段都是 [MspText] 而不是 `String`：这样标签能跟着界面语言走，
 * 而拼装逻辑（以及它的单测）不需要 `Resources`。
 */
data class InfoRow(val label: MspText, val value: MspText) {

    /**
     * 铺成一行的纯文本，给日志/导出用。
     *
     * 分隔符只写在这里一处：日志抬头和导出报告用的是同一个函数，
     * 两处各写一遍迟早会分叉成 `设备: X` 和 `设备：X`，
     * 而按 `:` 搜索日志的人就会发现一半的行搜不到。
     */
    fun render(resources: Resources): String =
        label.resolve(resources) + ": " + value.resolve(resources)
}
