package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单与下载地址的纯逻辑测试。
 *
 * 这一组测试关心的不是覆盖率，而是几个**错了也不会报错**的地方：
 *
 * - 下载地址里 `repo` 被一起换成可配置项（最经典的错法），症状是「换镜像之后 404」；
 * - 清单里的 `sizeBytes` 敲错一位，症状是**永远下不完**（下完的文件大小对不上声明的值,
 *   于是 `AsrModelLocator.statusOf` 永远说「缺这个文件」，重新下载也永远不会成功）；
 * - 清单里漏一个角色（流式模型漏 joiner），症状是引擎构造时在原生层崩，堆栈里没有信息。
 *
 * 所以这里既测逻辑，也把**实测过的字节数**钉住：那几个数是从上游仓库真实下载下来量过的
 * （见 roadmap 的 v0.6 记录），在测试里独立写一遍是唯一能发现「敲错一位」的办法。
 */
class AsrModelCatalogTest {

    // ------------------------------------------------------------ normalizeModelBaseUrl

    @Test
    fun `空值、空串与纯空白回落到默认镜像`() {
        assertEquals(DEFAULT_MODEL_BASE_URL, normalizeModelBaseUrl(null))
        assertEquals(DEFAULT_MODEL_BASE_URL, normalizeModelBaseUrl(""))
        assertEquals(DEFAULT_MODEL_BASE_URL, normalizeModelBaseUrl("   "))
        // 「只有斜杠」也得算空：归一化的顺序是先判空、再 trimEnd，
        // 如果反过来，一个 `/` 会归一化成空串，拼出 `/repo/resolve/main/...`
        // 这种没有主机的地址，报出来的错看着像网络问题
        assertEquals(DEFAULT_MODEL_BASE_URL, normalizeModelBaseUrl("/"))
        assertEquals(DEFAULT_MODEL_BASE_URL, normalizeModelBaseUrl("  //  "))
    }

    @Test
    fun `去掉首尾空白与末尾斜杠`() {
        assertEquals("https://hf-mirror.com", normalizeModelBaseUrl("  https://hf-mirror.com/  "))
        // 多敲一个斜杠是最常见的手滑，不能让 `<base>//<repo>/...` 这种地址流出去
        assertEquals("https://hf-mirror.com", normalizeModelBaseUrl("https://hf-mirror.com//"))
        // 路径中间的斜杠是地址的一部分，不能动：有的镜像把模型挂在子路径下
        assertEquals("https://example.com/mirror", normalizeModelBaseUrl("https://example.com/mirror/"))
    }

    @Test
    fun `不补协议`() {
        // 「少写 https://」必须是**看得见**的错误（设置页会标红）：
        // 悄悄补上会让用户按记忆填一个错域名时一路绿灯，直到下载才 UnknownHostException。
        assertEquals("hf-mirror.com", normalizeModelBaseUrl("hf-mirror.com"))
        assertEquals("ftp://example.com", normalizeModelBaseUrl("ftp://example.com"))
    }

    // ------------------------------------------------------------ downloadUrl

    @Test
    fun `下载地址的构成是 base 加 repo 加 resolve-main 加路径`() {
        val model = AsrModelCatalog.byId(AsrModelCatalog.PARA_FORMER_ID)
        val file = model.file(AsrFileRole.MODEL)

        assertEquals(
            "https://hf-mirror.com/${model.repo}/resolve/main/${file.path}",
            model.downloadUrl(file, "https://hf-mirror.com"),
        )
        // 空 base（用户没填）走默认镜像，而不是拼出 `/repo/...` 这种没有主机的地址
        assertEquals(
            "https://hf-mirror.com/${model.repo}/resolve/main/${file.path}",
            model.downloadUrl(file, null),
        )
        // 末尾斜杠不能让地址里出现 `//` —— 有的镜像会把它当成另一个路径
        assertEquals(
            model.downloadUrl(file, "https://hf-mirror.com"),
            model.downloadUrl(file, "https://hf-mirror.com/"),
        )
    }

    @Test
    fun `换下载源只换主机，仓库名不受影响`() {
        val model = AsrModelCatalog.byId(AsrModelCatalog.ZIPFORMER_ID)
        val file = model.file(AsrFileRole.ENCODER)

        val official = model.downloadUrl(file, "https://huggingface.co")
        assertEquals("https://huggingface.co/${model.repo}/resolve/main/${file.path}", official)

        // 两条地址除了主机之外必须逐字相同。把 repo 也做成可配置项的话，
        // 换镜像时仓库名会被一起换掉，症状是「镜像站 404」。
        assertEquals(official, model.downloadUrl(file, "https://hf-mirror.com").replace("hf-mirror.com", "huggingface.co"))
    }

    // ------------------------------------------------------------ 按 id 查

    @Test
    fun `找不到时 byId 回落默认，find 返回 null`() {
        val default = AsrModelCatalog.byId(AsrModelCatalog.DEFAULT_ID)

        assertSame(default, AsrModelCatalog.byId(null))
        assertSame(default, AsrModelCatalog.byId(""))
        assertSame(default, AsrModelCatalog.byId("   "))
        assertSame(default, AsrModelCatalog.byId("早就删掉的老 id"))

        assertNull(AsrModelCatalog.find(null))
        assertNull(AsrModelCatalog.find(""))
        assertNull(AsrModelCatalog.find("   "))
        assertNull(AsrModelCatalog.find("早就删掉的老 id"))
        assertNotNull(AsrModelCatalog.find(AsrModelCatalog.DEFAULT_ID))
    }

