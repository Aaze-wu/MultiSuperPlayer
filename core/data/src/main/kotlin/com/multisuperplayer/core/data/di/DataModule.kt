package com.multisuperplayer.core.data.di

import com.multisuperplayer.core.data.artwork.ArtworkPaletteRepository
import com.multisuperplayer.core.data.history.PlaybackPositionRepository
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaStoreScanner
import com.multisuperplayer.core.data.settings.ApiKeyStore
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.settings.ThemeSettingsRepository
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.data.subtitle.SubtitleExportWriter
import com.multisuperplayer.core.data.subtitle.SubtitleFileLocator
import com.multisuperplayer.core.data.subtitle.SubtitleRepository
import com.multisuperplayer.core.player.PlaybackPositionStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single { MediaStoreScanner(context = androidContext()) }

    single {
        MediaLibraryRepository(
            context = androidContext(),
            scanner = get(),
            dispatchers = get(),
        )
    }

    single { ThemeSettingsRepository(context = androidContext(), dispatchers = get()) }

    single { SubtitleSettingsRepository(context = androidContext(), dispatchers = get()) }

    single { PlaybackSettingsRepository(context = androidContext(), dispatchers = get()) }

    // 续播位置存在单独的 DataStore 文件里（不是 msp_settings）——
    // 它无界增长、写入频繁，混进设置文件会拖累用户设置。见 mspPositionsStore。
    single<PlaybackPositionStore> {
        PlaybackPositionRepository(context = androidContext(), dispatchers = get())
    }

    // API Key 加密存起来（AndroidKeyStore + AES/GCM），密文进 msp_settings 这个 DataStore。
    single { ApiKeyStore(context = androidContext(), dispatchers = get()) }

    single {
        TranslationSettingsRepository(
            context = androidContext(),
            dispatchers = get(),
            apiKeys = get(),
        )
    }

    single { ArtworkPaletteRepository(context = androidContext(), dispatchers = get()) }

    single { SubtitleFileLocator(context = androidContext()) }

    // 导出译文用。走 SAF，不申请存储权限。
    single { SubtitleExportWriter(context = androidContext(), dispatchers = get()) }

    // parserRegistry 来自 core:subtitle 的 subtitleModule，由 MspApplication 一并加载。
    single {
        SubtitleRepository(
            context = androidContext(),
            locator = get(),
            parserRegistry = get(),
            dispatchers = get(),
        )
    }
}
