package com.multisuperplayer.core.llm

import com.multisuperplayer.core.common.text.MspText

/**
 * 默认下载源。
 *
 * `hf-mirror.com` 是 HuggingFace 的国内镜像，路径结构与官方完全一致
 * （`<base>/<repo>/resolve/main/<file>`）。与 `core:asr` 用的是同一个镜像站，
 * 但**键是分开存的**（`translation.local_base_url` vs `asr.base_url`）：
 * 一个源指错了不该悄悄改掉另一个源的配置。
 *
 * 为什么不默认 `huggingface.co`：这条模型 345 MB，官方站在国内经常连得上但慢到
 * 几十 KB/s，那种体验不像「下载失败」像「卡住了」，用户会一直等。
 */
const val DEFAULT_LLM_MODEL_BASE_URL: String = "https://hf-mirror.com"

/**
 * 把用户填的下载源规整一下。
 *
 * 只做两件事：去首尾空白、去末尾斜杠。**不补协议**——把 `hf-mirror.com` 悄悄补成
 * `https://hf-mirror.com` 会让「少写了 https://」这个错误变成「看起来能用」。
 * 设置页那里有一条「必须以 http:// 或 https:// 开头」的校验，让它在那里被拦住。
 *
 * 「空」是在**去完斜杠之后**判定的：用户把地址清成 `/` 时归一化结果是空串，
 * 拼出来的下载地址会变成 `/repo/resolve/main/...`，报的是 `UnknownHostException`
 * 之类的底层错误，看着像网络问题，其实是设置里那一个斜杠。所以统一回到默认站。
 *
 * 与 `core:asr` 的 `normalizeModelBaseUrl` 是同一段逻辑的两份副本：两个模块之间
 * 只共享 `core:common` 与 `core:model`，为一个 3 行的纯函数把 `core:llm` 绑到
 * `core:asr`（那份带 sherpa 的 jar 与 jniLibs）上不划算。
 */
fun normalizeLlmModelBaseUrl(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return DEFAULT_LLM_MODEL_BASE_URL
    return trimmed.trimEnd('/').ifEmpty { DEFAULT_LLM_MODEL_BASE_URL }
}

/**
 * 模型的下载地址：`<base>/<repo>/resolve/main/<fileName>`。
 *
 * 拼 URL 必须是**纯函数**并且只有一处：设置页要显示「会从哪里下」、下载器要去下、
 * 测试要能钉住「换了源之后仓库名没被换掉」。三处各拼一遍的话，最常见的错法
 * （把 repo 也当成可配置的）会一直藏在里面。
 */
fun LlmModelInfo.downloadUrl(baseUrl: String?): String =
    "${normalizeLlmModelBaseUrl(baseUrl)}/$repo/resolve/main/$fileName"

/**
 * 内置模型清单。
 *
 * ## 为什么不把模型打进 APK
 *
 * 首条就 345 MB，而整个 APK 现在是 88 MiB。本地翻译是**用的时候才需要**的功能，
 * 让每个用户都为它付出四倍的下载体积不划算。所以模型放在应用私有目录里按需下载
 * （见 [LlmModelLocator]）。
 *
 * ## 为什么默认仍是 Qwen3-0.6B，而不是质量更好的混元
 *
 * 两条模型的取舍完全不同，而**下载是不可撤销的**（1.82 GB 流量 + 1.82 GB 存储）：
 *
 * | 模型 | 体积 | 实测峰值内存 | 适合谁 |
 * |---|---|---|---|
 * | Qwen3-0.6B | 345 MB | 未实测（小一个数量级） | 所有人，尤其是入门机 |
 * | HY-MT2-1.8B | 1.82 GB | 2760 MB（Galaxy S26 · CPU） | 旗舰机，且愿意等 |
 *
 * 把默认改成 1.82 GB 的那条，等于让每一个新用户都为「更好一点」付 5 倍流量，
 * 而多数人根本不知道该不该付。默认放在 345 MB 上、把好的那条摆在旁边让用户自己选，
 * 是唯一不替用户花钱的做法。
 *
 * ## 为什么混元那条值 5 倍体积
 *
 * 它不是通用对话模型，是**翻译专用**的：腾讯 HY-MT2，1.8B，官方称 33 种语言、
 * 同尺寸下超过多数商业翻译接口。我们现在的目标语言早已不止中英两种，
 * 而 0.6B 的中英模型在日/韩/俄/阿这些语言上只是「沾了点多语言训练」。
 * 所以这两条不是「快」与「慢」的关系，而是「能翻中英」与「能翻 15 种」的关系。
 *
 * ## 为什么是 `int8` 这一条（仓库里只有一条）
 *
 * `litert-community/Hy-MT2-1.8B` 只提供 `Hy-MT2-1.8B_int8.litertlm` 一个文件
 * （1,815,622,960 B）。腾讯自己的仓库里有 1.25bit / 2bit 的 GGUF，但那些是 llama.cpp
 * 格式，LiteRT-LM 引擎读不了——格式不对的话，体积再小也用不上。
 *
 * ## 为什么是 `dynamic_wi4b32_afp32` 这一条
 *
 * 同一个仓库里有四个变体（实测体积）：
 *
 * | 文件 | 体积 | 说明 |
 * |---|---|---|
 * | `Qwen3-0.6B.litertlm` | 614,236,160 | 未量化，太大 |
 * | `Qwen3-0.6B.mediatek.mt6993.litertlm` | 1,202,913,280 | 只给联发科某颗 NPU 用 |
 * | **`Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm`** | **344,671,744** | 权重 int4、激活 fp32，**取这一条** |
 * | `qwen3_0_6b_mixed_int4.litertlm` | 497,516,544 | 混合 int4，更大 |
 *
 * 取体积最小的那条：手机上跑 0.6B 的瓶颈是内存与算力，两者都随体积走，
 * 而 int4 量化在字幕翻译这种任务上的质量差别可以忽略。
 */
