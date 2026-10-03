package com.multisuperplayer.core.llm

/**
 * 一次设备上生成请求。
 *
 * ⚠️ 这里**不带任何「翻译」概念**：本模块是「在设备上跑一个语言模型」的能力，
 * 翻译只是它的第一个使用者（下一个可能是字幕生成）。把译文格式、批大小之类的
 * 东西写进这里，就等于把翻译的规则钉进了推理层，将来第二个使用者要么绕开这层、
 * 要么把它改成两副面孔。
 */
data class LlmGenerationRequest(
    /** 用哪条模型（[LlmModelCatalog] 里的 id）。 */
    val modelId: String,
    /** 系统提示词。约束解码之外，调用方往往还会把手写的输出格式要求写在这里。 */
    val systemPrompt: String,
    val userPrompt: String,
    /** 输出上限。撞上它就是「被截断」，调用方要据此加大预算重试，而不是换个提示词。 */
    val maxOutputTokens: Int,
    val temperature: Double = 0.3,
    /** 约束解码用的 JSON Schema。null 表示不加约束（模型自由发挥）。 */
    val jsonSchema: String? = null,
)

/**
 * 一次生成的结果。
 *
 * [truncated] 与 [outputTokens] 是**引擎自己报的**，不是估算：本地跑的时候
 * 我们拿得到精确的 decode token 数（`BenchmarkInfo.lastDecodeTokenCount`），
 * 比「数一数字符再猜」准得多。剪断和「格式不对」在翻译引擎里走的是两条完全
 * 不同的处置分支（加大预算 vs 拆批/原样重试），所以这个字段必须可信。
 */
data class LlmGenerationResult(
    val text: String,
    /** 实际生成的 token 数；拿不到就是 null。 */
    val outputTokens: Int? = null,
    /** 提示词 token 数，只用于日志与调优。 */
    val promptTokens: Int? = null,
    val truncated: Boolean = false,
)

/**
 * 失败的三档，对应三种**不同的下一步**：
 *
 * | 档 | 谁的问题 | 下一步 |
 * |---|---|---|
 * | [MODEL_MISSING] | 模型文件不在 / 不完整 | 去设置页下载（重试再多也没用） |
 * | [ENGINE_UNAVAILABLE] | 引擎起不来（原生库、设备、内存） | 去看错误详情；重装应用或换设备 |
 * | [GENERATION_FAILED] | 这一次生成挂了 | **原样重试可能就好了** |
 *
 * 前两档要中止整个翻译任务，第三档要重试——所以它不能是一个笼统的「失败」。
 * 三档合并的后果是：模型没下载时，翻译任务会傻傻地把每一批都重试 3 次，
 * 而用户看到的解释是「模型返回格式不对」。
 */
enum class LlmFailureKind { MODEL_MISSING, ENGINE_UNAVAILABLE, GENERATION_FAILED }

sealed interface LlmGenerationOutcome {

    data class Ok(val result: LlmGenerationResult) : LlmGenerationOutcome

    data class Failure(val kind: LlmFailureKind, val detail: String) : LlmGenerationOutcome
}

/**
 * 「在设备上根据提示词生成一段文本」这个能力。
 *
 * ## 为什么是返回值而不是抛异常
 *
 * 使用者（翻译引擎）在 `core:translate` 里，而**一个模块不可能 catch 另一个模块的
 * 异常类型**——除非把本模块的异常类型放进公共 API 并让调用方依赖它。那会带来两个
 * 后果：调用方为了接一个错误而认识推理层（依赖方向反了），以及「调用方忘了 catch
 * 某一种异常」这种只能在真机上暴露的问题（异常穿过模块边界时没人保证被处理）。
 * 返回值是编译期穷举的，漏掉一档 `when` 编不过。
 *
 * ## 取消
 *
 * `CancellationException` 照旧向上抛（协程的规矩，任何实现都不能吞）。
 * 但要注意：**原生生成一旦开始就没法中断**，取消只能在开始前生效。实现必须用
 * 互斥把并发的生成串起来——两个原生生成同时跑会崩在 native 里，连栈都看不到。
 */
interface LlmTextGenerator {

    /**
     * 跑一次生成。**阻塞**，调用方负责切到 IO 线程（项目里的约定：自己偷偷切线程
     * 会让「换个调度器观察行为」的单测失效）。
     */
    suspend fun generate(request: LlmGenerationRequest): LlmGenerationOutcome

    /**
     * 放掉常驻的引擎（内存与模型文件的映射）。**幂等**，没加载过时调用是空操作。
     *
     * ## 为什么这件事必须在接口上，而不是让实现类自己多一个公开方法
     *
     * 因为调用它的是**设置页**（用户点「删除模型」），而设置页拿到的依赖类型是
     * 这个接口。把它留在 `LiteRtLmTextGenerator` 上，设置页就必须认识具体实现类
     * （或者对着接口做一次 `as` 转换——那是「换个实现就崩」的写法）。
     *
     * ## 为什么删模型之前非调不可
     *
     * 模型是 `mmap` 进去的。把一个还被映射着的文件删掉，`File.length()` 变成 0、
     * 目录里也看不见它，**但磁盘空间在映射消失之前不会还回来**——用户删掉 345 MB
     * 的模型、设置页显示「已删除」，可用空间却一点没变，要等进程重启才对得上。
     * 这是「操作成功但结果不对」里最难排查的一类。
     */
    suspend fun release()
}
