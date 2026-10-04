package com.multisuperplayer.feature.library

import com.multisuperplayer.core.model.text.MspText
import com.multisuperplayer.core.model.MediaEntry

/**
 * 列表的排布方式。
 *
 * 只有「列表 / 网格」两种，不做「自适应列数」：网格列数由屏宽决定（见界面层），
 * 存进状态里反而会在旋转后留下一份过期的列数。
 */
enum class LibraryViewMode { LIST, GRID }

/**
 * 排序方式。
 *
 * ## 为什么把「未知值排最后」写进比较器
 *
 * 时长/体积/添加时间都可能**缺失**：MediaStore 里不少容器的时长为 0，
 * SAF 扫出来的条目根本没有这三个字段（那时候填的是 0）。
 * 如果直接用 `compareByDescending { durationMs }`，这些条目会以「0 毫秒」的身份
 * 稳稳地排在最后一位——看起来是对的，但「升序」时它们会跑到**最前面**，
 * 于是用户点一下「时长（短→长）」看到的是「一堆未知时长的文件在最上面」。
 * 「未知」不是「0」，它必须自己一档，且无论升降都靠后。
 *
 * ## 为什么要用 id 兜底
 *
 * 同一艺术家、同一秒添加的两个文件在主键上完全相等，`sortedWith` 的稳定性
 * 只能保证「相对输入不变」，而输入顺序取决于扫描与合并的过程。
 * 补一个 `id` 比较之后顺序才是**全序**：同样的库，无论重扫几次、分几次合并，
 * 列表顺序都一样，测试也才能钉住它。
 */
enum class LibrarySort(val label: MspText) {
    TITLE_ASC(MspText.Res(R.string.msp_library_sort_title_asc)),
    TITLE_DESC(MspText.Res(R.string.msp_library_sort_title_desc)),
    NEWEST(MspText.Res(R.string.msp_library_sort_newest)),
    OLDEST(MspText.Res(R.string.msp_library_sort_oldest)),
    LONGEST(MspText.Res(R.string.msp_library_sort_longest)),
    LARGEST(MspText.Res(R.string.msp_library_sort_largest)),
    ;

    /** 该排序方式对应的比较器（全序）。 */
    fun comparator(): Comparator<MediaEntry> = when (this) {
        // 显式写 `<MediaEntry, String>`：`compareBy(比较器, 选择函数)` 的 T 只能从选择
        // 函数的参数类型推，而那个 lambda 是隐式参数，推不出来（编译报 “Cannot infer
        // type for type parameter 'T'”）；而只写一个类型参数也不行——两个都写才行。
        TITLE_ASC -> compareBy<MediaEntry, String>(TITLE_ORDER) { it.sortTitle() }.thenById()
        TITLE_DESC -> compareByDescending<MediaEntry, String>(TITLE_ORDER) { it.sortTitle() }
            .thenById()
        NEWEST -> compareBy<MediaEntry> { it.dateAddedSeconds <= 0L }
            .thenByDescending { it.dateAddedSeconds }
            .thenByTitle()
        OLDEST -> compareBy<MediaEntry> { it.dateAddedSeconds <= 0L }
            .thenBy { it.dateAddedSeconds }
            .thenByTitle()
        LONGEST -> compareBy<MediaEntry> { it.durationMs <= 0L }
            .thenByDescending { it.durationMs }
            .thenByTitle()
        LARGEST -> compareBy<MediaEntry> { it.sizeBytes <= 0L }
            .thenByDescending { it.sizeBytes }
            .thenByTitle()
    }
}

/**
 * 分组维度。
 *
 * 音频按艺术家/专辑，视频按文件夹——枚举里不区分「音频只能按艺术家」：
 * 一条一条地限制用户能选什么，比让他选完看到空列表更麻烦。
 */
enum class LibraryGroupMode(val label: MspText) {
    NONE(MspText.Res(R.string.msp_library_group_none)),
    ARTIST(MspText.Res(R.string.msp_library_group_artist)),
    ALBUM(MspText.Res(R.string.msp_library_group_album)),
    FOLDER(MspText.Res(R.string.msp_library_group_folder)),
    ;

    /** 「未知」分组在界面上该显示成什么。 */
    val unknownLabel: MspText
        get() = when (this) {
            // 不分组时没有分组头，这个值取不到；给过滤/歌曲档一个说得通的兜底。
            NONE, ARTIST -> MspText.Res(R.string.msp_library_group_unknown_artist)
            ALBUM -> MspText.Res(R.string.msp_library_group_unknown_album)
            FOLDER -> MspText.Res(R.string.msp_library_group_unknown_folder)
        }
}

/**
 * 标题排序用的比较器：忽略大小写，空标题退化到 id（空串会一起挤在最前面，
 * 而空白标题的条目本身没有更有意义的排序依据）。
 *
 * 注意：这是**码位序**，中文不是拼音序。想让中文按拼音排需要 `Collator`，
 * 而它是 locale 相关的——同一份数据在不同系统语言下顺序不同，测试也只能写成
 * 「大致如此」。这里选确定性，并在 README 里记为已知限制。
 */
private val TITLE_ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER

private fun MediaEntry.sortTitle(): String = title.ifBlank { id }

private fun Comparator<MediaEntry>.thenById(): Comparator<MediaEntry> = thenBy { it.id }

private fun Comparator<MediaEntry>.thenByTitle(): Comparator<MediaEntry> =
    thenBy(TITLE_ORDER) { it.sortTitle() }.thenById()
