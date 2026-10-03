package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.data.settings.TranslationSettings
import com.multisuperplayer.core.llm.LlmModelCatalog
import com.multisuperplayer.core.llm.LlmModelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「本地模型」设置页里那几处纯判定。
 *
 * 与 `AsrSettingsLogicTest` 同一个思路：没有 ViewModel 的用例——`LocalModelSettingsViewModel`
 * 拖着 `DataStore`、下载器、引擎三样依赖，而真正容易写错的仍然是那几处纯判定，
 * 它们在真机上的错法分别是：
 * 1. 「下载源看起来对不对」判断反了 ⇒ 默认状态（空）就标红，或写错前缀也不标红；
 * 2. 「删除模型（释放多少）」算错 ⇒ 下到一半就说能释放 345 MB；
 * 3. 「还没读过盘」被当成「未知」或「已下载」 ⇒ 首帧看起来像错误，或谎报已就绪。
 */
class LocalModelSettingsLogicTest {

    private val qwen = LlmModelCatalog.byId(LlmModelCatalog.DEFAULT_ID)

    // ------------------------------------------------------------ 下载源看起来对不对

    @Test
    fun `空地址算合格，因为空就是用默认镜像站`() {
        // 与语音识别页同一条规矩：把「没填」当成错误会让默认状态下的设置页一直标着红，
        // 而用户看到的红字会说「要以 http:// 开头」——他明明一个字都没填。
        assertTrue(looksLikeHttpUrl(""))
        assertTrue(looksLikeHttpUrl("   "))
        assertTrue(LocalModelUiState().sourceLooksValid)
        assertTrue(LocalModelUiState(settings = TranslationSettings(localModelSource = "")).sourceLooksValid)
    }

    @Test
    fun `http 与 https 都认，大小写和首尾空白不影响`() {
        assertTrue(looksLikeHttpUrl("http://hf-mirror.com"))
        assertTrue(looksLikeHttpUrl("https://hf-mirror.com"))
        assertTrue(looksLikeHttpUrl("  HTTPS://hf-mirror.com  "))
    }

    @Test
    fun `少写协议必须看得见`() {
        // `LlmModelCatalog.normalizeLlmModelBaseUrl` 故意不补协议（与语音识别那边同源）：
        // 悄悄补上会让「按记忆填错域名」一路绿灯，直到下载才 UnknownHostException，
        // 而那条错误看起来像网络问题、不像填错了源。
        assertFalse(LocalModelUiState(settings = TranslationSettings(localModelSource = "hf-mirror.com")).sourceLooksValid)
        assertFalse(LocalModelUiState(settings = TranslationSettings(localModelSource = "//hf-mirror.com")).sourceLooksValid)
    }

    @Test
    fun `下载源取的是下载源那一栏，不是翻译服务地址`() {
        // 两个字段合成一个的后果在这里最明显：用户给 DeepSeek 填的地址会变成
        // 模型下载源（下载必然 404），而镜像站又会被显示成服务商地址。
        val state = LocalModelUiState(
            settings = TranslationSettings(
                providerId = "deepseek",
                baseUrl = "https://api.deepseek.com",
                localModelSource = "",
            ),
        )

        assertEquals("", state.source)
        assertTrue("服务地址填得再对也不该影响这一栏", state.sourceLooksValid)
    }

    // ------------------------------------------------------------ 占了多大盘

    @Test
    fun `没下过是 0，下完了是清单里的体积`() {
        assertEquals(0L, occupiedBytesOf(qwen, LlmModelStatus.Absent))
        assertEquals(qwen.sizeBytes, occupiedBytesOf(qwen, LlmModelStatus.Ready))
    }

    @Test
    fun `下到一半时只说已经占掉的那些`() {
        // 「删除模型（释放约 345 MB）」里的那个数。下到一半时说总体积就是在骗人：
        // 文件还没落成正式名字，删掉回收的只有 `.part` 那点大小。
        // 反过来把它说成 0 更糟——用户会以为「删了也白删」，于是永远删不掉。
        assertEquals(
            12_345L,
            occupiedBytesOf(qwen, LlmModelStatus.Incomplete(presentBytes = 12_345L)),
        )
    }

