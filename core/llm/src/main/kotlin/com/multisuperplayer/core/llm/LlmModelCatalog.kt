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
 * ## 为什么先只有 Qwen3-0.6B
 *
 * 0.6B 是能在手机上「跑得动」的量级：更大的（1.7B / 4B）在同一条字幕批量上要慢
 * 好几倍，而字幕翻译不需要世界知识。宁可先上一条能用的，也不要给一个「点了要等
 * 十分钟」的选项。
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
     * 首条（也是当前唯一一条）模型的 id。
     *
     * 声明顺序不能随意：`DEFAULT_ID` 引用它，而 object 里的 `const val` 初始化
     * 是按书写顺序做的，写在后面会报「must be initialized」。
     */
    const val QWEN3_06B_ID: String = "qwen3-0.6b"

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

    /** 全部内置模型，顺序就是设置页里的显示顺序。 */
    val models: List<LlmModelInfo> = listOf(qwen3_06b)

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
