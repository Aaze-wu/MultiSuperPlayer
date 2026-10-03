package com.multisuperplayer.core.translate

import com.multisuperplayer.core.llm.LlmFailureKind
import com.multisuperplayer.core.llm.LlmGenerationOutcome
import com.multisuperplayer.core.llm.LlmGenerationRequest
import com.multisuperplayer.core.llm.LlmGenerationResult
import com.multisuperplayer.core.llm.LlmTextGenerator
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一个请求该出网还是该在本机跑」的测试。
 *
 * 这一层只有一件事要做，但它**做错的两种方式都不会报错**：
 * 本地请求被发到网上（用户的提示词与字幕内容离开了设备，而这一路本来跟厂商无关），
 * 或者远端请求被丢进本机引擎（用户会看到「模型没下载」，而去下 345 MB 也修不好，
 * 因为他压根没选本地）。两种都是「功能看起来只是不好用」。
 *
 * 所以这里的断言是**互相排他的**：每次调用之后，另一侧必须一条请求都没有。
 */
class SwitchingChatClientTest {

    /** 记下每一次本机生成请求。 */
    private class RecordingGenerator(
        private val respond: (LlmGenerationRequest) -> LlmGenerationOutcome,
    ) : LlmTextGenerator {

        val requests = mutableListOf<LlmGenerationRequest>()
        var releaseCount = 0

        override suspend fun generate(request: LlmGenerationRequest): LlmGenerationOutcome {
            requests += request
            return respond(request)
        }

        override suspend fun release() {
            releaseCount++
        }
    }

    /** 出网的请求：有地址、有 key，没有本地模型 id。 */
    private fun remoteRequest() = ChatCompletionRequest(
        url = "https://api.deepseek.com/chat/completions",
        apiKey = "sk-test",
        model = "deepseek-flash",
        systemPrompt = "sys",
        userPrompt = "usr",
        maxTokens = 512,
        temperature = 0.3,
    )

    /** 本机跑的请求：地址是空串（本地本来就没地址），模型 id 就是清单里的 id。 */
    private fun localRequest(
        modelId: String = "qwen3-0.6b",
        maxTokens: Int = 512,
        temperature: Double = 0.3,
        expectedItems: Int? = null,
    ) = ChatCompletionRequest(
        url = "",
        apiKey = null,
        model = modelId,
        onDeviceModelId = modelId,
        expectedItems = expectedItems,
        systemPrompt = "sys",
        userPrompt = "usr",
        maxTokens = maxTokens,
        temperature = temperature,
    )

    private fun ok(
        text: String = "译文",
        outputTokens: Int? = 42,
        truncated: Boolean = false,
    ) = LlmGenerationOutcome.Ok(
        LlmGenerationResult(
            text = text,
            outputTokens = outputTokens,
            promptTokens = 7,
            truncated = truncated,
        ),
    )

    // ------------------------------------------------------------ 分发

    @Test
    fun `没有本地模型 id 的请求只走远端，本机一次都不碰`() = runTest {
        val remote = RecordingChatClient { _, _ -> okResponse("remote") }
        val generator = RecordingGenerator { error("本机不该被调用") }
        val client = SwitchingChatClient(remote, generator)

        val outcome = client.complete(remoteRequest())

        assertEquals(1, remote.requests.size)
        assertTrue("本机不该收到任何请求", generator.requests.isEmpty())
        assertEquals("remote", (outcome as ChatCompletionOutcome.Ok).response.content)
    }

    @Test
    fun `带本地模型 id 的请求只走本机，一次网都不出`() = runTest {
        // `respond` 直接炸：只要走到远端就是**失败**，而不是「多了一次请求」。
        // 本地路径存在的全部意义就是不出网（隐私与额度），
        // 「顺手也发一份给远端」是不允许的。
        val remote = RecordingChatClient { _, _ -> error("远端不该被调用") }
        val generator = RecordingGenerator { ok() }
        val client = SwitchingChatClient(remote, generator)

        val outcome = client.complete(localRequest())

        assertTrue("远端不该收到任何请求", remote.requests.isEmpty())
        assertEquals(1, generator.requests.size)
        assertTrue(outcome is ChatCompletionOutcome.Ok)
    }

