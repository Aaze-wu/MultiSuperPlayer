package com.multisuperplayer.core.common.di

import com.multisuperplayer.core.common.coroutines.DefaultDispatcherProvider
import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import org.koin.dsl.module

/** core:common 对外提供的绑定。 */
val commonModule = module {
    single<DispatcherProvider> { DefaultDispatcherProvider() }
}