    @Test
    fun `按 id 查之前会去空白`() {
        // 设置里存的值是从输入框来的，历史上出现过带空白的值
        assertSame(
            AsrModelCatalog.byId(AsrModelCatalog.ZIPFORMER_ID),
            AsrModelCatalog.byId("  ${AsrModelCatalog.ZIPFORMER_ID}  "),
        )
        assertNotNull(AsrModelCatalog.find(" ${AsrModelCatalog.ZIPFORMER_ID} "))
    }

    // ------------------------------------------------------------ 清单自身的不变量

    @Test
    fun `id 唯一、非空、且不含路径分隔符`() {
        // id 会直接当目录名（`filesDir/asr/<id>`），含分隔符就会写到别的目录里去
        val ids = AsrModelCatalog.models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { id ->
            assertTrue("id 不能是空白：'$id'", id.isNotBlank())
            assertTrue("id 不能含路径分隔符：'$id'", !id.contains('/') && !id.contains('\\'))
            assertEquals("id 不该有首尾空白：'$id'", id, id.trim())
        }
        assertTrue("默认模型必须在清单里", AsrModelCatalog.DEFAULT_ID in ids)
    }

    @Test
    fun `每条模型的文件路径不重复，sha256 是小写十六进制`() {
        AsrModelCatalog.models.forEach { model ->
            val paths = model.files.map { it.path }
            assertEquals("${model.id} 的文件路径重复", paths.size, paths.toSet().size)
            model.files.forEach { file ->
                assertTrue("${model.id}/${file.path} 的体积必须是正数", file.sizeBytes > 0L)
                assertEquals("${model.id}/${file.path} 的 sha256 长度不对", 64, file.sha256.length)
                assertTrue(
                    "${model.id}/${file.path} 的 sha256 必须是小写十六进制",
                    file.sha256.all { it in '0'..'9' || it in 'a'..'f' },
                )
            }
        }
    }

    @Test
    fun `总体积就是各文件之和`() {
        AsrModelCatalog.models.forEach { model ->
            assertEquals(model.files.sumOf { it.sizeBytes }, model.totalBytes)
        }
    }

    @Test
    fun `每条模型的清单里都齐了它那套引擎需要的角色`() {
        AsrModelCatalog.models.forEach { model ->
            // 缺角色是编程错误，`file()` 会抛；这里就是让它在测试里抛，而不是在手机上
            model.file(AsrFileRole.TOKENS)
            when (model.engine) {
                AsrEngine.OFFLINE -> model.file(AsrFileRole.MODEL)
                AsrEngine.STREAMING -> {
                    model.file(AsrFileRole.ENCODER)
                    model.file(AsrFileRole.DECODER)
                    model.file(AsrFileRole.JOINER)
                }
            }
        }
    }

    @Test
    fun `缺角色时的报错里带着缺的是哪个角色`() {
        val broken = AsrModelInfo(
            id = "broken",
            engine = AsrEngine.OFFLINE,
            repo = "owner/name",
            name = MspText.Plain("缺零件的模型"),
            description = MspText.Plain("测试用"),
            languageTag = "zh",
            files = listOf(AsrModelFile(AsrFileRole.TOKENS, "tokens.txt", 1L, "0".repeat(64))),
        )

        val error = assertThrows(IllegalStateException::class.java) { broken.file(AsrFileRole.MODEL) }
        assertTrue(
            "报错要说清缺的是哪个角色，实际是：${error.message}",
            error.message.orEmpty().contains(AsrFileRole.MODEL.name),
        )
    }

    @Test
    fun `声明的体积与实测下载到的字节数一致`() {
        // 这几个数是把模型真下载下来量出来的（v0.6 可行性验证时记录在 roadmap 里）。
        // 在这里独立再写一遍，是为了让「清单里敲错一位」在单测里就红：
        // 那种错法的症状是**永远下不完**——文件下完了，但大小对不上声明的值。
        val expected = mapOf(
            AsrModelCatalog.PARA_FORMER_ID to listOf(
                "model.int8.onnx" to 81_828_675L,
                "tokens.txt" to 75_352L,
            ),
            AsrModelCatalog.ZIPFORMER_ID to listOf(
                "encoder-epoch-99-avg-1.int8.onnx" to 181_895_032L,
                "decoder-epoch-99-avg-1.onnx" to 13_876_452L,
                "joiner-epoch-99-avg-1.int8.onnx" to 3_228_404L,
                "tokens.txt" to 56_317L,
            ),
        )

        expected.forEach { (id, files) ->
            val model = AsrModelCatalog.byId(id)
            files.forEach { (path, size) ->
                val declared = model.files.firstOrNull { it.path == path }
                assertNotNull("$id 的清单里少了 $path", declared)
                assertEquals("$id/$path 的体积写错了", size, declared!!.sizeBytes)
            }
            assertEquals("$id 的文件条数不对", files.size, model.files.size)
        }
    }
}
