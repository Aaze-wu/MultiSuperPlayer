package com.multisuperplayer.core.player

/**
 * 视频画面的原始尺寸（来自媒体文件本身，不是显示区域）。
 *
 * 单独抽出来而不是往 `MspPlaybackState` 里塞三个 `Int`/`Float`，是因为这三者
 * **要么一起有效、要么一起无效**：只拿到宽和高、把像素宽高比漏掉，会让变形
 * 视频（DVD 的 720×576 anamorphic 之类）算出一个错的显示宽高比——而界面上
 * 看不出来「你少乘了一个系数」，只表现为「画面有点扁」。
 *
 * @param widthPx 画面宽度（像素）。
 * @param heightPx 画面高度（像素）。
 * @param pixelWidthHeightRatio 像素宽高比。1.0 = 方形像素（绝大多数文件）。
 */
data class MspVideoSize(
    val widthPx: Int,
    val heightPx: Int,
    val pixelWidthHeightRatio: Float = 1f,
) {
    /**
     * 这一组尺寸能不能拿来算显示大小。
     *
     * 内核在「还没解析出视频轨」时会给 0×0，`pixelWidthHeightRatio` 也可能是 0
     * 或 NaN（畸形文件）。这三种情况都必须在算除法**之前**拦掉：0 宽会算出
     * 除零，NaN 会一路传染到 Compose 的约束里，最后表现为整块画面消失。
     */
    val isValid: Boolean
        get() = widthPx > 0 &&
            heightPx > 0 &&
            pixelWidthHeightRatio > 0f &&
            pixelWidthHeightRatio.isFinite()

    /**
     * 显示宽高比（宽 ÷ 高），**已经把像素宽高比算进去**。
     *
     * 无效尺寸时返回 1（正方形）而不是抛异常或返回 NaN：调用方多在布局阶段，
     * 那里没有任何合理的恢复动作，返回一个「中性的、不会把布局搞崩」的值即可。
     */
    val aspectRatio: Float
        get() = if (isValid) widthPx * pixelWidthHeightRatio / heightPx else 1f

    /** 画面本身是不是竖的（竖向视频在横屏播放器里会两侧留黑边）。 */
    val isPortrait: Boolean get() = aspectRatio < 1f

    companion object {
        /**
         * 「还不知道」。用它而不是 null 的场合：内核侧只有这一个字段，
         * 每多一个 null 就多一处调用方要解包。
         *
         * 界面上两者含义完全一样（还不知道就只能先铺满），所以这里用
         * [isValid] 而不是「是不是 null」来判定。
         */
        val Unknown = MspVideoSize(widthPx = 0, heightPx = 0)
    }
}
