package com.multisuperplayer.feature.player

import com.multisuperplayer.core.model.MediaKind
import com.multisuperplayer.core.player.MspVideoSize

/**
 * 画中画窗口的两条纯计算规则：**窗口该是什么形状**、**该不该给入口**。
 *
 * 单独抽出来而不是直接写在 `PlayerPipController` 里，是因为那两件事都是纯函数，
 * 而 `PictureInPictureParams` / `Rational` 都是 Android 框架类——在纯 JVM 单测里
 * 碰不得（`unitTests.isReturnDefaultValues = true`，构造 `Rational` 会直接抛
 * 「not mocked」）。把算数部分留在这里，真机上试不出来的边界（0×0、NaN、超宽）
 * 就都能跑单测。
 */
internal object PlayerPipRules {

    /**
     * 尺寸还没解析出来时用的兜底宽高比。
     *
     * 用 16:9 而不是 1:1：[MspVideoSize] 对无效尺寸返回的 `aspectRatio` 是 1，
     * 那会让画中画窗口变成一个正方形——绝大多数片源是 16:9，用户看到的是一个
     * 四周大黑边的方窗口，而「明明还没解析出画面尺寸」这个原因在界面上看不出来。
     */
    const val DEFAULT_ASPECT_RATIO: Float = 16f / 9f

    /**
     * 宽高比下限。`1 / 2.39`，也就是 2.39:1 的宽银幕**竖过来**的样子。
     *
     * 取 2.39 是因为它是常见片源里最极端的宽画幅（变形宽银幕），上下留白已经
     * 够多了；比这更极端的比值多半来自畸形元数据，夹住比画出来强。
     */
    const val MIN_ASPECT_RATIO: Float = 0.418410f

    /** 宽高比上限。2.39:1，理由同 [MIN_ASPECT_RATIO]。 */
    const val MAX_ASPECT_RATIO: Float = 2.39f

    /**
     * 画中画窗口用哪个宽高比。
     *
     * @param video 片源尺寸。无效时（还没解析出视频轨）取 [DEFAULT_ASPECT_RATIO]；
     *   有效时取**显示**宽高比（已经把像素宽高比算进去，见 [MspVideoSize.aspectRatio]），
     *   否则 DVD 那种变形片源会得到一个被压扁的窗口。
     */
    fun aspectRatio(video: MspVideoSize): Float =
        if (video.isValid) clampAspectRatio(video.aspectRatio) else DEFAULT_ASPECT_RATIO

    /**
     * 把一个宽高比夹进系统接受的范围。
     *
     * `NaN` 和 `Infinity` 必须在这里拦掉：系统侧 `setAspectRatio` 拿到它们会抛
     * `IllegalArgumentException`，而那时调用点在一个 `LaunchedEffect` 里，
     * 表现是「播放页一进去就崩」。
     *
     * 注意 `coerceIn` 在这里不够用——`Float.coerceIn` 对 NaN 返回 NaN，
     * 不会夹到区间里。
     */
    fun clampAspectRatio(ratio: Float): Float = when {
        !ratio.isFinite() || ratio <= 0f -> DEFAULT_ASPECT_RATIO
        ratio < MIN_ASPECT_RATIO -> MIN_ASPECT_RATIO
        ratio > MAX_ASPECT_RATIO -> MAX_ASPECT_RATIO
        else -> ratio
    }

    /**
     * 要不要在控制条上给一个「画中画」入口。
     *
     * 两个条件都要：**系统支持**（有些设备/ROM 没有这个特性），以及**播的是视频**。
     * 音频绝不能给入口——进了画中画就会得到一个只有黑底、没有画面的小窗口，
     * 而系统侧本身是允许这么做的（它只要求 Activity 声明支持），拦不住的。
     */
    fun shouldOfferEntry(kind: MediaKind?, supported: Boolean): Boolean =
        supported && kind == MediaKind.VIDEO
}
