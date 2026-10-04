package com.multisuperplayer.core.llm

import com.multisuperplayer.core.model.text.MspText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模型落盘状态的测试。
 *
 * 这里钉住的是**三态判定**（[LlmModelStatus]）：三种状态对应的按钮完全不同
 * （下载 / 重新下载 / 删除），压成一个 `isDownloaded` 的话，下到 60% 被系统杀掉后
 * 用户会看到「未下载」，点下去从零开始——这条模型 345 MB，白花的就是几百 MB。
 *
 * 用一个**很小的假模型**（5 字节）而不是真模型：`statusOf` 只比大小，与内容无关，
 * 所以 5 字节的文件和 344 MB 的文件在这条判定上完全等价。
 */
class LlmModelLocatorTest {

    @get:Rule
    val temporary = TemporaryFolder()

    /**
     * 与真实接线一致：`DataModule` 里是 `LlmModelLocator(LlmModelLocator.rootOf(filesDir))`
     * ——传进去的就是 `llm` 目录本身，模型目录是它的子目录。
     * 构造时少套一层 `rootOf` 的话，模型会被写进私有目录根下（那里还有日志、缓存）。
     */
    private val locator: LlmModelLocator
        get() = LlmModelLocator(LlmModelLocator.rootOf(temporary.root))

    /** 假模型：声明 5 字节，真下载一个字节都不用。 */
    private fun model(id: String = "fake-model", size: Long = 5L): LlmModelInfo = LlmModelInfo(
        id = id,
        repo = "owner/name",
        fileName = "fake.litertlm",
        sizeBytes = size,
        sha256 = "0".repeat(64),
        name = MspText.Plain("假模型"),
        description = MspText.Plain("测试用，不下载任何东西"),
    )

