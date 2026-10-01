package com.multisuperplayer.core.data.di

import com.multisuperplayer.core.data.artwork.ArtworkPaletteRepository
import com.multisuperplayer.core.data.library.MediaLibraryRepository
import com.multisuperplayer.core.data.library.MediaStoreScanner
import com.multisuperplayer.core.data.settings.ThemeSettingsRepository
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

    single { ArtworkPaletteRepository(context = androidContext(), dispatchers = get()) }
}
