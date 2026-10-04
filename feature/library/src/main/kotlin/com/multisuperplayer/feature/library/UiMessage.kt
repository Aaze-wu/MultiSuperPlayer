package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.text.MspText

/**
 * 一条**一次性**的提示（Snackbar）。
 *
 * [nonce] 看起来多余，实际是必需的：只把文案放进状态时，用户连着两次「加入 N 项」
 * 会得到两个**结构相等**的状态，Compose 的 `LaunchedEffect(text)` 认为 key 没变，
 * 第二次的提示就再也不显示了。所以让每一次事件都带上一个只增不减的序号，
 * 界面拿它当 key。
 *
 * 放在自己的文件里而不是挂在某个 ViewModel 上：媒体库页和浏览页都会发这种提示，
 * 谁「拥有」这个类型都不合适。
 */
data class UiMessage(val text: MspText, val nonce: Long)
