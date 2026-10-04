package com.multisuperplayer.core.data.artwork

import com.multisuperplayer.core.model.MediaKind
import java.io.File

/**
 * 封面取图的全部判据。
 *
 * 这里只有纯函数，没有一处碰 `Bitmap` / `MediaMetadataRetriever`，
 * 是刻意的：真正难写对的不是「怎么从 uri 里掏出图」，而是
 * **在什么时间点抽帧、抽出来算不算废片、抽出来的图缩放成多大、
 * 磁盘上那个文件算不算能用的缓存**。
 * 这些全都由整数、浮点和一个文件名决定，可以一条条写断言。
 * 把 `Bitmap` 掺进来的话，测试就得跑在 Robolectric 上，
 * 而这里最需要精确断言的东西反而会变成「大致对」。
 *
 * 位图本身用一个 `IntArray`（ARGB 像素）表示，那是纯数据；
 * 文件相关的两条规则只碰 `java.io.File`（本项目的单测本来就跑真的文件系统，
 * 见 `GeneratedSubtitleStoreTest`）。
 */
internal object ArtworkRules {

    /** 第一候选帧：时长的 10%。 */
    const val FIRST_FRAME_PERCENT = 10

    /**
     * 太暗时的第二候选帧：时长的 40%。
     *
     * 为什么不是 20%：开场附近往往**整段**都是黑的（片头黑底字幕、
     * 渐入的黑场），只往后挪 10% 很容易落回同一段黑。40% 已经在片子的前半段
     * 偏中间，那里出现黑场的概率低得多。
     */
    const val SECOND_FRAME_PERCENT = 40

    /**
     * 平均亮度低于它就算「近黑帧」。
     *
     * 0.12 是偏保守的值：真正全黑的帧平均亮度在 0.02 上下，夜景画面大概
     * 0.05～0.15，有内容的画面普遍高于 0.2。取值偏高的代价是「多抽一帧」，
     * 偏低的代价是「用了一张看不清的图」，两个代价都不大，
     * 所以这里选「只有真的很黑才重试」。
     */
    const val DARK_LUMINANCE = 0.12

    /** 磁盘里存的封面长边上限（像素）。 */
    const val MAX_EDGE_PX = 512

    /**
     * 算平均亮度时把帧缩到 16×16 再采样。
     *
     * 逐像素遍历一张 1080p 的帧是 200 万次循环、还要把整张图拷进 `IntArray`
     * （8MB），而这里只想知道「它是不是黑的」。16×16 的均值足够回答这个问题，
     * 也让缩放的代价盖过采样的代价。
     */
    const val LUMINANCE_SAMPLE = 16

    /** JPEG 质量。封面是缩略图性质，88 已经看不出压缩痕迹。 */
    const val JPEG_QUALITY = 88

    /**
     * 抽帧的时间点（毫秒），按优先级排列。
     *
     * ## 时长未知时为什么是「第一帧」而不是空列表
     *
     * 返回空列表等于让整条抽帧路径**什么都不做**，而它明明能出图。
     * 时长缺失是常态：有些容器在 MediaStore 里就没有时长，网络来源更没有。
     * 时间点传 0 时 `getFrameAtTime` 给的是第一帧，那正是这时候唯一能有的答案。
     *
     * ## 为什么给「超过 10 秒的长视频」不设上限
     *
     * 3 小时的视频意味着第一个候选在 18 分钟处，有人会想给它加个
     * 「不超过 N 秒」的封顶。但那样抽出来的就不是「这片子 10% 处」了：
     * 对一集 45 分钟的剧，18 分钟处很可能还在片头之后的正片开头，
     * 而 10 秒处大概率还是黑场或台标——那正是用户要躲开的东西。
     * `getFrameAtTime` 用的是 `OPTION_CLOSEST_SYNC`（跳到最近的关键帧），
     * 对本地文件是毫秒级的 seek，不必为它牺牲取景位置。
     */
    fun frameTimeCandidatesMs(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return listOf(0L)
        val first = durationMs / 100L * FIRST_FRAME_PERCENT
        val second = durationMs / 100L * SECOND_FRAME_PERCENT
        // 极短媒体 / 整除掉到同一个值时去重，免得把完全相同的帧抽两遍。
        if (second <= first) return listOf(first)
        return listOf(first, second.coerceAtMost(durationMs))
    }

