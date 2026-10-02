package com.multisuperplayer.core.common.info

/**
 * 「关于」页 / 日志抬头里的一行「标签 : 值」。
 *
 * 放在 `core:common` 而不是设置模块，是因为**日志抬头和关于页必须显示同一份设备信息**：
 * 用户在关于页看到「机型：X」，导出的日志抬头里也必须是同一个 X。两处各自拼一遍，
 * 迟早会出现「日志里有而界面上没有」的字段，排障时对不上。
 */
data class InfoRow(val label: String, val value: String)