    @Test
    fun `本机请求原样带上提示词、预算、温度，并强制加上约束解码的 schema`() = runTest {
        val remote = RecordingChatClient { _, _ -> error("远端不该被调用") }
        val generator = RecordingGenerator { ok() }
        val client = SwitchingChatClient(remote, generator)

        client.complete(
            localRequest(
                modelId = "qwen3-0.6b",
                maxTokens = 4096,
                temperature = 0.9,
                expectedItems = 8,
            ),
        )

        val sent = generator.requests.single()
        assertEquals("qwen3-0.6b", sent.modelId)
        assertEquals("sys", sent.systemPrompt)
        assertEquals("usr", sent.userPrompt)
        // 预算与温度必须一路传到底：引擎的「撞上限就加大预算重试」和
        // 「降温度再试一次」这两条路在本机同样要能用，而它们靠的就是这两个字段。
        assertEquals(4096, sent.maxOutputTokens)
        assertEquals(0.9, sent.temperature, 0.0)
        // 约束解码是本机这一路唯一能真正钉死输出形状的手段（远端的 response_format
        // 只保证「是合法 JSON」，字段名根本不发给模型）。漏了它，本机输出会变成
        // 一堆自创键名，而错误信息看起来像「模型不听话」。
        assertEquals(localTranslationSchema(8), sent.jsonSchema)
        // 条数必须跟着请求走：同一个客户端要服务 batch=8 和 batch=4 两种批次，
        // 写死任何一个数都会让另一半批次在解码期就被卡住。
        assertTrue("条数要钉进 schema", sent.jsonSchema.orEmpty().contains("\"minItems\": 8"))
    }

    // ------------------------------------------------------------ 成功回填

    @Test
    fun `本机成功时把引擎报的 token 数原样上报，reasoning 给 null 而不是 0`() = runTest {
        val generator = RecordingGenerator { ok(text = "你好", outputTokens = 42) }
        val client = SwitchingChatClient(
            RecordingChatClient { _, _ -> error("远端不该被调用") },
            generator,
        )

        val response = (client.complete(localRequest()) as ChatCompletionOutcome.Ok).response

        assertEquals("你好", response.content)
        assertEquals(42, response.completionTokens)
        assertEquals("stop", response.finishReason)
        // null 是「不适用」，0 是「确实没有」。本机固定关掉了思考，
        // 若传 0，界面上那句「思考吃掉了全部预算」的诊断在本地就会**永远显示**。
        assertNull("本机没有 reasoning token 这回事", response.reasoningTokens)
    }

    @Test
    fun `引擎说被剪断时报 finish_reason 为 length`() = runTest {
        // 解析层是靠 finish_reason（以及括号是否闭合）区分「被剪断」和「形状不对」的，
        // 这两种的处置**正好相反**：一个加大预算，一个换策略。
        // 这里把 `truncated = true` 吞掉的话，本机撞上限会被误判成格式问题，
        // 于是重试永远在同一个预算上打转。
        val client = SwitchingChatClient(
            RecordingChatClient { _, _ -> error("远端不该被调用") },
            RecordingGenerator { ok(truncated = true) },
        )

        val response = (client.complete(localRequest()) as ChatCompletionOutcome.Ok).response

        assertEquals("length", response.finishReason)
    }

    @Test
    fun `引擎没报 token 数时就是 null，不编一个 0 出来`() = runTest {
        val client = SwitchingChatClient(
            RecordingChatClient { _, _ -> error("远端不该被调用") },
            RecordingGenerator { ok(outputTokens = null) },
        )

        val response = (client.complete(localRequest()) as ChatCompletionOutcome.Ok).response

        assertNull(response.completionTokens)
    }

    // ------------------------------------------------------------ 失败映射

    private suspend fun failureOf(
        kind: LlmFailureKind,
        detail: String = "boom",
        modelId: String = "qwen3-0.6b",
    ): TranslationFailure {
        val client = SwitchingChatClient(
            RecordingChatClient { _, _ -> error("远端不该被调用") },
            RecordingGenerator { LlmGenerationOutcome.Failure(kind, detail) },
        )
        return (client.complete(localRequest(modelId = modelId)) as ChatCompletionOutcome.Err).failure
    }

    @Test
    fun `推理层三档各映射到翻译层对应的那一档`() = runTest {
        // 映射错一档的后果不是「话不好听」，而是**重试策略**跟着错：
        // 模型没下载时会傻傻地把每一批重试三次，用户看到的解释却是「格式不对」。
        assertEquals(
            TranslationFailure.LocalModelMissing("qwen3-0.6b"),
            failureOf(LlmFailureKind.MODEL_MISSING, detail = "文件不存在"),
        )
        assertEquals(
            TranslationFailure.LocalEngineUnavailable("load failed"),
            failureOf(LlmFailureKind.ENGINE_UNAVAILABLE, detail = "load failed"),
        )
        assertEquals(
            TranslationFailure.LocalGenerationFailed("empty output"),
            failureOf(LlmFailureKind.GENERATION_FAILED, detail = "empty output"),
        )
    }

    @Test
    fun `模型没下载时带的是模型 id，不是引擎给的那句详情`() = runTest {
        // 详情那句话来自原生侧，用户既读不懂也做不了什么；
        // id 则正是设置页里那一条模型，能拿去定位「下没下、下的哪条」。
        val failure = failureOf(LlmFailureKind.MODEL_MISSING, detail = "/data/.../x.litertlm 不存在")

        assertEquals("qwen3-0.6b", (failure as TranslationFailure.LocalModelMissing).model)
        assertFalse("别把路径漏到界面上", failure.model.contains('/'))
    }

