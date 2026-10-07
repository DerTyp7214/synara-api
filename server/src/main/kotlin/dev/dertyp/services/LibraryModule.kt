package dev.dertyp.services

import dev.dertyp.Indexer
import dev.dertyp.plugins.*
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val libraryModule = module {
    singleOf(::Indexer) { bind<IPluginIndexer>() }
    singleOf(::SongService) { bind<SongLibrary>() }
    singleOf(::AlbumService) { bind<AlbumLibrary>() }
    singleOf(::ArtistService) { bind<ArtistLibrary>() }
    singleOf(::GenreService)
    singleOf(::PlaylistService)
    singleOf(::UserPlaylistService) { bind<PlaylistLibrary>() }
    singleOf(::CollectionService)
    singleOf(::FavSyncService)
    singleOf(::LibraryMergeService)
    singleOf(::LibraryFileDeleter)
    singleOf(::VersionGroupTrigger) { bind<HookSubscriber>() }
    singleOf(::DuplicateAlbumMergeTrigger) { bind<HookSubscriber>() }
    singleOf(::SearchIndexRemover) { bind<HookSubscriber>() }
    singleOf(::CustomAudioService)
    singleOf(::TimecodeTagService)
    singleOf(::EntityChangeRecorder) { bind<HookSubscriber>() }
    singleOf(::EntityEventPublisher)
    singleOf(::EntityChangeService)
    singleOf(::ImageService) { bind<ImageLibrary>() }
    singleOf(::AnimatedImageService)
    singleOf(::LyricsSearch)
    singleOf(::LyricsService)
    singleOf(::LrcLibService) { bind<ILrcLibService>() }
    singleOf(::SearchIndexWorker)
    singleOf(::RedisSearchService)
}
