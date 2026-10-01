package com.multisuperplayer.core.common.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 调度器抽象。
 *
 * 直接引用 [Dispatchers.IO] 会让涉及磁盘/网络的逻辑在单元测试里不可控，
 * 所有需要指定调度器的类都通过该接口注入，测试时可替换成 `StandardTestDispatcher`。
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
}
