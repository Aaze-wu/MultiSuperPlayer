package com.multisuperplayer.core.common.device

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.multisuperplayer.core.common.R
import com.multisuperplayer.core.common.info.InfoRow
import com.multisuperplayer.core.model.text.MspText
import java.util.Locale

/**
 * 一台设备的「身份快照」，全是原始值，便于单测直接构造。
 *
 * 这些字段就是排障时最常问的东西：机型决定了厂商 ROM 的行为差异（后台限制、
 * 解码器实现），ABI 决定了本安装包里那套 FFmpeg 原生库能不能加载
 * （本项目只打了 `arm64-v8a` + `x86_64`），系统版本决定了走哪条兼容分支。
 */
data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val languageTag: String,
) {

    /** 机型文本。很多机型的 `MODEL` 里已经带了品牌（如 `Pixel 7`、`M2101K9C`），避免出现「小米 小米 14」。 */
    fun deviceText(): MspText {
        val brand = manufacturer.trim()
        val name = model.trim()
        return when {
            name.isEmpty() -> if (brand.isEmpty()) MspText.unknown() else MspText.Plain(brand)
            brand.isEmpty() -> MspText.Plain(name)
            name.lowercase(Locale.ROOT).startsWith(brand.lowercase(Locale.ROOT)) -> MspText.Plain(name)
            else -> MspText.Res(R.string.msp_device_brand_model, brand, name)
        }
    }

    fun androidText(): MspText =
        if (androidRelease.isBlank()) {
            MspText.Res(R.string.msp_api_only, sdkInt)
        } else {
            MspText.Res(R.string.msp_android_with_api, androidRelease.trim(), sdkInt)
        }

    fun abiText(): MspText =
        if (abis.isEmpty()) MspText.unknown() else MspText.Plain(abis.joinToString(", "))

    /**
     * 屏幕文本 `1080×2400 @420dpi`。
     *
     * 用全角乘号 `×` 而不是 `x`：等宽字体下 `x` 会和数字糊在一起，
     * 而且项目其它地方（日志、中文文案）都是全角。乘号本身也在资源里了，
     * 万一某种语言想写成 `1080 × 2400`，改文案即可。
     */
    fun screenText(): MspText {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return MspText.unknown()
        return if (densityDpi > 0) {
            MspText.Res(R.string.msp_screen_size, screenWidthPx, screenHeightPx, densityDpi)
        } else {
            MspText.Res(R.string.msp_screen_size_plain, screenWidthPx, screenHeightPx)
        }
    }

    /**
     * 关于页与日志抬头**共用**的行。
     *
     * 顺序有意为之：机型 → 系统 → 架构 → 屏幕 → 语言。
     * 前三个是真正用来定位问题的，屏幕和语言属于「顺手记一下」。
     */
    fun rows(): List<InfoRow> = listOf(
        InfoRow(MspText.Res(R.string.msp_row_device), deviceText()),
        InfoRow(MspText.Res(R.string.msp_row_system), androidText()),
        InfoRow(MspText.Res(R.string.msp_row_abi), abiText()),
        InfoRow(MspText.Res(R.string.msp_row_screen), screenText()),
        InfoRow(MspText.Res(R.string.msp_row_language), MspText.plainOrUnknown(languageTag)),
    )
}

/** 从系统里读一次设备信息。**只在需要时调用**（启动路径上不要调，见 [DeviceInfo.snapshot] 的注释）。 */
object DeviceInfo {

    /**
     * 采集一次。
     *
     * 会读 `WindowManager` 与 `Resources`，属于有 IPC/锁的调用，**不要放在冷启动关键路径**上
     * ——关于页只在用户点进去时采一次即可。
     */
    fun snapshot(context: Context): DeviceSnapshot {
        val bounds = screenSizePx(context)
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            screenWidthPx = bounds.first,
            screenHeightPx = bounds.second,
            densityDpi = densityDpi(context),
            languageTag = Locale.getDefault().toLanguageTag(),
        )
    }

    /**
     * 屏幕像素尺寸。
     *
     * API 30 起走 `WindowManager#getMaximumWindowMetrics`——`Resources#getDisplayMetrics`
     * 在 API 34 被标记为弃用，而它返回的是**当前窗口**的尺寸，分屏/折叠屏上会小于屏幕，
     * 写进日志就成了错的信息。低版本只能退回老 API，那里没有更好选择。
     */
    private fun screenSizePx(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(WindowManager::class.java)
            val rect = manager?.maximumWindowMetrics?.bounds
            if (rect != null && rect.width() > 0 && rect.height() > 0) {
                return rect.width() to rect.height()
            }
        }
        @Suppress("DEPRECATION")
        val legacy: DisplayMetrics = context.resources.displayMetrics
        return legacy.widthPixels to legacy.heightPixels
    }

    /**
     * 密度。
     *
     * 用 `DENSITY_DEVICE_STABLE`（设备稳定的物理密度）而不是当前 `DisplayMetrics.densityDpi`：
     * 后者会被用户的「显示大小」设置改掉，于是同一台机器在不同设置下报出不同的 dpi，
     * 看起来像是两台设备。
     */
    private fun densityDpi(context: Context): Int {
        val stable = DisplayMetrics.DENSITY_DEVICE_STABLE
        if (stable > 0) return stable
        @Suppress("DEPRECATION")
        return context.resources.displayMetrics.densityDpi
    }
}
