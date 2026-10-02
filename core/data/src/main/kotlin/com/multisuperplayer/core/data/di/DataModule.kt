package com.multisuperplayer.core.data.di

import com.multisuperplayer.core.data.artwork.ArtworkPaletteRepository
import com.multisuperplayer.core.data.browser.BrowserRepository
import com.multisuperplayer.core.data.browser.StorageAccess
import com.multisuperplayer.core.data.history.PlaybackPositionRepository
import com.multisuperplayer.core.data.history.RecentPlayRepository
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaStoreScanner
import com.multisuperplayer.core.data.library.SafTreeScanner
import com.multisuperplayer.core.data.library.SafTreeStore
import com.multisuperplayer.core.data.playlist.PlaylistStore
import com.multisuperplayer.core.data.settings.ApiKeyStore
import com.multisuperplayer.core.data.settings.LocaleSettingsRepository
import com.multisuperplayer.core.data.settings.PlaybackSettingsRepository
import com.multisuperplayer.core.data.settings.SubtitleSettingsRepository
import com.multisuperplayer.core.data.settings.ThemeSettingsRepository
import com.multisuperplayer.core.data.settings.TranslationSettingsRepository
import com.multisuperplayer.core.data.subtitle.SafSubtitleLocator
import com.multisuperplayer.core.data.subtitle.SubtitleExportWriter
import com.multisuperplayer.core.data.subtitle.SubtitleFileLocator
import com.multisuperplayer.core.data.subtitle.SubtitleRepository
import com.multisuperplayer.core.player.PlaybackPositionStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single { MediaStoreScanner(context = androidContext()) }

    // SAF 目录扫描。与 MediaStore 两条来源在仓库里合并、去重，
    // 详见 MediaLibraryRepository 与 LibraryMergeRules 的注释。
    single { SafTreeScanner(context = androidContext()) }

    single { SafTreeStore(context = androidContext(), dispatchers = get()) }

    // 「所有文件访问」的状态与授权入口。默认关闭、永不主动申请，
    // 详情与理由见 StorageAccess 的类注释。
    single { StorageAccess(context = androidContext()) }

    // 内置文件浏览器（只读）。依赖上面三个，所以排在这里。
    single {
        BrowserRepository(
            context = androidContext(),
            safTreeStore = get(),
            safScanner = get(),
            storage = get(),
            dispatchers = get(),
        )
    }

    single {
        MediaLibraryRepository(
            context = androidContext(),
            scanner = get(),
            safScanner = get(),
            safTreeStore = get(),
            dispatchers = get(),
        )
    }

    single { ThemeSettingsRepository(context = androidContext(), dispatchers = get()) }

    single { SubtitleSettingsRepository(context = androidContext(), dispatchers = get()) }

    single { PlaybackSettingsRepository(context = androidContext(), dispatchers = get()) }

    // 不加 dispatchers：这一个设置不用 DataStore、也不做异步读取，理由见 AppLocaleStore 的注释。
    single { LocaleSettingsRepository(context = androidContext()) }

    // 续播位置存在单独的 DataStore 文件里（不是 msp_settings）——
    // 它无界增长、写入频繁，混进设置文件会拖累用户设置。见 mspPositionsStore。
    single<PlaybackPositionStore> {
        PlaybackPositionRepository(context = androidContext(), dispatchers = get())
    }

    // 「最近播放」= 续播记录 × 当前媒体库的投影。需要读媒体库当前状态，
    // 所以只能排在上面两个注册之后。
    single { RecentPlayRepository(positionStore = get(), library = get()) }

    // 播放列表存在单独的 DataStore 文件（msp_playlists）里：写一次就是几 KB 自由文本，
    // 混进 msp_settings 会拖慢用户设置的写入。
    single { PlaylistStore(context = androidContext(), dispatchers = get()) }

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

    // SAF 目录里的字幕走另一条路（列兄弟目录而不是查 MediaStore），见类注释。
    single { SafSubtitleLocator(context = androidContext()) }

    // 导出译文用。走 SAF，不申请存储权限。
    single { SubtitleExportWriter(context = androidContext(), dispatchers = get()) }

    // parserRegistry 来自 core:subtitle 的 subtitleModule，由 MspApplication 一并加载。
    single {
        SubtitleRepository(
            context = androidContext(),
            locator = get(),
            safLocator = get(),
            parserRegistry = get(),
            dispatchers = get(),
        )
    }
}
