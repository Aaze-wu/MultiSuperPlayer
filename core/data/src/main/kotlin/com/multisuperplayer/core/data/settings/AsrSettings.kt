package com.multisuperplayer.core.data.settings

import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.AsrService
import com.multisuperplayer.core.asr.AsrServices
import com.multisuperplayer.core.asr.isHttpAddress
import com.multisuperplayer.core.asr.normalizeModelBaseUrl

/**
 * 语音识别的偏好：走哪条路（本机 / 云端）、用哪条模型或哪家服务、从哪个源下载。
 *
 * ## 「没设置」和「设置成默认值」是两件事
 *
 * 所有「用户填过的东西」都存原文、缺省用 `null` 表示。压成一个「总是有值」的字符串
 * 之后，用户就再也看不出自己改过没有了——界面会显示默认镜像站，而他明明填过另一个，
 * 于是「我的地址怎么没了」。
 *
 * ## 但「没选过服务商」和「选了个不认得的服务商」必须分开
 *
 * 这是本文件里唯一一处「缺省」不等于「回落」的地方，见 [cloudService]：
 * - **没选过** → 用 [AsrServices.DEFAULT_SERVICE]（选完就能用的那一家）；
 * - **选过但认不出来**（老版本留下的、被手改过的 id）→ 回 [AsrServices.CUSTOM]，
 *   也就是「地址在你手上，我不猜」。
 *
 * 两者都回默认服务商的话，第二种情况会表现为「我配的中转站地址还在框里，但请求
 * 发去了硅基流动」——而失败信息指向的是硅基流动，用户根本查不出来。
 *
 * ## 为什么地址和模型名要能「只填一个」
 *
 * 用户可能只想换模型（`whisper-large-v3` 而不是自带那个），也可能只想换地址
 * （走自己的中转站）。所以两者各自独立缺省：谁空用谁的预设值，互不牵连。
 *
 * @param storedRouteId 用户选的路线 id；`null` = 没选过 = [AsrRoute.DEFAULT]（本机）。
 * @param storedCloudServiceId 用户选的预设 id；`null` = 没选过。见 [cloudService]。
 * @param storedCloudBaseUrl 用户填的云端地址；`null`/空白 = 用预设自带的。
 * @param storedCloudModel 用户填的云端模型名；`null`/空白 = 用预设自带的。
 * @param cloudApiKeyStored 当前服务商的密钥存了没有（只有这个布尔值，没有密钥本身）。
 */
data class AsrSettings(
    /** 用户选过的模型 id；`null` = 没选过，用 [AsrModelCatalog.DEFAULT_ID]。 */
    val storedModelId: String? = null,

    /** 用户填过的下载源；`null`/空白 = 用 [com.multisuperplayer.core.asr.DEFAULT_MODEL_BASE_URL]。 */
    val storedBaseUrl: String? = null,

    /** 用户选过的识别路线 id；`null` = 没选过 = 本机。 */
    val storedRouteId: String? = null,

    /** 用户选过的云端服务商预设 id；`null` = 没选过，见 [cloudService]。 */
    val storedCloudServiceId: String? = null,

    /** 用户填过的云端服务地址；`null`/空白 = 用预设自带的。 */
    val storedCloudBaseUrl: String? = null,

    /** 用户填过的云端模型名；`null`/空白 = 用预设自带的。 */
    val storedCloudModel: String? = null,

    /**
     * 当前服务商的密钥是不是已经存在盘上了（只回答「有没有」，**不带**密钥本身）。
     *
     * ⚠️ 这个对象会被界面连着 KDoc 一起持有、也可能被 `toString()` 进日志，
     * 而密钥一旦进了那条路就收不回来了——所以字段是布尔值，不是字符串。
     *
     * 它必须与「实际请求会带上哪把钥匙」用同一个 owner（[cloudApiKeyOwner]），
     * 所以它只在 `toAsrSettings` 里算一次：界面自己 `apiKeys.get(...)` 的话，
     * 就变成两份判定摆在一起比对，而其中一份一定会先改。
     */
    val cloudApiKeyStored: Boolean = false,
) {
    /** 实际要用的模型：认不出来的 id 一律回落到默认模型，绝不抛异常。 */
    val model: AsrModelInfo get() = AsrModelCatalog.byId(storedModelId)

    /** 实际要用的下载源，永远是一个可直接拼路径的地址。 */
    val baseUrl: String get() = normalizeModelBaseUrl(storedBaseUrl)

    /** 是否在用默认镜像站（界面据此显示「默认」提示）。 */
    val usesDefaultSource: Boolean get() = storedBaseUrl.isNullOrBlank()

    // ------------------------------------------------------------------ 路线

    /** 实际要走的路线。缺省本机——这是隐私口径，理由见 [AsrRoute]。 */
    val route: AsrRoute get() = AsrRoute.byId(storedRouteId)

    /** 是否把音频发给服务商。界面用它决定要不要显示隐私提示。 */
    val usesCloud: Boolean get() = route == AsrRoute.CLOUD

    // ------------------------------------------------------------------ 云端

    /**
     * 实际要用的云端服务商。
     *
     * 缺省值（`null`）与坏值是**分开**处理的，见类注释：没选过给默认服务商，
     * 认不出来给「自定义」。写入口会把任意 id 归一化成清单里的 id，所以
     * 认不出来的值只会来自旧版本或手改过的配置。
     */
    val cloudService: AsrService
        get() = when (val stored = storedCloudServiceId) {
            null -> AsrServices.DEFAULT_SERVICE
            else -> AsrServices.byId(stored)
        }

    /**
     * 实际要用的云端地址：用户填过的优先，没填用预设自带的。
     *
     * 自定义预设自带的地址是**空串**（那是唯一「只有用户知道」的一家），所以这个
     * 属性在「自定义 + 没填」时会返回空串——**空串不是「不能用」，是「还没填」**，
     * 由 [cloudAddressLooksValid] 回答。这里不抛异常也不回落到别家：回落到别家
     * 意味着把音频发给一个用户从没听说过的域名。
     */
    val cloudBaseUrl: String
        get() = storedCloudBaseUrl?.trim()?.takeIf { it.isNotEmpty() } ?: cloudService.baseUrl

    /** 实际要用的云端模型名。空白 ⇒ 用预设自带的（自定义预设可能是空串）。 */
    val cloudModel: String
        get() = storedCloudModel?.trim()?.takeIf { it.isNotEmpty() } ?: cloudService.model

    /**
     * 这个地址现在能不能拿去发请求。
     *
     * 与 `core:asr` 里开连接之前那道检查（`checkEndpointUsable`）**用的是同一个判定**
     * （[isHttpAddress]）：这里只用来把按钮变灰 + 说明原因，真正拦住请求的是那一道。
     * 两处各写一遍的话会出现「按钮是亮的、点了报网络错误」。
     */
    val cloudAddressLooksValid: Boolean get() = isHttpAddress(cloudBaseUrl)

    /**
     * 云端识别的密钥在 `ApiKeyStore` 里的 owner id。
     *
     * 带 `asr-` 前缀是因为 `ApiKeyStore` 的键名是 `translation.api_key.<providerId>`
     * （`translation` 那段已经在盘上了，改不了）。不带前缀的话，用户给「翻译 → OpenAI」
     * 填的密钥会被识别页当成自己的——**在设置页改识别密钥会静默改掉翻译密钥**。
     */
    val cloudApiKeyOwner: String get() = "asr-${cloudService.id}"

    /** 这一家是否必须填密钥（自定义中转站可以不带）。 */
    val cloudNeedsApiKey: Boolean get() = cloudService.requiresApiKey
}
