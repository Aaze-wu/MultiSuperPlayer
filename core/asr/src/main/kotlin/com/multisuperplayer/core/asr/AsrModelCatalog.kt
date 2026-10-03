package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.text.MspText

/**
 * 默认下载源。
 *
 * `hf-mirror.com` 是 HuggingFace 的国内镜像，路径结构与官方完全一致
 * （`<base>/<repo>/resolve/main/<file>`），实测能直接下到这几条模型。
 *
 * 为什么不默认 `huggingface.co`：这两条模型一共约 268 MB，而官方站在国内**经常**
 * 连得上、只是慢到几十 KB/s —— 那种体验不像「下载失败」，像「卡住了」，
 * 用户会一直等。镜像站可以直接改成官方站，所以默认取「能用」的那一个。
 */
const val DEFAULT_MODEL_BASE_URL: String = "https://hf-mirror.com"

/**
 * 把用户填的下载源规整一下。
 *
 * 只做两件事：去首尾空白、去末尾斜杠。**不补协议**——把 `hf-mirror.com` 悄悄
 * 补成 `https://hf-mirror.com` 会让「少写了 https://」这个错误变成「看起来能用」，
 * 于是用户按照自己的记忆填一个错的域名时也一路绿灯，直到下载才开始报
 * `UnknownHostException`。设置页那里有一条「必须以 http:// 或 https:// 开头」的
 * 校验，让它在那里就被拦住。
 *
 * 空值回落到 [DEFAULT_MODEL_BASE_URL]。
 *
 * 「空」是在**去完斜杠之后**判定的：用户把地址清成 `/` 或者 `//` 时，
 * 归一化的结果是空串，而那不是一个地址——拼出来的下载地址会变成
 * `/repo/resolve/main/...`，报的是 `UnknownHostException` 之类的底层错误，
 * 看着像网络问题，其实是设置里那一个斜杠。所以这里统一回到默认站。
 */
fun normalizeModelBaseUrl(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return DEFAULT_MODEL_BASE_URL
    return trimmed.trimEnd('/').ifEmpty { DEFAULT_MODEL_BASE_URL }
}

/**
 * 一个模型文件的下载地址：`<base>/<repo>/resolve/main/<path>`。
 *
 * 拼 URL 这件事必须是**纯函数**并且只有一处：设置页要能显示「会从哪里下」、
 * 下载器要去下、测试要能钉住「换了源之后地址里的仓库名没被换掉」。
 * 三处各拼一遍的话，最常见的错法（把 repo 也当成可配置的）会一直藏在里面。
 */
fun AsrModelInfo.downloadUrl(file: AsrModelFile, baseUrl: String?): String =
    "${normalizeModelBaseUrl(baseUrl)}/$repo/resolve/main/${file.path}"

/**
 * 内置模型的清单。
 *
 * ## 为什么不把模型打进 APK
 *
 * 两条都很大（78.1 MB / 189.8 MB），而用户通常只需要一条。打进包里等于让每个
 * 用户都为两条付费（下载体积、安装体积、以及 32 位设备上的存储），
 * 而字幕生成这个功能是**用的时候才需要**。所以模型放在应用私有目录里按需下载。
 *
 * ## 为什么选 `.int8` 量化版
 *
 * 手机上跑的瓶颈是算力（见 [AsrModelInfo.engine] 的注释），int8 版比 fp32 小一半、
 * 快一倍，而这两个模型在中文上的字错率差别在字幕场景下可以忽略。
 * 注意 Zipformer 的 **decoder 没有 int8 版**（上游那个包里就是 fp32 的），
 * 这不是写错了。
 */
object AsrModelCatalog {

    /**
     * 两条模型的 id。
     *
     * 声明顺序不能随意：`DEFAULT_ID` 引用 `PARA_FORMER_ID`，而 object 里的
     * `const val` 初始化是按书写顺序做的，写在后面就会报
     * 「Variable 'PARA_FORMER_ID' must be initialized」（编译器不会替你重排）。
     */
    const val PARA_FORMER_ID: String = "paraformer-zh-small"
    const val ZIPFORMER_ID: String = "zipformer-bilingual-zh-en"