    @Test
    fun `只有生成失败这一档原样可重试，另外两档要中止整个任务`() = runTest {
        // 这是三档存在的**唯一理由**。合并成一档的话，要么「模型没下载」被重试三次
        // （每次都要走一遍原生加载，用户干等），要么「这一次生成挂了」直接中止
        // （本来再试一次就好）。
        val missing = failureOf(LlmFailureKind.MODEL_MISSING)
        val engine = failureOf(LlmFailureKind.ENGINE_UNAVAILABLE)
        val generation = failureOf(LlmFailureKind.GENERATION_FAILED)

        assertTrue("模型没下载：重试没有意义", missing.abortsJob)
        assertFalse(missing.retryableAsIs)
        assertTrue("引擎起不来：重试同样没意义", engine.abortsJob)
        assertFalse(engine.retryableAsIs)
        assertFalse("这一档不该中止任务", generation.abortsJob)
        assertTrue("这一档就该原样再试一次", generation.retryableAsIs)
    }

    // ------------------------------------------------------------ 列模型

    @Test
    fun `列模型永远走远端`() = runTest {
        // 设备上不存在「列模型」这个动作：本机有哪几条由模型清单说话。
        // 所以这个签名上没有本地之分，实现也不该有第二个分支。
        val remote = RecordingChatClient { _, _ -> okResponse("x") }.apply {
            modelList = listOf("deepseek-chat", "deepseek-flash")
        }
        val generator = RecordingGenerator { error("本机不该被调用") }
        val client = SwitchingChatClient(remote, generator)

        val outcome = client.listModels("https://api.deepseek.com", "sk-test")

        assertEquals(listOf("deepseek-chat", "deepseek-flash"), (outcome as ModelListOutcome.Ok).models)
        assertTrue(generator.requests.isEmpty())
    }

    // ------------------------------------------------------------ schema 本身

    @Test
    fun `约束解码的 schema 钉的是 translations 数组，且必须能被解析`() = runTest {
        // schema 是**字符串**（交给原生侧编译），写错了不会有编译期检查：
        // 一个少了的引号会让整条本地路径在运行期起不来，而报错来自 native，
        // 我们连它说的是哪一条约束都看不出来。
        val root = Json.parseToJsonElement(localTranslationSchema(8)).jsonObject

        assertEquals("object", root["type"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("translations"),
            root["required"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        val translations = root["properties"]!!.jsonObject["translations"]!!.jsonObject
        assertEquals("array", translations["type"]?.jsonPrimitive?.content)
        assertEquals(
            "string",
            translations["items"]!!.jsonObject["type"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `条数被钉成调用方给的那个数，不是写死的`() = runTest {
        // 真机上换来的：0.6B 模型**稳定地**把一整批译文并成一个字符串（数组长度 1），
        // 解析器每次都报「条数是 1，期望 8」，本地翻译一条也出不来。
        // 形状对、条数不对，靠重试永远好不了——只能在解码期就不给这条路。
        val eight = boundsOf(8)
        assertEquals(8, eight["minItems"]?.jsonPrimitive?.content?.toInt())
        assertEquals(8, eight["maxItems"]?.jsonPrimitive?.content?.toInt())

        // 4 条的那一批必须拿到 4，不能沿用上一批的 8（同一个客户端两种批次都要跑）
        val four = boundsOf(4)
        assertEquals(4, four["minItems"]?.jsonPrimitive?.content?.toInt())
        assertEquals(4, four["maxItems"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `调用方不知道条数时退回纯形状约束，而不是编一个数`() = runTest {
        // 编 1 条或 0 条都会把正常请求卡死；没有信息就不约束这一项。
        for (bounds in listOf(boundsOfOrNull(null), boundsOfOrNull(0), boundsOfOrNull(-3))) {
            assertNull("不该有 minItems", bounds?.get("minItems"))
            assertNull("不该有 maxItems", bounds?.get("maxItems"))
        }
    }

    @Test
    fun `schema 里不写 additionalProperties`() = runTest {
        // 每多一个关键字，就多一个「约束解码器可能不认识」的东西，失败的代价是
        // 整条本地路径不能用。所以只留实测过有用的那一个（条数）。
        val translations = Json.parseToJsonElement(localTranslationSchema(8))
            .jsonObject["properties"]!!.jsonObject["translations"]!!.jsonObject

        assertFalse("不要 additionalProperties", translations.containsKey("additionalProperties"))
    }

    /** 取 `translations` 那一层的 schema 节点，顺手断言它确实是数组。 */
    private fun boundsOf(count: Int): JsonObject = boundsOfOrNull(count)!!

    private fun boundsOfOrNull(count: Int?): JsonObject? {
        val translations = Json.parseToJsonElement(localTranslationSchema(count))
            .jsonObject["properties"]!!.jsonObject["translations"]!!.jsonObject
        assertEquals("array", translations["type"]?.jsonPrimitive?.content)
        return translations
    }
}
