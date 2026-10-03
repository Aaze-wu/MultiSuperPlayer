package com.multisuperplayer.core.translate.di

import com.multisuperplayer.core.common.coroutines.DispatcherProvider
import com.multisuperplayer.core.translate.ChatCompletionClient
import com.multisuperplayer.core.translate.HttpChatClient
import com.multisuperplayer.core.translate.SwitchingChatClient
import com.multisuperplayer.core.translate.TranslationCacheStore
import com.multisuperplayer.core.translate.TranslationEditsStore
import com.multisuperplayer.core.translate.TranslationEngine
import com.multisuperplayer.core.translate.TranslationProbe
import com.multisuperplayer.core.translate.TranslationRunner
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

/**
 * 字幕翻译模块的依赖。
 *
 * 缓存与人工修正都放在 `filesDir` 下（不是 `cacheDir`）：
 * 前者是**已经付过钱**的结果，后者是用户的劳动成果，都不该被系统在
 * 「存储空间不足」时清掉——那样用户会看到译文明天自己消失了。
 */
val translateModule = module {

    // 出网还是在本机跑，由**请求自己**带着（见 SwitchingChatClient）：
    // 引擎不读「用户当前选了哪家服务商」，只把 config 快照发出去，
    // 所以跑完一半换服务商也不会让同一批里混进两种来源。
    // LlmTextGenerator 由 core:data 装配（模型目录/下载/引擎都在那边）。
    single<ChatCompletionClient> {
        SwitchingChatClient(remote = HttpChatClient(), generator = get())
    }

    single {
        TranslationCacheStore(
            file = File(androidContext().filesDir, TranslationCacheStore.FILE_NAME),
            dispatchers = get<DispatcherProvider>(),
        )
    }

    single {
        TranslationEditsStore(
            directory = File(androidContext().filesDir, TranslationEditsStore.DIRECTORY_NAME),
            dispatchers = get<DispatcherProvider>(),
        )
    }

    single { TranslationEngine(client = get(), cache = get(), dispatchers = get()) }

    /**
     * 引擎的门面。
     *
     * 显式绑一次接口，而不是指望「按实现类注册的也能按接口取到」：
     * 后者在 Koin 各版本上行为不一致，而它一旦不成立，报错发生在
     * 打开字幕面板的那一刻（运行时），编译期毫无提示。
     */
    single<TranslationRunner> { get<TranslationEngine>() }

    /**
     * 设置页的「测试连接 / 拉模型列表」。
     *
     * 构造参数里有 internal 类型（[ChatCompletionClient]），所以它只能在这里被建；
     * 对外暴露的只有 [TranslationProbe] 自己和它的结果类型。
     */
    single { TranslationProbe(runner = get(), client = get()) }
}
