package com.multisuperplayer.core.llm

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText

/**
 * 一条可以下载到手机上、在设备上跑的文本生成模型。
 *
 * ## 为什么是**单个文件**
 *
 * `core:asr` 的 [com.multisuperplayer.core.asr.AsrModelInfo] 是一个模型对应多个文件
 * （transducer 的 encoder / decoder / joiner / tokens 必须分别喂给引擎的不同字段），
 * 所以那里有 `AsrFileRole` 这一层。LiteRT-LM 的 `.litertlm` 是**自包含的容器**：
 * 权重、分词器、对话模板都在同一个文件里，引擎只收一个路径。
 *
 * 所以这里**不**照抄「文件清单」抽象：一个 `files: List<...>` 里永远只有一项、
 * 每次都要 `first()`，读代码的人还得先搞清「多文件时怎么办」——而那件事不存在。
 * 将来真出现多文件格式（比如外挂视觉编码器）再引入列表，比现在预留一个空壳便宜。
 *
 * ## 为什么要钉 sha256
 *
 * 同 `AsrModelInfo`：这里要往用户手机上写将近 345 MB 的二进制，而它的失败方式是
 * **静默**的——少一个字节、被代理塞了一个 HTML 错误页、下载到一半断了，症状都不是
 * 「下载失败」，而是「翻译出来是乱码」或者「加载模型时崩溃」。体积对得上但内容不对的
 * 文件，只有哈希能发现。
 *
 * 这些数**全部实测得来**（HuggingFace 的 `/api/models/<repo>/tree/main` 会给 LFS 文件的
 * `oid`）。
 *
 * ## 为什么不写「支持的语言」
 *
 * 因为那是模型的**能力**而不是本应用的**配置**：翻译方向由界面上的目标语言决定，
 * 模型只是执行者。写一个「支持 100 种语言」的字段，只会在界面上多出一行没人看的字，
 * 还得维护它别过期。
 */
data class LlmModelInfo(
    /** 稳定的标识，会写进设置、也会当成目录名，所以**不能含路径分隔符**。 */
    val id: String,
    /** HuggingFace 仓库名（`owner/name`）。 */
    val repo: String,
    /** 仓库内的相对路径，同时也是下载后落在模型目录里的文件名。 */
    val fileName: String,
    val sizeBytes: Long,
    /** 小写十六进制的 sha256。 */
    val sha256: String,
    val name: MspText,
    val description: MspText,
) {
    /** 「约 328.7 MB」。 */
    fun sizeText(): MspText = MspText.Res(R.string.msp_llm_model_size, TimeFormat.fileSize(sizeBytes))

    /** 列表里那一行：`Qwen3 0.6B（本地） · 约 328.7 MB`。 */
    fun labelWithSize(): MspText =
        MspText.join(MspText.Res(R.string.msp_llm_detail_sep), listOf(name, sizeText()))
}
