package com.multisuperplayer.core.common.appinfo

import com.multisuperplayer.core.common.info.InfoRow

/**
 * 当前安装包的构建信息。
 *
 * 数据源头是 `BuildConfig`（由 `app/build.gradle.kts` 在**构建时**注入 git tag / commit /
 * 构建时间，见那里的 `gitOutput(...)`），因此这个类本身不碰任何 Android API，
 * 可以在 JVM 单测里直接构造——「关于页显示什么」这件事值得钉住，
 * 尤其是**字段缺失时的降级**：一行空白和一页空白都属于「看起来正常但什么也没说」。
 *
 * [buildTimeText] 是**构建机上已经格式化好的字符串**（例如 `2026-10-02 15:30 +08:00`），
 * 不是 epoch 毫秒。理由是：毫秒要在设备上再格式化一次，而设备的时区和构建机的时区
 * 往往不同，于是「构建时间」会显示成一个偏移了好几个小时的假时间。带上偏移量写死，
 * 谁看都一样，也不用猜。
 */
data class AppBuildInfo(
    val versionName: String,
    val versionCode: Int,
    val gitCommit: String,
    val gitTag: String,
    val gitDirty: Boolean,
    val buildTimeText: String,
) {

    /** 形如 `0.5.4 (504)`；拿不到版本号时返回 [UNKNOWN]。 */
    fun versionText(): String {
        val name = versionName.trim()
        if (name.isEmpty()) return UNKNOWN
        return if (versionCode > 0) "$name ($versionCode)" else name
    }

    /**
     * 提交说明：`09b06a6`、`09b06a6（含未提交改动）`、拿不到时 [UNKNOWN]。
     *
     * 「有未提交改动」必须说出来：否则一个在本地改过的包和一个干净的 tag 包
     * 会显示成同一个 commit，而它们的行为可能完全不同。
     */
    fun commitText(): String {
        val commit = gitCommit.trim()
        if (commit.isEmpty() || commit == UNKNOWN) return UNKNOWN
        return if (gitDirty) "$commit（$DIRTY_SUFFIX）" else commit
    }

    /**
     * 构建来源说明：优先 `tag（commit）`，只有 commit 就只显示 commit。
     *
     * 关于页里 `versionName` 和 git tag 是两条独立的信息（前者可能忘了同步），
     * 两者不一致时把 tag 也显示出来才看得见这件事。
     *
     * **脏标记挂在这一行**：关于页一共只有版本/来源/时间三行，没有单独的「提交」行，
     * 所以「这个包不是 tag 那份代码」只可能在这里说。不说的后果很具体——
     * 一个本地改过的包和干净的 tag 包会显示成同一行，而这正是当初把
     * versionCode/versionName 改成由构建脚本注入时想避免的事。
     */
    fun sourceText(): String {
        val commit = gitCommit.trim()
        val tag = gitTag.trim()
        val hasCommit = commit.isNotEmpty() && commit != UNKNOWN
        val hasTag = tag.isNotEmpty() && tag != UNKNOWN
        val dirtySuffix = if (gitDirty) "，$DIRTY_SUFFIX" else ""
        return when {
            hasTag && hasCommit -> "$tag（$commit$dirtySuffix）"
            hasTag -> "$tag$dirtySuffix"
            hasCommit -> "$commit$dirtySuffix"
            else -> UNKNOWN
        }
    }

    /** 供设置首页那一行「关于」用的摘要。 */
    fun summaryText(): String = versionText()

    /** 构建时间，拿不到时 [UNKNOWN]。 */
    fun buildTimeLabel(): String = buildTimeText.trim().ifEmpty { UNKNOWN }

    /** 日志抬头用的若干行，和关于页同源。 */
    fun rows(): List<InfoRow> = listOf(
        InfoRow("版本", versionText()),
        InfoRow("构建来源", sourceText()),
        InfoRow("构建时间", buildTimeLabel()),
    )

    companion object {
        /** 取不到时的占位文本。**不要**留空串：空白行等于「这个字段不存在」，无法与「构建脚本坏了」区分。 */
        const val UNKNOWN: String = "未知"

        /** 「本地有未提交改动」的标记（不带括号与前导逗号），[commitText] 与 [sourceText] 共用同一句话。 */
        const val DIRTY_SUFFIX: String = "含未提交改动"

        /** 构建脚本没注入时的兜底值。 */
        val Unknown = AppBuildInfo(
            versionName = "",
            versionCode = 0,
            gitCommit = UNKNOWN,
            gitTag = UNKNOWN,
            gitDirty = false,
            buildTimeText = UNKNOWN,
        )
    }
}
