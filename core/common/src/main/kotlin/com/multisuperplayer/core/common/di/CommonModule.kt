package com.multisuperplayer.core.common.di

import com.multisuperplayer.core.common.coroutines.DefaultDispatcherProvider
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.common.log.LogRepository
import com.multisuperplayer.core.common.log.MspLogInitializer
import org.koin.dsl.module

/** core:common 对外提供的绑定。 */
val commonModule = module {
    single<DispatcherProvider> { DefaultDispatcherProvider() }

    /**
     * 日志读取侧。
     *
     * 两个参数都是 lambda，把「目录/落地端是哪来的」与「什么时候解析」解耦：
     * 目录由 `MspApplication.onCreate` 里的 [MspLogInitializer.install] 决定，
     * 而 Koin 可能在这之前就把它建好了（顺序见 install 的注释）。
     * 传 lambda 之后，无论谁先谁后，取到的都是最终那一份。
     */
    single {
        LogRepository(
            directoryProvider = { MspLogInitializer.currentDirectory() },
            flush = { MspLogInitializer.currentSink()?.flush() },
        )
    }
}
