package com.multisuperplayer.core.common.appinfo

import com.multisuperplayer.core.common.R
import com.multisuperplayer.core.common.info.InfoRow
import com.multisuperplayer.core.common.text.MspText

/**
 * 版本号后缀里会出现的那几个通道名。**唯一**一处定义。
 *
 * 同一个名字至少出现在三处：`versionName` 的后缀（`0.9.0-beta.1`）、git tag
 * （`v0.9.0-beta.1`）、以及更新通道「该收哪些版本」的判据（`` `UpdateChannel` ``）。
 * 散着写迟早会有一天只改了两处，而那时的表现是「某个通道忽然什么都收不到」——
 * 一个不报错、只是静默失效的开关。
 *
 * 放在 `core:common` 是因为依赖只能朝这个方向走：`core:data` 看得到这里，
 * 反过来不行。
 */
object AppChannelNames {

    /** 公开测试版：`0.9.0-beta.1`。对外发布，更新通道里的「测试版」收这一种。 */
    const val BETA = "beta"

    /**
     * 内部构建：`0.9.0-alpha.1`。
     *
     * **不对外发布**（不上传 Releases），更新通道里也没有任何一档收它。
     * 它出现在这里只是为了「关于页能认出自己是个什么包」。
     */
    const val ALPHA = "alpha"
}

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
    /**
     * 预发行通道（`alpha` / `beta` / `rc`）。**正式版是空串**。
     *
     * 由构建脚本从 `versionName` 的后缀里切出来，而不是让界面自己去 `versionName`
     * 上做字符串处理：切字符串的代码写错了不会报错，只会某天在一个
     * `1.0.0-rc.1+build.7` 上安静地失灵，而这里是一个显式字段，缺了就是空。
     */
    val versionChannel: String = "",
) {

    /**
     * 是不是预发行版。界面据此在标题区挂标记（[isBetaChannel] 决定挂哪一个词）。
     *
     * 只有两种情况会走到这里：公开测试版 `beta`，以及**不会公开**的内部构建。
     * 正式包里通道号是空串，于是徽章整块不出现。
     */
    val isPreview: Boolean get() = versionChannel.isNotBlank()

    /**
     * 是不是**公开测试版**（`beta`）。
     *
     * 关于页据此把标记写成「测试版」而不是「预览版」。两个词对用户的含义不一样：
     * 「预览版」是「随时会变，别当回事」，「测试版」是「可以用了，帮忙看看」。
     * 现在 `beta` 是对外发的那一档，用前者等于把一个正常可用的公开版本说成实验品。
     *
     * 比较前 `trim()`：这个字段来自构建脚本，多一个空格就变成另一个通道；
     * 与其它字段同一套规矩——空白等于「没取到」，但那属于 [isPreview] 的判断，
     * 这里只回答「是不是 beta」，取不到就是不是。
     */
    val isBetaChannel: Boolean
        get() = versionChannel.trim().equals(AppChannelNames.BETA, ignoreCase = true)

    /** 形如 `0.5.4 (50500)`；拿不到版本号时返回「未知」。 */
    fun versionText(): MspText {
        val name = versionName.trim()
        if (name.isEmpty()) return MspText.unknown()
        return if (versionCode > 0) {
            MspText.Res(R.string.msp_version_with_code, name, versionCode)
        } else {
            MspText.Plain(name)
        }
    }

    /**
     * 提交说明：`09b06a6`、`09b06a6（含未提交改动）`、拿不到时「未知」。
     *
     * 「有未提交改动」必须说出来：否则一个在本地改过的包和一个干净的 tag 包
     * 会显示成同一个 commit，而它们的行为可能完全不同。
     */
    fun commitText(): MspText {
        val commit = gitCommit.trim()
        if (commit.isEmpty()) return MspText.unknown()
        return if (gitDirty) {
            MspText.Res(R.string.msp_wrapped_in_parens, commit, dirtySuffix())
        } else {
            MspText.Plain(commit)
        }
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
    fun sourceText(): MspText {
        val commit = gitCommit.trim()
        val tag = gitTag.trim()
        return when {
            tag.isNotEmpty() && commit.isNotEmpty() ->
                MspText.Res(R.string.msp_wrapped_in_parens, tag, commitText())

            tag.isNotEmpty() -> if (gitDirty) appendedWithComma(tag) else MspText.Plain(tag)

            // 只有 commit 时直接复用 commitText()
            commit.isNotEmpty() -> commitText()

            else -> MspText.unknown()
        }
    }

    /** 供设置首页那一行「关于」用的摘要。 */
    fun summaryText(): MspText = versionText()

    /** 构建时间，拿不到时「未知」。 */
    fun buildTimeLabel(): MspText = MspText.plainOrUnknown(buildTimeText)

    /** 日志抬头用的若干行，和关于页同源。 */
    fun rows(): List<InfoRow> = listOf(
        InfoRow(MspText.Res(R.string.msp_row_version), versionText()),
        InfoRow(MspText.Res(R.string.msp_row_source), sourceText()),
        InfoRow(MspText.Res(R.string.msp_row_build_time), buildTimeLabel()),
    )

    /** 「，含未提交改动」里的后半句，[commitText] 与 [sourceText] 共用同一句话。 */
    private fun dirtySuffix(): MspText = MspText.Res(R.string.msp_dirty_suffix)

    /** `v0.5.3，含未提交改动`。 */
    private fun appendedWithComma(head: String): MspText =
        MspText.Res(R.string.msp_appended_with_comma, head, dirtySuffix())

    companion object {
        /**
         * 构建脚本没注入时的兜底值。
         *
         * 六个字段全是空串/0，**不**把「未知」写进字段里：由 `MspText.unknown()` 在渲染时
         * 兜底，于是「取到了什么」和「取不到时怎么显示」分开。好处是这两个词可以跟着
         * 界面语言走，而不是把中文烙进构建数据。
         */
        val Unknown = AppBuildInfo(
            versionName = "",
            versionCode = 0,
            gitCommit = "",
            gitTag = "",
            gitDirty = false,
            buildTimeText = "",
        )
    }
}
