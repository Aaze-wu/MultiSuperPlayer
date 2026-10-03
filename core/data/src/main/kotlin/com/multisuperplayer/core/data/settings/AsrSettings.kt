package com.multisuperplayer.core.data.settings

import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.normalizeModelBaseUrl

/**
 * 语音识别的偏好：用哪条模型、从哪个源下载。
 *
 * 两个字段都存「用户填过的原文」，缺省用 `null` 表示——因为
 * **「没设置」和「设置成默认值」在界面上是两件事**：前者显示「默认镜像站」，
 * 后者显示用户填的那一行。压成一个「总是有值」的字符串之后，
 * 用户就再也看不出自己改过没有了。
 */
data class AsrSettings(
    /** 用户选过的模型 id；`null` = 没选过，用 [AsrModelCatalog.DEFAULT_ID]。 */
    val storedModelId: String? = null,

    /** 用户填过的下载源；`null`/空白 = 用 [com.multisuperplayer.core.asr.DEFAULT_MODEL_BASE_URL]。 */
    val storedBaseUrl: String? = null,
) {
    /** 实际要用的模型：认不出来的 id 一律回落到默认模型，绝不抛异常。 */
    val model: AsrModelInfo get() = AsrModelCatalog.byId(storedModelId)

    /** 实际要用的下载源，永远是一个可直接拼路径的地址。 */
    val baseUrl: String get() = normalizeModelBaseUrl(storedBaseUrl)

    /** 是否在用默认镜像站（界面据此显示「默认」提示）。 */
    val usesDefaultSource: Boolean get() = storedBaseUrl.isNullOrBlank()
}
