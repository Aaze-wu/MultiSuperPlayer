package com.multisuperplayer.core.ui.text

import com.multisuperplayer.core.common.text.MspText
import com.multisuperplayer.core.model.MediaEntry
import com.multisuperplayer.core.ui.R

/**
 * 列表和播放页显示的媒体标题。
 *
 * ## 为什么不放在 `core:data`
 *
 * 数据层给不出标题时 [MediaEntry.title] 就是**空串**（见
 * `MediaStoreScanRules.UNKNOWN_TITLE`）。那句「未知标题」是界面文案，
 * 数据层写死一句中文，英文界面里就会冒出来——所以兜底放在这里，
 * 由 [displayTitle] 选择该说哪一句、由调用方在正确的语言下解析。
 *
 * 多个界面（媒体库列表、播放页标题、通知）都要用同一个兜底，写两份
 * 迟早会出现「列表里叫未知标题、通知里叫 (未知)」这种不一致。
 */
fun MediaEntry.displayTitle(): MspText =
    title.takeIf { it.isNotBlank() }?.let(MspText::Plain)
        ?: MspText.Res(R.string.msp_media_unknown_title)