    /** 用户没选、或者存的值已经不存在时用哪一条。 */
    const val DEFAULT_ID: String = PARA_FORMER_ID

    private val paraformerZhSmall = AsrModelInfo(
        id = PARA_FORMER_ID,
        engine = AsrEngine.OFFLINE,
        repo = "csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09",
        name = MspText.Res(R.string.msp_asr_model_paraformer_name),
        description = MspText.Res(R.string.msp_asr_model_paraformer_desc),
        // paraformer 这条是纯中文模型：把生成的字幕标成 zh，而不是「未知」。
        languageTag = "zh",
        files = listOf(
            AsrModelFile(
                role = AsrFileRole.MODEL,
                path = "model.int8.onnx",
                sizeBytes = 81_828_675,
                sha256 = "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec",
            ),
            AsrModelFile(
                role = AsrFileRole.TOKENS,
                path = "tokens.txt",
                sizeBytes = 75_352,
                sha256 = "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa",
            ),
        ),
    )

    private val zipformerBilingualZhEn = AsrModelInfo(
        id = ZIPFORMER_ID,
        engine = AsrEngine.STREAMING,
        repo = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
        name = MspText.Res(R.string.msp_asr_model_zipformer_name),
        description = MspText.Res(R.string.msp_asr_model_zipformer_desc),
        languageTag = "zh",
        files = listOf(
            AsrModelFile(
                role = AsrFileRole.ENCODER,
                path = "encoder-epoch-99-avg-1.int8.onnx",
                sizeBytes = 181_895_032,
                sha256 = "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
            ),
            AsrModelFile(
                role = AsrFileRole.DECODER,
                path = "decoder-epoch-99-avg-1.onnx",
                sizeBytes = 13_876_452,
                sha256 = "2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d",
            ),
            AsrModelFile(
                role = AsrFileRole.JOINER,
                path = "joiner-epoch-99-avg-1.int8.onnx",
                sizeBytes = 3_228_404,
                sha256 = "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
            ),
            AsrModelFile(
                role = AsrFileRole.TOKENS,
                path = "tokens.txt",
                sizeBytes = 56_317,
                sha256 = "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
            ),
        ),
    )

    /** 全部内置模型，顺序就是设置页里的显示顺序（小的在前）。 */
    val models: List<AsrModelInfo> = listOf(paraformerZhSmall, zipformerBilingualZhEn)

    /** 按 id 找。找不到（老版本留下的值、手工改过的配置）回落到默认模型。 */
    fun byId(id: String?): AsrModelInfo {
        val key = id?.trim()
        return models.firstOrNull { it.id == key } ?: models.first { it.id == DEFAULT_ID }
    }

    /** 按 id 找，找不到返回 null——用于「这个模型还在不在」这种需要区分「没有」的场合。 */
    fun find(id: String?): AsrModelInfo? {
        val key = id?.trim() ?: return null
        return models.firstOrNull { it.id == key }
    }
}

/**
 * VAD（语音活动检测）模型。
 *
 * 它是**分段器**，不产生文字：把整条音轨里「有人在说话」的段落切出来，再交给
 * 识别器。不做这一步而直接把两小时的音频丢给识别器，实测会在很长的静音上
 * 产生幻觉文字（识别器会「听出」不存在的句子），而且慢得多。
 *
 * 这个模型只有 629 KB，所以**打进 APK**（`core/asr/src/main/assets/silero_vad.onnx`），
 * 不做下载。它是 sherpa-onnx 官方发布里 silero-vad 的标准版本。
 */
const val VAD_MODEL_ASSET: String = "silero_vad.onnx"

/**
 * VAD 每次喂进去的采样点数（16 kHz、35 ms）。
 *
 * **这个数必须和模型对得上**：silero-vad v4/v5 在 16 kHz 下的输入窗口就是 512 点，
 * 喂多了少了都不会报错，只是检测结果变成噪声（要么全是静音、要么整段都是人声）。
 * 所以它是常量而不是可调项，并且 `SherpaEngine` 会按它切窗口。
 */
const val VAD_WINDOW_SIZE: Int = 512
