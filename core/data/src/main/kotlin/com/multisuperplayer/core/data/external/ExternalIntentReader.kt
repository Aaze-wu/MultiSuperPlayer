package com.multisuperplayer.core.data.external

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.multisuperplayer.core.common.log.MspLog

/**
 * 把 `Intent` 摊成 [ExternalPayload]。
 *
 * 这是整条路上**唯一**碰 `Intent` 的地方，换句话说这里只做「取字段」，
 * 不做任何取舍——「哪些该收、类型怎么判、来源算哪个」全在
 * [ExternalMediaRules] 那个纯函数里（单测只测那一半，见那边的注释）。
 *
 * ## 三种"带 uri"的写法都要认
 *
 * 发 uri 的应用各写各的，实测会遇到三种：
 *
 * 1. `ACTION_SEND` + `EXTRA_STREAM`（一张图/一个视频，最常见的分享）；
 * 2. `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` 是 `ArrayList<Uri>`（多选分享）；
 * 3. **`ClipData`**：一部分应用（尤其是从 Chrome 里分享链接）只写 ClipData
 *    不写 `EXTRA_STREAM`。`EXTRA_STREAM` 拿不到时再读 ClipData，
 *    而不是只挑一种写法——只挑一种的后果是「某些应用的分享点了没反应」，
 *    而这类问题从代码上看不出任何异常。
 */
object ExternalIntentReader {

    /** 没有可播内容时返回 `null`。 */
    fun read(intent: Intent?): ExternalPayload? {
        if (intent == null) return null
        val action = intent.action
        return ExternalMediaRules.read(
            action = action,
            mimeType = intent.type,
            dataUri = intent.data?.toString(),
            streamUris = intent.streamUris(action),
        )
    }

    /**
     * 读 `EXTRA_STREAM` / `ClipData`。
     *
     * `runCatching` 不是防自己，是防**发 uri 的那个应用**：`EXTRA_STREAM` 里到底装的是
     * `Uri`、`String` 还是别的东西完全由对方决定，而 `IntentCompat` 的类型检查
     * 只覆盖了「单个 parcelable」那一种写法（数组那一路在某几个 API 上是不做检查的）。
     * 真丢进来一个异常就是 `lifecycleScope` 里的未捕获异常——**应用直接崩**，
     * 而代价只是「这一次分享没反应」。两害相权取轻，所以这里宁可吞掉它并留一条日志。
     */
    private fun Intent.streamUris(action: String?): List<String> =
        runCatching {
            when (action) {
                ExternalMediaRules.ACTION_SEND_MULTIPLE -> streamList() ?: clipUris()
                ExternalMediaRules.ACTION_SEND -> listOfNotNull(singleStream()).ifEmpty { clipUris() }
                else -> emptyList()
            }
        }.getOrElse { error ->
            MspLog.w(TAG, error) { "读取分享来的 uri 失败（action=$action）" }
            emptyList()
        }

    /**
     * `getParcelableExtra` 在 API 33 分了家（新的要传 `Class`），
     * `IntentCompat` 就是那层版本适配。自己写 `if (SDK_INT >= 33)` 分支也行，
     * 但那是每个读 parcelable 的地方都要重写一遍的重复劳动。
     */
    private fun Intent.singleStream(): String? =
        IntentCompat.getParcelableExtra(this, ExternalMediaRules.EXTRA_STREAM, Uri::class.java)
            ?.toString()

    private fun Intent.streamList(): List<String>? =
        IntentCompat
            .getParcelableArrayListExtra(this, ExternalMediaRules.EXTRA_STREAM, Uri::class.java)
            ?.mapNotNull { uri -> uri?.toString() }

    private fun Intent.clipUris(): List<String> {
        val clip = clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { index -> clip.getItemAt(index).uri?.toString() }
    }

    private const val TAG = "ExternalIntentReader"
}
