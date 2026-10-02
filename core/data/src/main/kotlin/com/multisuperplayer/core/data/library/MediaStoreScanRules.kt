package com.multisuperplayer.core.data.library

/**
 * 扫描过程中的「纯规则」：版本分支、标题回退、partial 判定。
 *
 * ## 为什么单独抽一个文件
 *
 * 这些规则原本写在 [MediaStoreScanner] 里，和 `Context`、`Cursor`、`ContentResolver`
 * 混在一起，结果是**一行都测不到**。而它们恰恰是最容易写错的部分：
 *
 * - 版本分支（13 / 14 的权限名不一样，写错的表现是「点了授权按钮没反应」）；
 * - 标题回退（三选一，写错的表现是列表里出现一堆空标题）；
 * - partial 判定（写错的表现是「我明明只选了两个视频，界面却像全库一样自信」）。
 *
 * 抽出来之后它们是纯函数，能在普通 JVM 单测里直接跑。
 *
 * ## 为什么 `sdkInt` 是参数而不是直接读 `Build.VERSION.SDK_INT`
 *
 * 单元测试里 `Build.VERSION.SDK_INT` **恒为 0**（用的是 mockable android.jar 里的桩），
 * 所以只要函数体里去读它，测试就永远只能走到 `else` 分支——也就是说
 * 真正的三条分支一条都验证不了，「版本判断写错」这类回归会一路溜到用户手上。
 * 把版本号当参数传进来，四条分支才有了可观察的入口。
 */
internal object MediaStoreScanRules {

    /**
     * 标题、文件名都拿不到时的**空**占位符。
     *
     * 曾经是 `"(未知)"`——但数据层不能放界面文案：这里的值只表示「没有标题」，
     * 显示什么由界面决定（`core:ui` 的 `msp_media_unknown_title`），否则英文
     * 界面里会冒出一句写死的中文。
     */
    const val UNKNOWN_TITLE = ""

    /**
     * MediaStore 在「这条记录没有艺术家/专辑信息」时**返回的字面字符串**。
     *
     * 注意它不是一个错误码，而是一个普普通通的字符串，所以会一路混进列表、
     * 被当成真的艺术家名显示出来——用户看到的就是 `<unknown> · Music`
     * 这种把系统内部哨兵值当元数据的界面。
     */
    const val UNKNOWN_TAG = "<unknown>"

    /**
     * 规范化 MediaStore 返回的艺术家/专辑字段。
     *
     * 空白串和 [UNKNOWN_TAG] 都算「没有」。返回值是 `String?` 而不是空串：
     * UI 用 `listOfNotNull(...)` 拼 `艺术家 · 专辑`，给空串会拼出一个孤零零的
     * `·`，给 `null` 才会真的把这一项略掉。
     *
     * 大小写不敏感地比较：哨兵值本身是全小写，但没理由依赖这个巧合。
     */
    fun normalizeTag(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals(UNKNOWN_TAG, ignoreCase = true) }

    /**
     * 需要向系统申请的权限。
     *
     * 版本差异的原因：
     * - Android 13（33）把「读外部存储」拆成了 `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO`；
     * - Android 14（34）又多了一个 `READ_MEDIA_VISUAL_USER_SELECTED`，对应
     *   「仅选择部分照片和视频」这个新选项。
     *
     * **申请一个当前版本不存在的权限，系统的权限对话框会直接不弹**，
     * 用户看到的就是「点按钮没反应」。所以这里必须按版本给准。
     */
    fun requiredPermissions(sdkInt: Int): Array<String> = when {
        sdkInt >= VERSION_UPSIDE_DOWN_CAKE -> arrayOf(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        sdkInt >= VERSION_TIRAMISU -> arrayOf(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            android.Manifest.permission.READ_MEDIA_VIDEO,
        )

        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * 「只拿到用户勾选的那部分视频」= 有 34+ 的新权限、但没有完整的视频权限。
     *
     * 两个条件缺一不可：如果 `READ_MEDIA_VIDEO` 也在手上，那看到的就是全部视频，
     * 此时那个 selected 权限只是历史残留，不能因此把界面标成 partial。
     */
    fun isPartialVisualAccess(
        sdkInt: Int,
        hasFullVideoPermission: Boolean,
        hasUserSelectedVisualPermission: Boolean,
    ): Boolean =
        sdkInt >= VERSION_UPSIDE_DOWN_CAKE &&
            !hasFullVideoPermission &&
            hasUserSelectedVisualPermission

    /**
     * 本次扫描结果是否「不完整」。
     *
     * 两种情况：
     * 1. 只拿到用户勾选的视频（见 [isPartialVisualAccess]）；
     * 2. 音频权限被拒，但视频扫到了东西——说明这个库本来就不全。
     *
     * 第 2 点的价值在于：界面上要能解释「为什么我手机里有 500 首歌却只显示 3 个视频」，
     * 否则用户只会认为扫描坏了。
     */
    fun isPartialScan(
        partialVisualAccess: Boolean,
        hasAudioPermission: Boolean,
        videoCount: Int,
    ): Boolean = partialVisualAccess || (!hasAudioPermission && videoCount > 0)

    /**
     * 列表里显示的标题。
     *
     * 三级回退：MediaStore 的 TITLE → 去掉扩展名的文件名 → [UNKNOWN_TITLE]。
     *
     * 两个细节：
     * - TITLE 空串和全空白都算「没有」，否则列表里会出现一条肉眼看不见标题的项；
     * - 从文件名截扩展名时，截完也可能是空的（`.mp3` 这种隐藏文件），
     *   必须再判一次空白，不然它就会绕过回退、被当成合法标题显示出来。
     */
    fun deriveTitle(rawTitle: String?, displayName: String?): String {
        rawTitle?.takeIf { it.isNotBlank() }?.let { return it }
        return displayName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: UNKNOWN_TITLE
    }

    // 这里刻意写成字面量而不是 Build.VERSION_CODES.*：
    // 那几个常量虽然是编译期常量（能内联、单测里可用），但写成字面量能让
    // 「这条分支对应哪个版本」在阅读时无需跳转就能确认。
    private const val VERSION_TIRAMISU = 33
    private const val VERSION_UPSIDE_DOWN_CAKE = 34
}
