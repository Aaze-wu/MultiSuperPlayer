package com.multisuperplayer.core.common.text

import android.content.res.Resources
import androidx.annotation.StringRes
import com.multisuperplayer.core.common.R

/**
 * 一段**还没被解析**的界面文案。
 *
 * ## 为什么要多这一层，而不直接返回 `String`
 *
 * 项目里有一批「纯函数」负责把状态翻译成人话，比如
 * `PlaybackErrorMapper.describe(...)`、`describeTranslationFailure(...)`、
 * `AppBuildInfo.sourceText()`。它们刻意不碰 Android API，好让 JVM 单测能直接钉住
 * 「哪种情况说哪句话」——而这件事是**语义**，不是字符串本身。
 *
 * 一旦要支持多语言，直接返回 `String` 的函数就必须先拿到 `Resources`：那会让它们
 * 变成不可 JVM 测试的（单测里没有真的 `Resources`），或者要在每个测试里塞一个
 * 「假 Resources」，而假 Resources 只能把 id 拼成 `«2131»` 这种没有含义的东西，
 * 测试也就写不出「这两句必须是不同的话」这类断言了。
 *
 * 所以反过来：**函数返回「哪一条文案 + 什么参数」，解析留给 UI 边界**
 * （Compose 里是 `stringResource`，日志/导出那条路上是 `Resources.getString`）。
 * 测试于是断言到资源 id 级别：改标点不会让测试变红，但选错了分支一定会。
 */
sealed interface MspText {

    /** 解析成真正显示给用户的字符串。 */
    fun resolve(resources: Resources): String

    /**
     * 本来就与语言无关的值：版本号、ABI、语言标签、文件大小、commit 摘要……
     *
     * 刻意不做成「`%1$s` 的字符串资源」：那样每多一个这样的值就多一条待翻译的文案，
     * 翻译者会以为自己要动它。真要翻译的内容一律走 [Res]。
     */
    data class Plain(val text: String) : MspText {
        override fun resolve(resources: Resources): String = text
    }

    /**
     * 一条字符串资源 + 它的参数。
     *
     * 参数里可以再嵌 [MspText]，[resolve] 会先把嵌套的解析掉——没有这一条，
     * 「设置摘要里嵌入一个时长」这种最常见的组合就只能退回传 `String`，
     * 而那正是上面说的「纯函数拿不到 Resources」的死结。
     */
    data class Res(@StringRes val id: Int, val args: List<Any?> = emptyList()) : MspText {

        /** `Res(R.string.x, 1, "a")` 这种写法。 */
        constructor(@StringRes id: Int, vararg args: Any?) : this(id, args.toList())

        override fun resolve(resources: Resources): String {
            if (args.isEmpty()) return resources.getString(id)
            return resources.getString(id, *flatArgs { it.resolve(resources) })
        }

        /**
         * 把参数摊平成 `getString(id, vararg)` 要的形状。
         *
         * 单独摘出来是为了**能单测**：`resolve` 必须要一个真的 `Resources`（JVM 单测里
         * 没有），而「哪些参数需要递归解析」其实是与 Android 无关的判断，
         * 所以把解析动作当参数注入，测试传一个假解析器就能直接钉住嵌套行为。
         */
        internal fun flatArgs(resolveArg: (MspText) -> String): Array<Any?> =
            Array(args.size) { index ->
                val arg = args[index]
                if (arg is MspText) resolveArg(arg) else arg
            }
    }

    companion object {

        /**
         * 「未知」。
         *
         * 刻意是一个**共享**取值，而不是各模块各写一份：设备信息会被同时渲染到关于页
         * 和日志抬头里，两处用不同的兜底词，排障时就会以为它们取自不同的字段。
         */
        fun unknown(): MspText = Res(R.string.msp_value_unknown)

        /** 空/空白一律当成「取不到」，显示成 [unknown]。 */
        fun plainOrUnknown(text: String): MspText =
            if (text.isBlank()) unknown() else Plain(text.trim())

        /**
         * 用 [separator] 把 [parts] 依次接起来，空列表给出空串。
         *
         * 分隔符当一个参数传给 [Res]，而不是在代码里写 `" · "`：这样「换标点」和「换顺序」
         * 都是译者能做的事，而 `String` 拼接在代码里既不可翻译、也不可测（测试只能看到
         * 一整条拼好的句子，说不出是哪几段）。
         */
        fun join(separator: MspText, parts: List<MspText>): MspText =
            parts.reduceOrNull { acc, part -> Res(R.string.msp_joined, acc, separator, part) }
                ?: Plain("")
    }
}