    /**
     * ARGB 像素的平均亮度，0.0～1.0。
     *
     * 用 Rec.709 的权重（人眼对绿最敏感、对蓝最不敏感），而不是简单平均
     * R/G/B：纯蓝的 `0xFF0000FF` 简单平均是 0.33（看起来「不暗」），
     * 实际观感上它和黑的区别很小。加权后是 0.07，落在「暗」这一侧。
     *
     * 位运算手写而不是 `Color.red()` 之类的框架方法：那些在单元测试里是空壳。
     */
    fun averageLuminance(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var sum = 0.0
        for (argb in pixels) {
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            sum += (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        }
        return sum / pixels.size
    }

    /** 这一帧是不是该换下一个候选时间点重抽。 */
    fun isTooDark(pixels: IntArray): Boolean = averageLuminance(pixels) < DARK_LUMINANCE

    /**
     * 把 [width]×[height] 等比缩到长边不超过 [maxEdge] 的尺寸。
     *
     * 长边已经在范围内时**原样返回**（调用方靠这个判断「不用新建位图」，
     * 免得为了一张本来就不大的图白拷一份）。宽高非法时返回 `[0, 0]`，
     * 调用方据此放弃——注意这里不能返回 `null`，
     * 因为「不用缩」和「没法缩」必须分得开。
     */
    fun fitWithin(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): IntArray {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return intArrayOf(0, 0)
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return intArrayOf(width, height)
        val scale = maxEdge.toDouble() / longest
        return intArrayOf(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
        )
    }

    /**
     * 解码前用的 `inSampleSize`（必须是 2 的幂）。
     *
     * 两段式缩放的第一段：先把 4000×3000 的原始封面按 1/4 解出来（1000×750，
     * 内存从 48MB 降到 3MB），第二段再用 [fitWithin] 精确缩到 512。
     * 只做第二段的话，中间那张全尺寸位图就是 OOM 的入口。
     *
     * 条件写成「再降一半还够大就继续降」，保证降完之后长边仍然 ≥ [maxEdge]
     * （能降过头就宁可少降，留给第二段）。
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * 内存缓存与磁盘缓存共用的键。
     *
     * ## 为什么不能直接用 uri 当键
     *
     * uri 里有 `/` 和 `:`，SAF 的 document uri 更是几百个字符——
     * 磁盘缓存要拿它当文件名。所以压成一个定长的十六进制指纹。
     *
     * ## 为什么不用 `String.hashCode()`
     *
     * 32 位。列表里几百条时碰撞概率不算高，但**碰撞的后果是沉默的**：
     * 两个不同的文件共用一张封面，而且谁都不会报错。
     * 两个独立方向的 32 位散列拼起来是 64 位有效位（`reversed()` 让
     * `"ab"` 和 `"ba"` 不会得到同一组值），碰撞概率降到可以忽略。
     *
     * `String.hashCode()` 的结果由 Java 规范钉死，跨进程、跨重启一致
     * ——这是磁盘缓存键必须的性质，`hashCode()` 恰好满足，所以不必自己
     * 实现一个散列算法（那只会多一处需要维护的魔数）。
     *
     * [kind] 也进键：同一个 uri 被当成音频和视频问过的时候（`UNKNOWN` 收敛成
     * 两者之一的过程），取法不同、结果也不同，不该互相命中。
     */
    fun cacheKeyOf(uri: String, kind: MediaKind): String {
        val forward = uri.hashCode().toUInt().toString(16)
        val backward = uri.reversed().hashCode().toUInt().toString(16)
        return "${kind.name.lowercase()}-$forward-$backward"
    }

    // ---- 磁盘缓存 ------------------------------------------------------------

    /**
     * 磁盘上这个文件算不算一张能用的封面。
     *
     * 「存在且非空」而不是「存在」：被取消或中途失败的写入会留下 0 字节的文件，
     * 而缓存命中发生在解码**之前**——认了它就等于把这个坏状态永久固化，
     * 以后每次读到它都解码失败一次。
     *
     * 目录也被排除（`isFile`）：同名目录会让读取直接失败，而报错现场里
     * 「明明存在」这句话会把人往错的方向带。
     */
    fun cacheFileIsUsable(file: File): Boolean = file.isFile && file.length() > 0L

    /**
     * 把写好的临时文件发布成正式缓存，失败时返回 `false` 并清掉临时文件。
     *
     * ## 为什么要「临时文件 + 改名」
     *
     * 少了这一步，一个被取消（用户滑走 / 切页面）或中途失败的写入会留下
     * **写了一半的 jpg**，而它同样满足「存在且非空」——于是这次失败被固化。
     * 同目录内的 `renameTo` 在 Android 上是原子的：要么是完整的图，要么什么都没有。
     *
     * ## 为什么要自己复检一次内容
     *
     * 调用方已经看过 `compress` 的返回值，但那个值只说明「没有抛异常」。
     * 这里仍然按「文件真的非空」判一次，因为这是发布前最后一道闸，
     * 而它判断的是**即将被读到的那份字节**。
     *
     * 两个失败分支都必须 `delete()` 那个临时文件：不删的话缓存目录里会攒下
     * 一堆没人读也没人清的 `.tmp`，而且它们不会被任何清理逻辑认领。
     *
     * 注：`File.renameTo` 在 Windows 上**目标已存在时**会失败（Android/Linux 会覆盖）。
     * 所以不要为这个分支写「目标已存在也要成功」的断言——那是在钉一个平台差异，
     * 不是在钉规则。
     */
    fun promoteAtomically(temp: File, target: File): Boolean {
        if (!cacheFileIsUsable(temp)) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        return true
    }
}
