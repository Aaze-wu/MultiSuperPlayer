package com.multisuperplayer.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单与下载地址的测试。
 *
 * 这里的每一条错法都不会报错，只会**安静地给出一个错的地址**：
 * 少一个斜杠变双斜杠、把仓库名一起换掉、用户填的源被悄悄补上协议……
 * 症状统一是「下载失败」，而失败的地方看起来像网络问题。
 *
 * 另外还钉住清单本身的几条不变量（id 不能含路径分隔符、哈希必须是 64 位小写十六进制、
 * 体积必须大于 0）：这些数是从上游实测抄来的，抄错一位就得等到用户手机上才暴露。
 */
class LlmModelCatalogTest {

    // ------------------------------------------------------------ 下载源归一化

    @Test
    fun `空值一律回落到默认镜像站`() {
        assertEquals(DEFAULT_LLM_MODEL_BASE_URL, normalizeLlmModelBaseUrl(null))
        assertEquals(DEFAULT_LLM_MODEL_BASE_URL, normalizeLlmModelBaseUrl(""))
        assertEquals(DEFAULT_LLM_MODEL_BASE_URL, normalizeLlmModelBaseUrl("   "))
        // 用户把地址清成 `/` 时，去完斜杠就成了空串。不兜底的话拼出来的是
        // `/litert-community/...`，报一个 UnknownHostException 级别的底层错误，
        // 看着像网络问题，其实是设置里那一个斜杠。
        assertEquals(DEFAULT_LLM_MODEL_BASE_URL, normalizeLlmModelBaseUrl("/"))
        assertEquals(DEFAULT_LLM_MODEL_BASE_URL, normalizeLlmModelBaseUrl("  //  "))
    }

    @Test
    fun `首尾空白与末尾斜杠都去掉，但中间不动`() {
        assertEquals("https://example.com", normalizeLlmModelBaseUrl("  https://example.com  "))
        assertEquals("https://example.com", normalizeLlmModelBaseUrl("https://example.com/"))
        assertEquals("https://example.com", normalizeLlmModelBaseUrl("https://example.com///"))
        // 带路径的镜像站：末尾斜杠去掉，路径里的斜杠保留
        assertEquals("https://example.com/hf", normalizeLlmModelBaseUrl(" https://example.com/hf/ "))
    }

    @Test
    fun `少写协议不补，因为悄悄补上会让错的域名一路绿灯`() {
        // 「hf-mirror.com」这种值会一路拼到下载时才失败，而那时报的是解析不了主机。
        // 设置页有一条「必须以 http:// 或 https:// 开头」的校验在那里拦它，
        // 归一化函数**不能**替用户猜——猜错了就是「看起来能用」。
        assertEquals("hf-mirror.com", normalizeLlmModelBaseUrl("hf-mirror.com"))
    }

    // ------------------------------------------------------------ 下载地址拼接

    @Test
    fun `下载地址是 源 加 仓库 加 resolve main 加 文件名`() {
        val model = LlmModelCatalog.byId(LlmModelCatalog.DEFAULT_ID)

        assertEquals(
            "https://hf-mirror.com/${model.repo}/resolve/main/${model.fileName}",
            model.downloadUrl(null),
        )
    }

    @Test
    fun `换源只换源，仓库名与文件名不受影响`() {
        val model = LlmModelCatalog.byId(LlmModelCatalog.DEFAULT_ID)

        val custom = model.downloadUrl("https://mirror.example.com/hf/")

        // 把源当成可配置的整体替换掉是最容易犯的错：那样一来仓库名也会跟着变，
        // 而两条模型的仓库名不一样（将来还会有第二条），错法就藏不住了。
        assertEquals(
            "https://mirror.example.com/hf/${model.repo}/resolve/main/${model.fileName}",
            custom,
        )
        assertTrue("仓库名必须原样出现", custom.contains(model.repo))
    }

    @Test
    fun `源为空串时拼的是默认站，不是斜杠开头的相对路径`() {
        val model = LlmModelCatalog.byId(LlmModelCatalog.DEFAULT_ID)

        val url = model.downloadUrl("")

        assertTrue("实际是 $url", url.startsWith("https://"))
        assertFalse("不能以斜杠开头", url.startsWith("/"))
    }

    // ------------------------------------------------------------ 按 id 查

    @Test
    fun `认不出来的 id 回落到默认模型`() {
        // 降级安装、手改过配置、将来删掉的模型：都不能崩，也不能显示空名字
        assertEquals(LlmModelCatalog.DEFAULT_ID, LlmModelCatalog.byId("no-such-model").id)
        assertEquals(LlmModelCatalog.DEFAULT_ID, LlmModelCatalog.byId(null).id)
        assertEquals(LlmModelCatalog.DEFAULT_ID, LlmModelCatalog.byId("").id)
    }

