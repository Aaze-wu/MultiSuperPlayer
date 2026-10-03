package com.multisuperplayer.feature.settings

import com.multisuperplayer.core.asr.AsrModelCatalog
import com.multisuperplayer.core.asr.AsrModelStatus
import com.multisuperplayer.core.data.settings.AsrSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音识别设置页里那几处纯判定。
 *
 * 这里没有 ViewModel 的用例：这一页的两个 ViewModel 都要 `DataStore`、下载器、
 * 引擎一堆依赖，而真正容易写错的是三个纯函数级的判定——
 * 「地址看起来对不对」「这条模型占了多少盘」「没读过盘时算是哪种状态」。
 * 它们在真机上的错法分别是：一句话都不说就让下载失败、删除对话框里的体积撒谎、
 * 首帧显示成「未知」。
 */
class AsrSettingsLogicTest {

    private val paraformer = AsrModelCatalog.byId(AsrModelCatalog.PARA_FORMER_ID)
    private val zipformer = AsrModelCatalog.byId(AsrModelCatalog.ZIPFORMER_ID)

    // ------------------------------------------------------------ 下载源看起来对不对

    @Test
    fun `空地址算合格，因为空就是用默认源`() {
        // 这条容易写反：把「没填」当成错误会让默认状态下的设置页一直标着红
        assertTrue(looksLikeHttpUrl(null))
        assertTrue(looksLikeHttpUrl(""))
        assertTrue(looksLikeHttpUrl("   "))
    }

    @Test
    fun `http 与 https 都认，大小写和首尾空白不影响`() {
        assertTrue(looksLikeHttpUrl("http://example.com"))
        assertTrue(looksLikeHttpUrl("https://example.com"))
        assertTrue(looksLikeHttpUrl("HTTPS://example.com"))
        assertTrue(looksLikeHttpUrl("  https://example.com  "))
    }

    @Test
    fun `少写协议必须看得见`() {
        // `normalizeModelBaseUrl` 故意不补协议：悄悄补上会让「按记忆填错域名」
        // 一路绿灯，直到下载才 UnknownHostException。这里是那条约定在界面侧的落点。
        assertFalse(looksLikeHttpUrl("hf-mirror.com"))
        assertFalse(looksLikeHttpUrl("example.com/hf"))
        assertFalse(looksLikeHttpUrl("/"))
        assertFalse(looksLikeHttpUrl("//example.com"))
    }

    @Test
    fun `别的协议不认`() {
        assertFalse(looksLikeHttpUrl("ftp://example.com"))
        assertFalse(looksLikeHttpUrl("file:///sdcard"))
        // 只检查前缀，不解析 URL——「拼错的域名只有下的时候才知道」
        assertTrue(looksLikeHttpUrl("https://"))
    }

    // ------------------------------------------------------------ 占了多大盘

    @Test
    fun `没下过是 0，下完了是总体积`() {
        assertEquals(0L, AsrModelStatus.Absent.occupiedBytesOf(paraformer))
        assertEquals(paraformer.totalBytes, AsrModelStatus.Ready.occupiedBytesOf(paraformer))
    }

    @Test
    fun `下了一半时只说已经拿到的有效字节`() {
        // 「删除模型（释放 190 MB）」里的那个数。下了一半时说总体积就是在骗人：
        // 那部分根本释放不出来。`presentBytes` 只统计大小对得上的文件，
        // 所以它可以直接当「实际占盘」用。
        val partial = AsrModelStatus.Partial(presentBytes = 1_024L, missing = paraformer.files)

        assertEquals(1_024L, partial.occupiedBytesOf(paraformer))
    }

    // ------------------------------------------------------------ 设置页状态

    @Test
    fun `还没读过盘时算未下载，而不是未知`() {
        val state = AsrSettingsUiState()

        // 首帧的空白不该看起来像错误：兜底成「未下载」是用户熟悉的那种状态
        assertEquals(AsrModelStatus.Absent, state.statusOf(paraformer))
        assertEquals(AsrModelStatus.Absent, state.status)
        assertEquals(0L, state.occupiedBytes)
        assertEquals(AsrModelCatalog.DEFAULT_ID, state.model.id)
    }

    @Test
    fun `每条模型各查各的状态，不会串味`() {
        val state = AsrSettingsUiState(statuses = mapOf(zipformer.id to AsrModelStatus.Ready))

        // 列表两条都要显示，所以状态是「按 id 查」的；选中的那条没下过时
        // 不能把另一条的状态拿过来
        assertEquals(AsrModelStatus.Ready, state.statusOf(zipformer))
        assertEquals(AsrModelStatus.Absent, state.statusOf(paraformer))
        assertEquals(AsrModelStatus.Absent, state.status)
    }

    @Test
    fun `选中哪条模型看设置，不看状态表`() {
        val state = AsrSettingsUiState(
            settings = AsrSettings(storedModelId = AsrModelCatalog.ZIPFORMER_ID),
            statuses = mapOf(AsrModelCatalog.ZIPFORMER_ID to AsrModelStatus.Ready),
        )

        assertEquals(AsrModelCatalog.ZIPFORMER_ID, state.model.id)
        assertEquals(AsrModelStatus.Ready, state.status)
        assertEquals(zipformer.totalBytes, state.occupiedBytes)
    }

    @Test
    fun `认不出来的模型 id 让界面回落到默认模型`() {
        // 降级安装、手改过配置：不能崩，也不能显示一个空名字
        val state = AsrSettingsUiState(settings = AsrSettings(storedModelId = "no-such-model"))

        assertEquals(AsrModelCatalog.DEFAULT_ID, state.model.id)
    }

    @Test
    fun `地址那一栏的标红只看盘上的值`() {
        // 空值不标红；写错前缀才标红
        assertTrue(AsrSettingsUiState().sourceLooksValid)
        assertTrue(AsrSettingsUiState(settings = AsrSettings(storedBaseUrl = "https://example.com")).sourceLooksValid)
        assertFalse(AsrSettingsUiState(settings = AsrSettings(storedBaseUrl = "example.com")).sourceLooksValid)
    }

    // ------------------------------------------------------------ 入口页那一行

    @Test
    fun `入口页那两个数来自同一个状态对象`() {
        val fresh = AsrEntryState()

        assertEquals(AsrModelCatalog.DEFAULT_ID, fresh.model.id)
        assertEquals(AsrModelStatus.Absent, fresh.status)
    }

    @Test
    fun `入口页显示的模型跟着设置走`() {
        val entry = AsrEntryState(
            settings = AsrSettings(storedModelId = AsrModelCatalog.ZIPFORMER_ID),
            status = AsrModelStatus.Ready,
        )

        // ⚠️ 这两个字段之所以放在一个 data class 里、一次更新，就是因为分开成两个
        // Flow 会在换完模型的那几帧里拼出「中英双语 · 已下载」——而那个「已下载」
        // 说的是上一条模型（paraformer 确实下好了）。这里锁的是「模型名取设置里的」。
        assertEquals(AsrModelCatalog.ZIPFORMER_ID, entry.model.id)
        assertEquals(AsrModelStatus.Ready, entry.status)
    }
}
