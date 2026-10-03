package com.multisuperplayer.core.data.settings

import com.multisuperplayer.core.asr.AsrException
import com.multisuperplayer.core.asr.AsrModelInfo
import com.multisuperplayer.core.asr.AsrRoute
import com.multisuperplayer.core.asr.CloudAsrConfig

/**
 * 「这一次生成字幕具体怎么做」——路线与参数都在这里定下来。
 *
 * ## 为什么要有这一层，而不是让生成器自己去读设置
 *
 * 两个理由，都不是为了好看：
 *
 * ① **跑的过程中改设置不能中途换引擎**。云端是一块一块传的，如果每一块都去读一次
 *    当前设置，用户在传输过程中切到「本机」就会变成「前 3 块走了云端、后面的在本机
 *    算」，而两边的时间轴会被拼成一条字幕——一条永远对不上的字幕，且没有任何报错。
 *    组装一次、按它跑完，这个可能就不存在了。
 *
 * ② **「本机模型没装」不能变成「那就用云端」**。这是隐私口径：静默切换意味着用户
 *    一次都没选过云端，音频却已经上传了。所以 [AsrRoute] 是**唯一**的判据，
 *    装没装模型只影响要不要先去下载（见 `SubtitleViewModel`）。
 *
 * ## 为什么是 sealed 而不是一个「两个字段都可能为空」的数据类
 *
 * 那种形态会让「本机但模型为空」「云端但地址为空」变成**能构造出来**的状态，
 * 于是每个消费点都要再判一次「到底哪半边有值」。这里两条路各自带上各自必需的参数，
 * 消费点一个 `when` 分完就没得选错了。
 */
sealed interface AsrJob {

    /** 用本机模型识别。不需要网络，不需要密钥。 */
    data class OnDevice(val model: AsrModelInfo) : AsrJob

    /**
     * 用云端接口识别。
     *
     * [config] 里的地址不做校验：空地址**不在这里**被拦下，而是由
     * `CloudAsrClient` 开连接之前那道 `checkEndpointUsable` 拦（它们用的是同一个判定
     * `isHttpAddress`，而真正必须拦住请求的是那一道）。这里只把配置搬过去。
     */
    data class Cloud(val config: CloudAsrConfig) : AsrJob
}

/**
 * 读密钥 + 组装。界面层只需要这一个入口。
 *
 * 密钥的 owner id 由 [AsrSettings.cloudApiKeyOwner] 决定（带 `asr-` 前缀），
 * 所以「读哪把钥匙」这件事不会散落在调用点上——散落的结果是某处忘了前缀，
 * 于是识别页读到了翻译页填的密钥：**能跑通，但用的是别人的账号**。
 *
 * @throws AsrException.CloudAuth 选了云端、该服务商需要密钥、而用户没填。
 */
suspend fun assembleJob(settings: AsrSettings, apiKeys: ApiKeyStore): AsrJob =
    resolveJob(settings, apiKeys.get(settings.cloudApiKeyOwner))

/**
 * 纯函数版的组装：把「读密钥」拿掉之后，剩下的全是判定逻辑，可以直接单测。
 *
 * 拆出来的理由就是可测：`ApiKeyStore` 的构造要 `Context`，包进去之后
 * 「该要密钥的没填密钥」「自定义不强制密钥」「地址与模型各自缺省」这些
 * 最容易写错的分支就只能在真机上碰运气了。
 *
 * ## 为什么「没填密钥」在发请求之前就失败
 *
 * 预设里那三家（OpenAI / Groq / 硅基流动）**一定**要密钥，而鉴权失败（401）只有在
 * 服务端读完整个请求体之后才会返回——也就是白传一块（约 9.6 MB）才知道。手机流量
 * 上这不是小事，所以在这里就抛，文案与 401 走的是同一档（[AsrException.CloudAuth]，
 * 会报出**本地化的服务商名**），用户看到的指引完全一致。
 *
 * 自定义预设的 `requiresApiKey` 是 `false`（见 `AsrServices.CUSTOM`），所以自建/
 * 中转站不会被这条拦下——它们很可能根本不校验密钥。
 *
 * @param apiKey 已经取出来的密钥（空白与 `null` 等价）。
 * @throws AsrException.CloudAuth 选了云端、该服务商需要密钥、而密钥为空。
 */
internal fun resolveJob(settings: AsrSettings, apiKey: String?): AsrJob {
    if (!settings.usesCloud) return AsrJob.OnDevice(settings.model)

    val service = settings.cloudService
    val key = apiKey?.takeIf { it.isNotBlank() }
    if (service.requiresApiKey && key == null) {
        throw AsrException.CloudAuth(service.id)
    }
    return AsrJob.Cloud(
        CloudAsrConfig(
            serviceId = service.id,
            baseUrl = settings.cloudBaseUrl,
            model = settings.cloudModel,
            apiKey = key,
            supportsSegments = service.supportsSegments,
        ),
    )
}
