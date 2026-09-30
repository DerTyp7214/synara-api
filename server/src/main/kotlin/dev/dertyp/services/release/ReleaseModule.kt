package dev.dertyp.services.release

import dev.dertyp.services.ReleaseService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val releaseModule = module {
    singleOf(::ReleaseService)
    singleOf(::AppleMusicReleaseService)
    singleOf(::ProviderLinkService)
    singleOf(::ReleaseArtistService)
}
