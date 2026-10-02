package com.multisuperplayer.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.multisuperplayer.core.common.text.MspText

/**
 * 在 Compose 里把 [MspText] 解析成字符串。
 *
 * 这是「延迟解析」的**UI 边界**：`core:*` 里的纯函数只负责决定「说哪一条」，
 * 真正取文案的动作（于是也就真正依赖当前语言）只发生在这里。
 * 好处是 `LocalConfiguration` 一变，重组的这一帧自然就是新语言了——
 * 不需要在切换语言的代码里挨个刷新字符串，也不会出现「数据变了但文案还是旧的」。
 *
 * 参数里嵌套的 [MspText] 会先被解析掉：长句套短句时子句也得跟着语言走。
 */
@Composable
fun MspText.string(): String = when (this) {
    is MspText.Plain -> text
    is MspText.Res -> {
        if (args.isEmpty()) return stringResource(id)
        // `map` 是 inline 的，所以这里可以调用另一个 @Composable。
        // `?: ""` 不是在兜异常：`null` 参数只可能是调用方写错了（现在没有任何一处这么用），
        // 而 `stringResource` 的 `vararg formatArgs: Any` 不接受可空数组，
        // 与其在这里崩掉整屏，不如把空值渲染成空串。
        val flat = Array<Any>(args.size) { index ->
            val arg = args[index]
            (if (arg is MspText) arg.string() else arg) ?: ""
        }
        stringResource(id, *flat)
    }
}
