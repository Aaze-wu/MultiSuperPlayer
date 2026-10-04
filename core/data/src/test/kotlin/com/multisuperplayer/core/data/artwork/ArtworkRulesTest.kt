package com.multisuperplayer.core.data.artwork

import com.multisuperplayer.core.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 封面取图的推导规则。
 *
 * 这一类计算的失败方式全部是**安静的**：抽错时间点得到一张黑屏、缩放算错得到
 * 一张拉变形的图、缓存键撞了导致两个文件共用一张封面——没有一处会抛异常。
 * 所以断言落在可验证的性质上（单调、不越界、保比例、不撞键、能被独立复算），
 * 而不是把算好的结果抄进来：抄下来的数字只能证明「和上次一样」。
 *
 * 唯一例外是 [键只取决于字节：用 JVM 规范的散列独立复算一遍] 里那个复算用的
 * 散列——那不是抄结果，是按 Java 规范独立实现第二份再对答案。
 *
 * 末尾一段的磁盘缓存规则跑在真的临时目录上（`java.io.File`，不需要 Context，
 * 也就不需要 Robolectric）：它们决定的是「磁盘上这个文件能不能信」，
 * 而这件事错了同样是安静的——认下一张半截图，以后永远解不开、永远重试。
 */
class ArtworkRulesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    // ARGB。用位运算手写而不是 Color.rgb()：后者在单元测试里是空壳（抛异常）。
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val midGray = 0xFF808080.toInt()

    private fun gray(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    // ---- 抽帧时间点 ----------------------------------------------------------

    @Test
    fun `时长未知时只给第一帧`() {
        // 返回空列表等于让整条抽帧路径什么都不做，而它明明能出图。
        assertEquals(listOf(0L), ArtworkRules.frameTimeCandidatesMs(0L))
        assertEquals(listOf(0L), ArtworkRules.frameTimeCandidatesMs(-1L))
    }

    @Test
    fun `一分钟的片子取 6 秒和 24 秒`() {
        assertEquals(listOf(6_000L, 24_000L), ArtworkRules.frameTimeCandidatesMs(60_000L))
    }

    @Test
    fun `极短媒体去重成一条候选`() {
        // 99ms：整数除法把两个百分比都算成 0，两个候选是同一帧。
        // 不去重就是白抽两遍，而第二遍注定还是那张黑图。
        assertEquals(listOf(0L), ArtworkRules.frameTimeCandidatesMs(99L))
    }

    @Test
    fun `几百毫秒的媒体仍有两条不同候选`() {
        // 200ms：10% = 20，40% = 80，两者不同，不该被去重规则吞掉。
        assertEquals(listOf(20L, 80L), ArtworkRules.frameTimeCandidatesMs(200L))
    }

    @Test
    fun `候选点递增、互不相同、不越界`() {
        // 越界会被内核夹到最后一帧，「10% 处」和「40% 处」于是变成同一张图；
        // 且重复的时间点会让第二次抽帧拿到和第一次一样的结果，重试就白费了。
        listOf(1L, 99L, 100L, 1_000L, 60_000L, 3L * 60 * 60 * 1000).forEach { duration ->
            val candidates = ArtworkRules.frameTimeCandidatesMs(duration)
            assertTrue("duration=$duration 不该为空", candidates.isNotEmpty())
            candidates.forEach { at ->
                assertTrue("duration=$duration 的候选 $at 越界", at in 0..duration)
            }
            assertEquals("duration=$duration 的候选应当递增", candidates.sorted(), candidates)
            assertEquals("duration=$duration 的候选应当互不相同", candidates.distinct(), candidates)
        }
    }

    @Test
    fun `三小时的片子不给抽帧时间加封顶`() {
        // 有人会想给长视频加个「不超过 N 秒」的封顶。那会让抽出来的图不再是
        // 「这片子 10% 处」——对一集 45 分钟的剧，10 秒处大概率还是黑场或台标，
        // 而那正是用户要躲开的东西。这条断言是那个想法的刹车。
        val threeHours = 3L * 60 * 60 * 1000
        assertEquals(listOf(1_080_000L, 4_320_000L), ArtworkRules.frameTimeCandidatesMs(threeHours))
    }

    // ---- 亮度 ----------------------------------------------------------------

    @Test
    fun `空像素数组的亮度是零`() {
        assertEquals(0.0, ArtworkRules.averageLuminance(IntArray(0)), 1e-9)
    }

    @Test
    fun `纯色按 Rec709 权重加权`() {
        // 简单平均会给三者都算出 0.33，那正是要避免的算法：人眼对绿最敏感、
        // 对蓝最不敏感。三个权重分别验证，免得只验证了一个而另两个写反。
        assertEquals(0.2126, ArtworkRules.averageLuminance(intArrayOf(red)), 1e-6)
        assertEquals(0.7152, ArtworkRules.averageLuminance(intArrayOf(green)), 1e-6)
        assertEquals(0.0722, ArtworkRules.averageLuminance(intArrayOf(blue)), 1e-6)
    }

    @Test
    fun `黑白两端分别是 0 和 1`() {
        assertEquals(0.0, ArtworkRules.averageLuminance(intArrayOf(black)), 1e-9)
        assertEquals(1.0, ArtworkRules.averageLuminance(intArrayOf(white)), 1e-9)
    }

    @Test
    fun `全黑与全白的平均是半亮`() {
        assertEquals(0.5, ArtworkRules.averageLuminance(intArrayOf(black, white)), 1e-9)
    }

    @Test
    fun `纯蓝算暗`() {
        // 这条是整个「太暗就重抽」策略的支点：纯蓝的简单平均是 0.33，
        // 看着像「不暗」；加权后 0.07 才如实反映「它和黑差不多」。
        // 阈值一旦调低到 0.072 以下，一片纯蓝就会被当成可用封面。
        assertTrue(ArtworkRules.isTooDark(intArrayOf(blue)))
        assertFalse(ArtworkRules.isTooDark(intArrayOf(white)))
    }

    @Test
    fun `中灰不算暗`() {
        assertFalse(ArtworkRules.isTooDark(intArrayOf(midGray)))
    }

    @Test
    fun `阈值两侧的灰恰好分界`() {
        // 0x1E = 30 → 0.1176（暗）；0x1F = 31 → 0.1216（不暗）。
        // 这两个值把「阈值 0.12」和「判据是严格小于」同时钉住：
        // 换成 <= 或者把阈值写成 0.13，这条就会红。
        assertTrue(ArtworkRules.isTooDark(intArrayOf(gray(0x1E))))
        assertFalse(ArtworkRules.isTooDark(intArrayOf(gray(0x1F))))
    }

    // ---- 缩放尺寸 ------------------------------------------------------------

    @Test
    fun `长边已经在范围内就原样返回`() {
        // 调用方拿「返回值和入参相等」当「不用新建位图」的信号。
        // 返回一个「缩完还是这个尺寸」的新数组会让它每次都白拷一份。
        val result = ArtworkRules.fitWithin(512, 288)
        assertEquals(512, result[0])
        assertEquals(288, result[1])
    }

    @Test
    fun `恰好等于上限时不动`() {
        assertEquals(listOf(512, 512), ArtworkRules.fitWithin(512, 512).toList())
    }

    @Test
    fun `横图竖图都缩到 512 长边`() {
        assertEquals(listOf(512, 288), ArtworkRules.fitWithin(1920, 1080).toList())
        assertEquals(listOf(288, 512), ArtworkRules.fitWithin(1080, 1920).toList())
    }

    @Test
    fun `长宽或上限非法时给 0 和 0`() {
        // 不能返回 null：调用方要区分「不用缩」和「没得缩」，
        // 所以用一个真实尺寸里不可能出现的值当信号。
        assertEquals(listOf(0, 0), ArtworkRules.fitWithin(0, 1080).toList())
        assertEquals(listOf(0, 0), ArtworkRules.fitWithin(1920, 0).toList())
        assertEquals(listOf(0, 0), ArtworkRules.fitWithin(-1920, 1080).toList())
        assertEquals(listOf(0, 0), ArtworkRules.fitWithin(1920, 1080, maxEdge = 0).toList())
    }

    @Test
    fun `极度扁的图短边不会缩成 0`() {
        // 4000×3 的长条图等比缩完短边是 0.38 → 取整成 0。宽或高为 0 时
        // createScaledBitmap 会直接抛异常，所以短边至少留 1 像素。
        assertEquals(listOf(512, 1), ArtworkRules.fitWithin(4000, 3).toList())
    }

    // ---- 采样率 --------------------------------------------------------------

    @Test
    fun `长宽或上限非法时采样率为 1`() {
        assertEquals(1, ArtworkRules.sampleSize(0, 0))
        assertEquals(1, ArtworkRules.sampleSize(-1, 100))
        assertEquals(1, ArtworkRules.sampleSize(100, 100, maxEdge = 0))
    }

    @Test
    fun `小图不采样`() {
        assertEquals(1, ArtworkRules.sampleSize(100, 100))
        assertEquals(1, ArtworkRules.sampleSize(512, 512))
        // 513 长边只要降一半就掉到上限以下了，所以一次都别降，留给第二段精确缩。
        assertEquals(1, ArtworkRules.sampleSize(513, 513))
    }

    @Test
    fun `四千像素的原始封面按四分之一解出来`() {
        // 4 倍降采样后长边 1000，仍然 ≥ 512，剩下的交给第二段缩到 512。
        // 少了这一步，中间那张 4000×3000 的位图就是 48MB——低端机直接 OOM。
        assertEquals(4, ArtworkRules.sampleSize(4000, 3000))
    }

    @Test
    fun `采样率永远是 2 的幂而且刚好降到不小于上限`() {
        listOf(
            513 to 513,
            1024 to 768,
            1025 to 1025,
            1920 to 1080,
            4000 to 3000,
            8000 to 6000,
            16384 to 12288,
        ).forEach { (width, height) ->
            val longest = maxOf(width, height)
            val sample = ArtworkRules.sampleSize(width, height)

            assertTrue(
                "${width}x$height 的采样率 $sample 不是 2 的幂",
                sample > 0 && (sample and (sample - 1)) == 0,
            )
            // 不能再降一半：说明这是「能用的最大的那个幂」，没有白白牺牲清晰度。
            assertTrue(
                "${width}x$height 降 $sample 倍后还能再降一半",
                longest / (sample * 2) < ArtworkRules.MAX_EDGE_PX,
            )
            // 但也必须降到位：降完之后长边仍不小于上限，否则第二段就补不回来了。
            if (longest >= ArtworkRules.MAX_EDGE_PX) {
                assertTrue(
                    "${width}x$height 降 $sample 倍后长边只有 ${longest / sample}",
                    longest / sample >= ArtworkRules.MAX_EDGE_PX,
                )
            }
        }
    }

    // ---- 缓存键 --------------------------------------------------------------

    @Test
    fun `同一个请求每次算出同一个键`() {
        val uri = "content://media/external/audio/media/42"
        assertEquals(
            ArtworkRules.cacheKeyOf(uri, MediaKind.AUDIO),
            ArtworkRules.cacheKeyOf(uri, MediaKind.AUDIO),
        )
    }

    @Test
    fun `键以类型名为前缀`() {
        // 不是装饰：调磁盘缓存的时候能在 cacheDir 里一眼看出某个文件是
        // 音频封面还是视频抽帧，不用反推散列。
        assertTrue(ArtworkRules.cacheKeyOf("x", MediaKind.AUDIO).startsWith("audio-"))
        assertTrue(ArtworkRules.cacheKeyOf("x", MediaKind.VIDEO).startsWith("video-"))
        assertTrue(ArtworkRules.cacheKeyOf("x", MediaKind.UNKNOWN).startsWith("unknown-"))
    }

    @Test
    fun `类型进键`() {
        // 同一个 uri 被当成音频和视频问过的时候（UNKNOWN 收敛的过程）取法不同、
        // 结果也不同，不该互相命中内存缓存。
        val uri = "content://media/external/file/7"
        assertNotEquals(
            ArtworkRules.cacheKeyOf(uri, MediaKind.AUDIO),
            ArtworkRules.cacheKeyOf(uri, MediaKind.VIDEO),
        )
    }

    @Test
    fun `不同 uri 不同键`() {
        assertNotEquals(
            ArtworkRules.cacheKeyOf("content://media/external/audio/media/1", MediaKind.AUDIO),
            ArtworkRules.cacheKeyOf("content://media/external/audio/media/2", MediaKind.AUDIO),
        )
    }

    @Test
    fun `已知会撞 hashCode 的两个 uri 仍然拿到不同的键`() {
        // "Aa" 和 "BB" 的 String.hashCode() 都是 2112（Java 的经典碰撞例子）。
        // 只用单向散列的话这两个 uri 会共用同一张封面，而且**一句报错都没有**。
        // 第二个（反向）散列正是为这种情况加的，所以这里连「确实撞了」也断言一遍，
        // 免得哪天换个字符串就悄悄失去了这条测试的意义。
        assertEquals("Aa".hashCode(), "BB".hashCode())
        assertNotEquals(
            ArtworkRules.cacheKeyOf("Aa", MediaKind.VIDEO),
            ArtworkRules.cacheKeyOf("BB", MediaKind.VIDEO),
        )
    }

    @Test
    fun `键里不含路径分隔符所以能当文件名`() {
        // 键直接被拿去拼磁盘文件名（`File(cacheDir, "$键.jpg")`），
        // 所以 `/`、`:` 之类一个都不能有——而 uri 里到处都是它们。
        val key = ArtworkRules.cacheKeyOf(
            "content://media/external/audio/media/12345:something?x=1",
            MediaKind.AUDIO,
        )
        listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', ' ', '%') .forEach { forbidden ->
            assertFalse("键 $key 里不该出现 '$forbidden'", key.contains(forbidden))
        }
        assertTrue(key.isNotBlank())
    }

    @Test
    fun `键只取决于字节用 JVM 规范的散列独立复算一遍`() {
        // 磁盘缓存要跨进程、跨重启命中，所以键必须只由「输入的字节」决定。
        // 这里按 Java 规范（h = 31h + c）独立实现第二份再对答案，
        // 而不是把算好的值抄进来：抄进来的数字只能证明「和上次一样」。
        val uri = "content://media/external/audio/media/12345"
        val expected = "${MediaKind.AUDIO.name.lowercase()}-" +
            "${specHash(uri).toUInt().toString(16)}-" +
            "${specHash(uri.reversed()).toUInt().toString(16)}"
        assertEquals(expected, ArtworkRules.cacheKeyOf(uri, MediaKind.AUDIO))
    }

    /** Java 规范里的 `String.hashCode()`：`h = 31 * h + c`（对 UTF-16 码元）。 */
    private fun specHash(text: String): Int {
        var hash = 0
        text.forEach { hash = hash * 31 + it.code }
        return hash
    }

    // ---- 磁盘缓存 ------------------------------------------------------------

    @Test
    fun `不存在的文件不算可用缓存`() {
        val missing = File(temporary.root, "${ArtworkRules.cacheKeyOf("x", MediaKind.AUDIO)}.jpg")
        assertFalse(missing.exists())
        assertFalse(ArtworkRules.cacheFileIsUsable(missing))
    }

    @Test
    fun `零点字节的文件不算可用缓存`() {
        // 被取消（用户滑走 / 切页面）或写到一半失败的写入留下的就是这个。
        // 缓存命中发生在解码**之前**，所以认下它等于把这个坏状态永久固化：
        // 以后每次冷启动都读到同一张解不开的图，而且再也不会重抽。
        val empty = temporary.newFile("empty.jpg")
        assertEquals(0L, empty.length())
        assertFalse(ArtworkRules.cacheFileIsUsable(empty))
    }

    @Test
    fun `目录不算可用缓存`() {
        // 只判 exists() 的话同名目录也算「有封面」，而打开目录会直接失败——
        // 报错现场里「它明明存在」这句会把排查带偏。
        assertFalse(ArtworkRules.cacheFileIsUsable(temporary.newFolder("a.jpg")))
    }

    @Test
    fun `有内容的文件算可用缓存`() {
        val file = temporary.newFile("ok.jpg")
        file.writeText("JPEG")
        assertTrue(ArtworkRules.cacheFileIsUsable(file))
    }

    @Test
    fun `临时文件有内容时改名成正式缓存`() {
        val temp = File(temporary.root, "a.jpg.tmp").apply { writeText("JPEG-BYTES") }
        val target = File(temporary.root, "a.jpg")

        assertTrue(ArtworkRules.promoteAtomically(temp, target))

        assertTrue(target.isFile)
        assertEquals("JPEG-BYTES", target.readText())
        // 改名而不是拷贝：留着临时文件的话，缓存目录里会攒下一堆没人读也没人清的垃圾。
        assertFalse("发布之后临时文件不该还在", temp.exists())
    }

    @Test
    fun `临时文件为空时不发布而且清掉它`() {
        val temp = temporary.newFile("b.jpg.tmp")
        val target = File(temporary.root, "b.jpg")

        assertFalse(ArtworkRules.promoteAtomically(temp, target))

        assertFalse("半张图不该被发布成缓存", target.exists())
        assertFalse("失败的临时文件要清掉", temp.exists())
    }

    @Test
    fun `临时文件不存在时不发布`() {
        val temp = File(temporary.root, "missing.tmp")
        val target = File(temporary.root, "c.jpg")

        assertFalse(ArtworkRules.promoteAtomically(temp, target))
        assertFalse(target.exists())
    }

    @Test
    fun `发布失败不会毁掉已经存在的正式缓存`() {
        // 这条比上面的都重要：失败路径上多一次 delete() 或者把 target 当临时文件删掉，
        // 代价不是「这次没封面」而是「把已经缓好的图删了」——用户重进列表就没了。
        val target = File(temporary.root, "keep.jpg").apply { writeText("OLD") }
        val temp = temporary.newFile("keep.jpg.tmp")

        assertFalse(ArtworkRules.promoteAtomically(temp, target))

        assertTrue("已有的缓存必须原样留着", target.isFile)
        assertEquals("OLD", target.readText())
    }
}