object LlmModelCatalog {

    /**
     * 轻量那条。
     *
     * 声明顺序不能随意：`DEFAULT_ID` 引用它，而 object 里的 `const val` 初始化
     * 是按书写顺序做的，写在后面会报「must be initialized」。
     */
    const val QWEN3_06B_ID: String = "qwen3-0.6b"

    /**
     * 高质量那条：腾讯混元翻译模型 HY-MT2-1.8B 的 LiteRT-LM 版。
     *
     * ⚠️ 它比 `DEFAULT_ID` 那条正好宽一个条目，而两者都是 `String`：
     * 写错一个字符不会编译报错，只会变成「设置里选了一条不存在的模型」，
     * 静默回落成 0.6B。所以 `LlmModelCatalogTest` 里有一条断言专门钉
     * 「这个常量指向的真的是混元那条」。
     */
    const val HY_MT2_18B_ID: String = "hy-mt2-1.8b"

    /** 用户没选、或者存的值已经不存在时用哪一条。 */
    const val DEFAULT_ID: String = QWEN3_06B_ID

    private val qwen3_06b = LlmModelInfo(
        id = QWEN3_06B_ID,
        repo = "litert-community/Qwen3-0.6B",
        fileName = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        sizeBytes = 344_671_744,
        sha256 = "03e7da1eb1108b50dffaa9bb52cc7bcbad2eb0c66ca990267f480c1e545d2856",
        name = MspText.Res(R.string.msp_llm_model_qwen3_name),
        description = MspText.Res(R.string.msp_llm_model_qwen3_desc),
    )

    /**
     * 混元翻译模型 HY-MT2-1.8B（int8 量化）。
     *
     * [LlmModelInfo.peakMemoryBytes] 取的是上游 `litertlm_manifest.json` 里
     * CPU 后端的实测峰值（Galaxy S26 2760 MB、Pixel 8a 2685 MB），取两者的大值：
     * 这是一个「够不够用」的门槛，报小了会让用户当成能跑。
     */
    private val hy_mt2_18b = LlmModelInfo(
        id = HY_MT2_18B_ID,
        repo = "litert-community/Hy-MT2-1.8B",
        fileName = "Hy-MT2-1.8B_int8.litertlm",
        sizeBytes = 1_815_622_960,
        sha256 = "529e6d378df5869d89a5a08717c06604105a32d8a4dab4800175d6baabc4da50",
        name = MspText.Res(R.string.msp_llm_model_hymt2_name),
        description = MspText.Res(R.string.msp_llm_model_hymt2_desc),
        peakMemoryBytes = 2_760L * 1024 * 1024,
    )

    /** 全部内置模型，顺序就是设置页里的显示顺序：轻的在前面（默认那条也在前面）。 */
    val models: List<LlmModelInfo> = listOf(qwen3_06b, hy_mt2_18b)

    /** 按 id 找。找不到（老版本留下的值、手工改过的配置）回落到默认模型。 */
    fun byId(id: String?): LlmModelInfo {
        val key = id?.trim()
        return models.firstOrNull { it.id == key } ?: models.first { it.id == DEFAULT_ID }
    }

    /** 按 id 找，找不到返回 null——用于「还区分得出『没有』」的场合。 */
    fun find(id: String?): LlmModelInfo? {
        val key = id?.trim() ?: return null
        return models.firstOrNull { it.id == key }
    }
}