    @Test
    fun `id 前后的空白不算数`() {
        // 设置里存的是字符串，而用户可能从别处复制来一个带空格的（包括不可见的那种）
        assertEquals(
            LlmModelCatalog.DEFAULT_ID,
            LlmModelCatalog.byId("  ${LlmModelCatalog.DEFAULT_ID}  ").id,
        )
    }

    @Test
    fun `find 与 byId 的区别就是认不出时给 null 还是给默认值`() {
        assertEquals(LlmModelCatalog.DEFAULT_ID, LlmModelCatalog.find(LlmModelCatalog.DEFAULT_ID)?.id)
        // 「分得出没有」这件事有用：`find` 的调用方要自己决定怎么兜底
        assertNull(LlmModelCatalog.find("no-such-model"))
        assertNull(LlmModelCatalog.find(null))
        assertNull(LlmModelCatalog.find("   "))
    }

    // ------------------------------------------------------------ 清单本身

    @Test
    fun `默认模型真的在清单里，而且清单不为空`() {
        assertTrue(LlmModelCatalog.models.isNotEmpty())
        assertTrue(
            "DEFAULT_ID 必须指向清单里的一条",
            LlmModelCatalog.models.any { it.id == LlmModelCatalog.DEFAULT_ID },
        )
    }

    @Test
    fun `高质量档的 id 也指向清单里的一条`() {
        // `byId` 认不出来时静默回落到默认那条，所以这个常量写错一个字符不会报任何错，
        // 只会让用户选好的混元模型下一次打开设置时变回 0.6B——一个「我的设置自己变了」
        // 且完全无从下手的问题。
        assertTrue(
            "HY_MT2_18B_ID 必须指向清单里的一条",
            LlmModelCatalog.models.any { it.id == LlmModelCatalog.HY_MT2_18B_ID },
        )
        assertFalse("轻量那条与高质量那条不能是同一个 id", LlmModelCatalog.HY_MT2_18B_ID == LlmModelCatalog.QWEN3_06B_ID)
    }

    @Test
    fun `两条模型不能共用同一个下载文件`() {
        // 复制一条模型条目最容易漏的就是这几行：repo / fileName / sha256 还是上一条的。
        // 症状是「下完第一条之后第二条直接显示已下载」（磁盘上那个文件体积与哈希都对得上），
        // 于是用户点下载什么也不会发生，而界面看起来一切正常。
        fun distinctCount(field: (LlmModelInfo) -> String): Int =
            LlmModelCatalog.models.map(field).toSet().size

        assertEquals("repo 重复", LlmModelCatalog.models.size, distinctCount { it.repo })
        assertEquals("fileName 重复", LlmModelCatalog.models.size, distinctCount { it.fileName })
        assertEquals("sha256 重复", LlmModelCatalog.models.size, distinctCount { it.sha256 })
    }

    @Test
    fun `内存需求要么不写，要么是个正数`() {
        // `0` 是这里最危险的取值：`LlmMemoryAdvice.isRisky` 会把它当成「需要零内存」，
        // 于是「本机内存可能不够」这句提示永远不会出现——一个假的安全结论，
        // 而代价是用户白下 1.82 GB。
        LlmModelCatalog.models.forEach { model ->
            val peak = model.peakMemoryBytes

            assertTrue("${model.id} 的 peakMemoryBytes 是 $peak", peak == null || peak > 0L)
        }
    }

    @Test
    fun `id 是目录名，所以不能含路径分隔符或空白`() {
        LlmModelCatalog.models.forEach { model ->
            assertFalse("${model.id} 含路径分隔符", model.id.contains('/') || model.id.contains('\\'))
            assertEquals("${model.id} 首尾有空白", model.id, model.id.trim())
            // `..` / `.` 这种名字会让模型目录跑到私有目录外面去
            assertFalse("${model.id} 是相对路径段", model.id == "." || model.id == "..")
        }
    }

    @Test
    fun `哈希必须是 64 位小写十六进制，体积必须大于零`() {
        LlmModelCatalog.models.forEach { model ->
            assertTrue("${model.id} 的体积是 ${model.sizeBytes}", model.sizeBytes > 0L)
            assertEquals("${model.id} 的哈希长度不对", 64, model.sha256.length)
            assertTrue(
                "${model.id} 的哈希里有非小写十六进制字符：${model.sha256}",
                model.sha256.all { it in '0'..'9' || it in 'a'..'f' },
            )
            // 文件名要能直接当本地文件名用：带斜杠说明仓库里有子目录，
            // 那时 `fileOf` 会去一个没建过的目录里写
            assertFalse("${model.id} 的文件名含路径分隔符", model.fileName.contains('/'))
        }
    }

    @Test
    fun `清单里没有重复的 id`() {
        val ids = LlmModelCatalog.models.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }
}
