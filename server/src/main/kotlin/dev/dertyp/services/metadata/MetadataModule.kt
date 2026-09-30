package dev.dertyp.services.metadata

import dev.dertyp.services.MetadataFetchingService
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val metadataModule = module {
    singleOf(::MetadataFetchingService)
    singleOf(::MetadataDispatcherService) { bind<IMetadataService>() }
    singleOf(::MusicBrainzService)
    singleOf(::MusicBrainzCacheService)
    singleOf(::CachedMusicBrainzService) { bind<IMusicBrainzService>() }
    singleOf(::AcoustIdFingerprintService)
    singleOf(::AcoustIdCredentialSource)
    singleOf(::AcoustIdService)
    singleOf(::LinkResolverService)
    singleOf(::AppleMusicArtistResolver)
}