    /** 按指定长度落一个正式文件（内容无所谓，长度才是判定依据）。 */
    private fun place(target: LlmModelLocator, model: LlmModelInfo, length: Long) {
        val file = target.fileOf(model)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(length.toInt()))
    }

    /** 落一个 `.part` 残留。 */
    private fun placePart(target: LlmModelLocator, model: LlmModelInfo, length: Long) {
        val file = target.partFileOf(model)
        file.parentFile?.mkdirs()
        file.writeBytes(ByteArray(length.toInt()))
    }

    // ------------------------------------------------------------ 三态

    @Test
    fun `根目录还不存在时是 Absent，且不抛`() {
        val model = model()

        // 首次安装、从没下过模型时就是这种情况：目录根本不存在。
        // 这里如果抛异常，设置页一打开就崩。
        assertFalse(LlmModelLocator.rootOf(temporary.root).exists())
        assertEquals(LlmModelStatus.Absent, locator.statusOf(model))
        assertFalse(locator.isReady(model))
    }

    @Test
    fun `目录建好了但一个字节都没有，仍然是 Absent`() {
        val model = model()

        locator.ensureDirectory(model)

        assertEquals(LlmModelStatus.Absent, locator.statusOf(model))
    }

    @Test
    fun `0 字节的正式文件不算存在`() {
        val model = model()

        // 下载刚开始就被杀死时留下的就是它。按「文件在不在」判定的话，
        // 用户会看到「已下载」，然后用的时候引擎加载失败。
        place(locator, model, length = 0L)

        assertEquals(LlmModelStatus.Absent, locator.statusOf(model))
    }

    @Test
    fun `大小正好对上才是 Ready`() {
        val model = model(size = 5L)

        place(locator, model, length = 5L)

        assertEquals(LlmModelStatus.Ready, locator.statusOf(model))
        assertTrue(locator.isReady(model))
    }

    @Test
    fun `大小对不上就是 Incomplete，报的是磁盘上真实的那一份`() {
        val model = model(size = 5L)

        // 同一个 id 换了版本（上游更新了量化文件）、或者存储损坏，都会落到这里。
        // 报「已收到 2 字节」是真话，报「未下载」会让用户以为没下过。
        place(locator, model, length = 2L)

        assertEquals(LlmModelStatus.Incomplete(presentBytes = 2L), locator.statusOf(model))
        assertFalse(locator.isReady(model))
    }

    @Test
    fun `正式文件与 part 残留取较大的那一份`() {
        val model = model(size = 5L)

        // 真实时序：上一版下完留下 2 字节的正式文件，这一版重新下载时刚写了 4 字节。
        // 只认其中一份都会少报（少报的后果是「重新下载」的提示数字对不上）。
        place(locator, model, length = 2L)
        placePart(locator, model, length = 4L)

        assertEquals(LlmModelStatus.Incomplete(presentBytes = 4L), locator.statusOf(model))
    }

    @Test
    fun `比清单还大的文件夹到清单大小，但仍然不是 Ready`() {
        val model = model(size = 5L)

        // 实测行为：`present = maxOf(part, 正式文件).coerceAtMost(sizeBytes)`，
        // 所以 6 字节的文件被夹成 5，判成 `Incomplete(5)`。
        //
        // 三个后果，都是刻意的：
        // 1) 状态**必须**留在 Incomplete，不能因为「有个文件在那儿」就升成 Ready——
        //    大小对不上的模型加载起来必然失败，那时用户在播放页看到的是引擎报错。
        // 2) 也不能降成 Absent：Absent 会把「删除」按钮一起收走，磁盘上那 6 字节
        //    （真实场景里是几百 MB 的旧版本文件）就再也没法从界面上清掉。
        // 3) 体积被夹住意味着界面会显示「已收到 100%」却仍然给「重新下载」。
        //    这句话不好听，但它指向的动作是对的：没有断点续传，只有整文件重下。
        place(locator, model, length = 6L)

        assertEquals(LlmModelStatus.Incomplete(presentBytes = 5L), locator.statusOf(model))
        assertFalse("大小对不上不能算已下载", locator.isReady(model))
        // 删除能力必须留着（第 2 条后果的断言）
        assertTrue(locator.remove(model))
    }

    @Test
    fun `正式文件大小对了就是 Ready，哪怕旁边还躺着一个 part 残留`() {
        val model = model(size = 5L)

        // 判定顺序是「先看正式文件是不是正好」，再去看 part。这个顺序不能反：
        // 用户「重新下载」中途退出会留下一个 part，而正式的旧文件仍然是好的
        // （大小一致 ⇒ 内容也就是对的，因为下载器是校验过哈希才改名的）。
        // 顺序反了的话，一个好的模型会被判成「未下载完成」，用户被迫白下 345 MB。
        place(locator, model, length = 5L)
        placePart(locator, model, length = 6L)

        assertEquals(LlmModelStatus.Ready, locator.statusOf(model))
        assertTrue(locator.isReady(model))
    }

    // ------------------------------------------------------------ 目录与文件的位置

    @Test
    fun `目录是 根目录 加 模型 id，正式文件与 part 都在它下面`() {
        val model = model(id = "qwen3-0.6b")

        val dir = locator.directoryOf(model)

        assertEquals(LlmModelLocator.rootOf(temporary.root), dir.parentFile)
        assertEquals(model.id, dir.name)
        assertEquals(dir, locator.fileOf(model).parentFile)
        assertEquals(model.fileName, locator.fileOf(model).name)
        assertEquals(
            model.fileName + LlmModelLocator.PART_SUFFIX,
            locator.partFileOf(model).name,
        )
    }

    @Test
    fun `statusOf 只读，不偷偷建目录`() {
        val model = model()

        // 设置页一打开就要查每条模型的状态。如果这里顺手 mkdirs，一次「只是看看」
        // 就在用户手机上留下一堆空目录，卸载前谁也清不掉。
        locator.statusOf(model)

        assertFalse(locator.directoryOf(model).exists())
    }

    // ------------------------------------------------------------ 删除与扫描

    @Test
    fun `删除会连 part 残留一起清掉`() {
        val model = model(size = 5L)
        place(locator, model, length = 5L)
        placePart(locator, model, length = 3L)
        assertTrue(locator.directoryOf(model).exists())

        assertTrue("第一次删应该真的删掉了什么", locator.remove(model))

        // 删目录而不是逐个文件：`.part` 的名字由本类决定，逐个删就等于有第三个地方
        // 需要知道这个名字。
        assertFalse(locator.directoryOf(model).exists())
        assertEquals(LlmModelStatus.Absent, locator.statusOf(model))
    }

    @Test
    fun `再删一次返回 false 而不是抛异常`() {
        val model = model()

        // 返回值用于「删除」按钮与提示语（「本地模型的文件本来就不在」），
        // 抛异常的话那个分支永远走不到，用户看到的是一次崩溃。
        assertFalse(locator.remove(model))
    }

    @Test
    fun `扫描只报清单里认识的模型 id`() {
        val known = model(id = LlmModelCatalog.DEFAULT_ID)
        locator.ensureDirectory(known)
        // 认不出的目录（旧版本留下的、手改过的）不能出现在扫描结果里：
        // 那样设置页会去渲染一个已经不在清单里的模型
        File(locator.directoryOf(known).parentFile, "no-such-model").mkdirs()

        assertEquals(setOf(LlmModelCatalog.DEFAULT_ID), locator.installedModelIds())
    }

    @Test
    fun `根目录不存在时扫描给空集合，不抛`() {
        assertTrue(locator.installedModelIds().isEmpty())
    }
}
