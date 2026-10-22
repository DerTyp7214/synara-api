package dev.dertyp.services.lyrics.providers

import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val lyricsProvidersModule = module {
    singleOf(::LyricsHttp)
    singleOf(::BetterLyricsProvider) { bind<LyricsProvider>() }
    singleOf(::UnisonProvider) { bind<LyricsProvider>() }
    singleOf(::LyricsPlusProvider) { bind<LyricsProvider>() }
    singleOf(::KuGouProvider) { bind<LyricsProvider>() }
    singleOf(::LrcLibLyricsProvider) { bind<LyricsProvider>() }
    singleOf(::YouTubeMusicProvider) { bind<LyricsProvider>() }
    singleOf(::SidecarLyricsProvider)
    singleOf(::LyricsProviderRegistry)
}
