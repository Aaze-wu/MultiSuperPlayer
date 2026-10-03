package com.multisuperplayer.core.asr

import com.multisuperplayer.core.common.text.MspText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模型落盘状态的测试。
 *
 * 这里钉住的是**三态判定**（[AsrModelStatus]）：`Absent` / `Partial` / `Ready`
 * 对应的按钮是「下载」/「继续下载」/（不显示下载），压成布尔值的话，下到一半被系统
 * 杀掉之后用户会看到「未下载」并从零开始，那几十上百 MB 的流量白花。
 *
 * 用一个**很小的假模型**（3 字节、2 字节）而不是真模型：`statusOf` 只比大小，
 * 与模型内容无关，所以 3 字节的文件和 181 MB 的文件在这条判定上是等价的。
 */
class AsrModelLocatorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /**
     * 和真实接线方式一致：`DataModule` 里是
     * `AsrModelLocator(AsrModelLocator.rootOf(filesDir))` —— 传进去的就是 `asr` 目录本身，
     * 模型目录是它的子目录。（构造时少套一层 `rootOf` 的话，模型会被写进私有目录根下。）
     */
    private val locator: AsrModelLocator get() = AsrModelLocator(AsrModelLocator.rootOf(temporary.root))

    /** 两个文件的假模型：`tokens.txt` 3 字节，`model.onnx` 2 字节。 */
    private fun model(
        id: String = "fake-model",
        tokens: Long = 3L,
        weights: Long = 2L,
    ): AsrModelInfo = AsrModelInfo(
        id = id,
        engine = AsrEngine.OFFLINE,
        repo = "owner/name",
        name = MspText.Plain("假模型"),
        description = MspText.Plain("测试用，不下载任何东西"),
        languageTag = "zh",
        files = listOf(
            AsrModelFile(AsrFileRole.TOKENS, "tokens.txt", tokens, "0".repeat(64)),
            AsrModelFile(AsrFileRole.MODEL, "weights.onnx", weights, "0".repeat(64)),
        ),
    )

    /** 按声明的大小造出文件（内容无所谓，长度才是判定依据）。 */
    private fun place(target: AsrModelLocator, model: AsrModelInfo, file: AsrModelFile, length: Long = file.sizeBytes) {
        val local = target.fileOf(model, file)
        local.parentFile?.mkdirs()
        local.writeBytes(ByteArray(length.toInt()))
    }

    // ------------------------------------------------------------ 三态

    @Test
    fun `根目录还不存在时是 Absent，且不抛`() {
        val model = model()
        // 首次安装、从没下过模型时就是这种情况：目录根本不存在，
        // 这里如果抛异常，设置页一打开就崩。
        assertFalse(AsrModelLocator.rootOf(temporary.root).exists())
        assertEquals(AsrModelStatus.Absent, locator.statusOf(model))
        assertFalse(locator.isReady(model))
    }

    @Test
    fun `目录建好了但一个文件都没有，仍然是 Absent`() {
        val model = model()
        locator.ensureDirectory(model)
        assertEquals(AsrModelStatus.Absent, locator.statusOf(model))
    }

    @Test
    fun `所有文件大小都对得上才是 Ready`() {
        val model = model()
        model.files.forEach { place(locator, model, it) }

        assertEquals(AsrModelStatus.Ready, locator.statusOf(model))
        assertTrue(locator.isReady(model))
        assertTrue(locator.missingFiles(model).isEmpty())
    }

    @Test
    fun `下了一半是 Partial，且 presentBytes 只算大小对得上的文件`() {
        val model = model()
        place(locator, model, model.file(AsrFileRole.TOKENS))

        val status = locator.statusOf(model)
        assertTrue("应该是 Partial，实际是 $status", status is AsrModelStatus.Partial)
        status as AsrModelStatus.Partial
        assertEquals(3L, status.presentBytes)
        assertEquals(listOf("weights.onnx"), status.missing.map { it.path })
    }

    @Test
    fun `0 字节的文件不算存在`() {
        val model = model()
        // `createNewFile` 造出的 0 字节文件：下载刚开始就被杀死时留下的就是它。
        // 如果按「文件在不在」判定，用户会看到「已下载」，点进去引擎加载失败。
        locator.fileOf(model, model.file(AsrFileRole.TOKENS)).apply {
            parentFile?.mkdirs()
            createNewFile()
        }

        assertEquals(AsrModelStatus.Absent, locator.statusOf(model))
    }

    @Test
    fun `长度比声明多也算缺`() {
        val model = model()
        // 比声明的多（比如上次写在同一个文件里的残渣）同样不能用：
        // 哈希对不上，引擎加载时会崩
        place(locator, model, model.file(AsrFileRole.TOKENS), length = 4L)
        place(locator, model, model.file(AsrFileRole.MODEL))

        val status = locator.statusOf(model)
        assertTrue("应该是 Partial，实际是 $status", status is AsrModelStatus.Partial)
        assertEquals(2L, (status as AsrModelStatus.Partial).presentBytes)
    }

    @Test
    fun `文件在但是个目录时算缺`() {
        val model = model()
        // 极端情况：同名目录占着位置。`isFile` 必须挡住它，否则 `length()` 会返回
        // 目录项的大小，有可能恰好等于声明的字节数
        locator.fileOf(model, model.file(AsrFileRole.TOKENS)).mkdirs()
        place(locator, model, model.file(AsrFileRole.MODEL))

        assertEquals(AsrModelStatus.Partial(2L, listOf(model.file(AsrFileRole.TOKENS))), locator.statusOf(model))
    }

    @Test
    fun `另一条模型的文件不影响这一条`() {
        val first = model(id = "fake-a")
        val second = model(id = "fake-b")
        first.files.forEach { place(locator, first, it) }

        assertEquals(AsrModelStatus.Ready, locator.statusOf(first))
        assertEquals(AsrModelStatus.Absent, locator.statusOf(second))
    }

    // ------------------------------------------------------------ 路径

    @Test
    fun `路径是 root 加模型 id 加清单里的相对路径`() {
        val model = model(id = "some-model")
        assertEquals("asr", AsrModelLocator.DIR_NAME)
        assertEquals(File(File(temporary.root, "asr"), "some-model"), locator.directoryOf(model))
        assertEquals(
            File(File(File(temporary.root, "asr"), "some-model"), "tokens.txt"),
            locator.fileOf(model, model.file(AsrFileRole.TOKENS)),
        )
    }

    @Test
    fun `rootOf 把 asr 目录挂在 filesDir 下面`() {
        assertEquals(File(temporary.root, AsrModelLocator.DIR_NAME), AsrModelLocator.rootOf(temporary.root))
    }

    @Test
    fun `ensureDirectory 会建出模型目录（含父目录）`() {
        val model = model()
        val directory = locator.ensureDirectory(model)
        assertTrue(directory.isDirectory)
        // 重复调用不能抛
        assertTrue(locator.ensureDirectory(model).isDirectory)
    }

    // ------------------------------------------------------------ 删除

    @Test
    fun `remove 删掉整条模型并返回 true，第二次返回 false`() {
        val model = model()
        model.files.forEach { place(locator, model, it) }

        assertTrue(locator.remove(model))
        assertFalse(locator.directoryOf(model).exists())
        assertEquals(AsrModelStatus.Absent, locator.statusOf(model))
        // 已经没什么可删的时候必须返回 false：界面靠它区分「已删除」和「本来就没有」
        assertFalse(locator.remove(model))
    }

    @Test
    fun `remove 不碰别的模型目录`() {
        val first = model(id = "fake-a")
        val second = model(id = "fake-b")
        first.files.forEach { place(locator, first, it) }
        second.files.forEach { place(locator, second, it) }

        assertTrue(locator.remove(first))
        assertEquals(AsrModelStatus.Ready, locator.statusOf(second))
    }

    // ------------------------------------------------------------ 扫描

    @Test
    fun `installedModelIds 只认清单里的 id，忽略文件与未知目录`() {
        assertTrue("这条测试假定清单里至少有一条模型", AsrModelCatalog.models.isNotEmpty())

        AsrModelCatalog.models.forEach { locator.ensureDirectory(it) }
        File(temporary.root, AsrModelLocator.DIR_NAME).apply {
            // 未知目录：老版本留下的、或者用户手动塞的
            File(this, "早期版本留下的目录").mkdirs()
            // 同名文件：`listFiles()` 里混着文件，必须按 isDirectory 过滤
            File(this, "readme.txt").writeText("x")
        }

        val ids = locator.installedModelIds()
        assertEquals(AsrModelCatalog.models.map { it.id }.toSet(), ids)
        assertFalse("未知目录不该被当成已安装的模型", "早期版本留下的目录" in ids)
    }

    @Test
    fun `根目录不存在时 installedModelIds 返回空集`() {
        assertTrue(locator.installedModelIds().isEmpty())
    }
}
