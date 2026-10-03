package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.format.TimeFormat
import com.multisuperplayer.core.common.text.MspText

/**
 * 这套引擎用哪一种识别器。
 *
 * 区别不是「快慢」，而是**解码的形状**：
 *
 * - [OFFLINE]：先把一段语音整段切出来，再一次性解码成整句文字。准确率明显更高，
 *   但一个字都拿不到之前必须先攒够一整段。
 * - [STREAMING]：一边喂一边解码，词是一个一个吐出来的。适合中英混说
 *   （英文词得靠子词单元才拼得对），代价是模型大、慢。
 *
 * 这个枚举决定「用哪个类、哪个配置对象」（`OfflineRecognizer` vs `OnlineRecognizer`），
 * 不是一句说明文字——所以它必须是从 [AsrModelInfo] 里读出来的事实，
 * 而不是界面上让用户勾的开关：勾错了就是「模型加载失败」，没有任何解释能给他看。
 */
enum class AsrEngine {
    OFFLINE,
    STREAMING,
}

/**
 * 一个模型文件在识别器的配置里扮演哪个角色。
 *
 * 用「角色」而不是文件名来指路，是因为引擎要的是 `tokens` / `encoder` / `decoder` /
 * `joiner` 这几个字段，而文件名是上游仓库自己起的（`encoder-epoch-99-avg-1.int8.onnx`）。
 * 把文件名写死在引擎代码里，上游换个版本就得改引擎；写在这里就只是改一行数据。
 */
enum class AsrFileRole {
    TOKENS,
    MODEL,
    ENCODER,
    DECODER,
    JOINER,
}

/**
 * 一个需要下载的模型文件。
 *
 * 三个字段全部来自**上游仓库的真实文件**：`sizeBytes` 与 `sha256` 都得实测。HuggingFace 的
 * `/api/models/<repo>` 只给文件名；`/api/models/<repo>/tree/main?expand=true` 会给 LFS
 * 大文件带上体积与 sha256，但 `tokens.txt` 这类小文件走的是普通 Git 对象，那里没有哈希——
 * 所以两个数最终都以「真下载一遍再算」为准，新加一条模型时这一步不能省。
 *
 * 为什么要钉 sha256：这个下载器要往用户手机上写将近 200 MB 的二进制模型，而它的失败
 * 方式是**静默**的——少一个字节、被运营商/代理塞了一个 HTML 错误页、下载到一半断了，
 * 症状都不是「下载失败」，而是「识别出来的字幕是乱码」或者「引擎加载时崩溃」。
 * 一个体积对得上但内容不对的文件，只有哈希能发现。
 */
data class AsrModelFile(
    val role: AsrFileRole,
    /** 仓库内的相对路径，同时也是下载后落在模型目录里的相对路径。 */
    val path: String,
    val sizeBytes: Long,
    /** 小写十六进制的 sha256。 */
    val sha256: String,
)

/**
 * 一条可用的语音识别模型。
 *
 * 模型本身**不进 APK**（一条就 78～190 MB），而是首次使用时下载到
 * `filesDir/asr/<id>/`（见 [AsrModelLocator]）。所以这个类是「清单」：它同时是下载器的任务表、
 * 设置页的列表数据、以及引擎构造配置时的依据——三处只能是同一份数据，
 * 否则会出现「设置页说已下载、引擎说文件不存在」。
 */
data class AsrModelInfo(
    /** 稳定的标识，会写进设置、也会当成目录名，所以**不能含路径分隔符**。 */
    val id: String,
    val engine: AsrEngine,
    /** HuggingFace 仓库名（`owner/name`）。 */
    val repo: String,
    val name: MspText,
    val description: MspText,
    /** 生成的字幕标成什么语言（BCP-47）。 */
    val languageTag: String,
    val files: List<AsrModelFile>,
) {

    /** 需要下载的总体积。进度条、以及「这个模型多大」都从这里算，不手抄。 */
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /**
     * 取某个角色的文件。
     *
     * 拿不到就是清单写错了（比如给流式模型漏了 joiner），属于**编程错误**：
     * 这种情况下引擎根本构造不出来，报一个「缺哪个角色」的异常比让它带着
     * 空路径去调用 JNI 好得多——后者会在原生层崩，堆栈里一个字都读不出来。
     */
    fun file(role: AsrFileRole): AsrModelFile =
        files.firstOrNull { it.role == role }
            ?: error("模型「$id」的清单里缺少 ${role.name} 文件")

    /**
     * 这条模型是不是 **transducer**（zipformer 系）：清单里同时有 encoder / decoder / joiner。
     *
     * 为什么用它分流、而不是再加一个 [AsrEngine] 成员：[AsrEngine] 说的是**解码的形状**
     * （整段解完 vs 边喂边解），而 transducer 和 paraformer 都是「整段解完」——
     * 真正要分的是「把哪个路径填进哪个字段」，那件事本来就由 [AsrFileRole] 表达
     * （它存在的意义就是「这个文件填进引擎的哪个字段」，见上）。让角色继续承担这份职责，
     * 就不必维护「引擎种类」和「清单里有哪些文件」这两份可能互相矛盾的真相。
     *
     * 判据取 `ENCODER`：它是三件套里必然出现的那一件，而 paraformer 的清单里只有 `MODEL`。
     */
    val isTransducer: Boolean get() = files.any { it.role == AsrFileRole.ENCODER }

    /** 「约 78.1 MB」。 */
    fun sizeText(): MspText = MspText.Res(R.string.msp_asr_model_size, TimeFormat.fileSize(totalBytes))

    /** 列表里那一行：`中文离线（Paraformer 小模型） · 约 78.1 MB`。 */
    fun labelWithSize(): MspText =
        MspText.join(MspText.Res(R.string.msp_asr_detail_sep), listOf(name, sizeText()))
}