    @Test
    fun `一个字节都没收到时也是 0，但仍然不是未下载`() {
        // 这一档在界面上必须与 `Absent` 分开：两者算出来的体积都是 0，
        // 但一个要显示「未下完（已收到 0%）· 从头开始」，一个显示「未下载 · 约 345 MB」。
        // 用体积去区分它们（`occupiedBytes == 0` ⇒ 未下载）就会把 `.part` 的存在抹掉，
        // 用户于是看不到「上次下到一半」这个提示。
        val empty = LlmModelStatus.Incomplete(presentBytes = 0L)

        assertEquals(0L, occupiedBytesOf(qwen, empty))
        assertFalse("体积一样不代表状态一样", empty == LlmModelStatus.Absent)
    }

    // ------------------------------------------------------------ 设置页状态

    @Test
    fun `还没读过盘时算未下载，而不是未知或已下载`() {
        val state = LocalModelUiState()

        // 首帧的空白不该看起来像错误（「未知」），更不该看起来像已经弄好了
        // （「已下载」会让用户直接去播放页翻译，然后拿到一个模型缺失的失败）。
        assertEquals(LlmModelStatus.Absent, state.statusOf(qwen))
        assertEquals(LlmModelStatus.Absent, state.status)
        assertEquals(0L, state.occupiedBytes)
        assertEquals(LlmModelCatalog.DEFAULT_ID, state.model.id)
    }

    @Test
    fun `状态表里有这一条时以它为准`() {
        val state = LocalModelUiState(statuses = mapOf(qwen.id to LlmModelStatus.Ready))

        assertEquals(LlmModelStatus.Ready, state.statusOf(qwen))
        assertEquals(LlmModelStatus.Ready, state.status)
        assertEquals(qwen.sizeBytes, state.occupiedBytes)
    }

    @Test
    fun `状态是按 id 查的，不会把别的模型的状态拿过来`() {
        // 现在清单里只有一条模型，但列表是照着「多条」写的（与语音识别页同构）。
        // 按位置查（`statuses.values.first()`）在加第二条的那天就会串味。
        val state = LocalModelUiState(statuses = mapOf("other-model" to LlmModelStatus.Ready))

        assertEquals(LlmModelStatus.Absent, state.statusOf(qwen))
        assertEquals(LlmModelStatus.Absent, state.status)
    }

    @Test
    fun `认不出来的模型 id 让界面回落到默认模型`() {
        // 降级安装、手改过配置、或者清单里换了模型 id：不能崩，也不能把一个
        // 用户从未见过的 id 显示成模型名（他会以为要自己去填一个叫这个的模型）。
        val state = LocalModelUiState(settings = TranslationSettings(model = "no-such-model"))

        assertEquals(LlmModelCatalog.DEFAULT_ID, state.model.id)
        assertEquals(qwen.name, state.model.name)
    }

    @Test
    fun `默认的 TranslationSettings 会把模型显示成清单里的名字`() {
        // `TranslationSettings()` 的 model 默认是**空串**（数据类的默认值），
        // 不是清单 id。空串同样要回落到默认模型，否则这一页第一次打开就是空名字。
        val state = LocalModelUiState()

        assertEquals("", state.settings.model)
        assertEquals(LlmModelCatalog.DEFAULT_ID, state.model.id)
    }

    // ------------------------------------------------------------ 入口页那一行

    @Test
    fun `入口页显示的模型跟着设置走`() {
        val entry = LocalModelEntryState(
            settings = TranslationSettings(model = LlmModelCatalog.DEFAULT_ID),
            status = LlmModelStatus.Incomplete(presentBytes = 1_024L),
        )

        // ⚠️ 这两个字段放在一个 data class 里、一次更新，就是因为分开成两个 Flow
        // 会在状态刷新到一半的那几帧里拼出「已下载」（说的是上一轮读到的磁盘状态）
        // 而 model 已经换成另一条。这里锁的是「模型名取设置里的」。
        assertEquals(LlmModelCatalog.DEFAULT_ID, entry.model.id)
        assertEquals(LlmModelStatus.Incomplete(presentBytes = 1_024L), entry.status)
    }

    @Test
    fun `入口页没给设置时用一个能显示的默认模型`() {
        // `LocalModelEntryState()` 在 Compose 预览与「还没读完设置」的首帧里会被用到。
        val entry = LocalModelEntryState()

        assertEquals(LlmModelCatalog.DEFAULT_ID, entry.model.id)
        assertEquals(LlmModelStatus.Absent, entry.status)
    }
}
