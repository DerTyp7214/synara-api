package dev.dertyp.core

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.schedule.*
import io.ktor.server.application.Application
import org.koin.ktor.ext.get

fun Application.configureScheduledTasks() {
    val scheduleService = get<ScheduleService>()

    val sessionService = get<SessionService>()
    val imageService = get<ImageService>()
    val libraryMergeService = get<LibraryMergeService>()
    val artistService = get<ArtistService>()
    val albumService = get<AlbumService>()
    val backupService = get<BackupService>()
    val userPlaylistBackupService = get<UserPlaylistBackupService>()
    val reverseProxyWorker = get<ReverseProxyWorker>()
    val metadataFetchingService = get<MetadataFetchingService>()
    val musicBrainzWorker = get<MusicBrainzWorker>()
    val musicBrainzCacheWorker = get<MusicBrainzCacheWorker>()
    val genreMetadataWorker = get<GenreMetadataWorker>()
    val artistImageWorker = get<ArtistImageWorker>()
    val recentReleaseWorker = get<RecentReleaseWorker>()
    val autoTranscodeWorker = get<AutoTranscodeWorker>()
    val lyricsSyncWorker = get<LyricsSyncWorker>()
    val audioAnalysisWorker = get<AudioAnalysisWorker>()
    val flacAnalysisWorker = get<FlacAnalysisWorker>()
    val imageAnalysisWorker = get<ImageAnalysisWorker>()
    val lrcLibWorker = get<LrcLibWorker>()
    val providerEnrichmentWorker = get<ProviderEnrichmentWorker>()

    scheduleService.registerManagedTask(
        key = TaskKeys.REVERSE_PROXY_HEALTH_CHECK,
        name = "Reverse Proxy Health Check",
        task = { reverseProxyWorker.run() }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.DATABASE_BACKUP,
        name = "Database Backup",
        task = {
            scheduleService.logTask("Database Backup") {
                val res = backupService.createBackup { p, l -> updateProgress(p, l) }
                mapOf("fileName" to res.fileName, "size" to res.size, "imageCount" to res.imageCount)
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.USER_PLAYLIST_BACKUP,
        name = "User Playlist Backup",
        task = {
            scheduleService.logTask("User Playlist Backup") {
                val count = userPlaylistBackupService.backupAllUsers { p, l -> updateProgress(p, l) }
                mapOf("userCount" to count)
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.SESSION_CLEANUP,
        name = "Session Cleanup",
        task = {
            scheduleService.logTask("Session Cleanup") {
                val count = sessionService.cleanupOldSessions { p, l -> updateProgress(p, l) }
                mapOf("sessionsDeleted" to count)
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.MERGE_LIBRARY_DUPLICATES,
        name = "Merge Library Duplicates",
        task = {
            scheduleService.logTask("Merge Library Duplicates") {
                libraryMergeService.mergeDuplicates { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.AUDIO_ANALYSIS,
        name = "Audio Analysis",
        task = {
            scheduleService.logTask("Audio Analysis") {
                audioAnalysisWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.FLAC_ANALYSIS,
        name = "FLAC Analysis",
        task = {
            scheduleService.logTask("FLAC Analysis") {
                flacAnalysisWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.MUSICBRAINZ_WORKER,
        name = "MusicBrainz Worker",
        task = {
            scheduleService.logTask("MusicBrainz Worker") {
                musicBrainzWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.MUSICBRAINZ_CACHE_WORKER,
        name = "MusicBrainz Cache Worker",
        task = {
            scheduleService.logTask("MusicBrainz Cache Worker") {
                musicBrainzCacheWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.GENRE_METADATA_WORKER,
        name = "Genre Metadata Worker",
        task = {
            scheduleService.logTask("Genre Metadata Worker") {
                genreMetadataWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.ARTIST_IMAGE_WORKER,
        name = "Artist Image Worker",
        task = {
            scheduleService.logTask("Artist Image Worker") {
                artistImageWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.FETCH_METADATA_THEAUDIODB,
        name = "Fetch Metadata (TheAudioDB)",
        task = {
            scheduleService.logTask("Fetch Metadata (TheAudioDB)") {
                metadataFetchingService.fetchMetadata(IMetadataService.MetadataType.theAudioDB) { p, l ->
                    updateProgress(p, l)
                }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.AUTO_TRANSCODING,
        name = "Auto Transcoding",
        task = {
            scheduleService.logTask("Auto Transcoding") {
                autoTranscodeWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.LYRICS_SYNC_WORKER,
        name = "Lyrics Sync Worker",
        task = {
            scheduleService.logTask("Lyrics Sync Worker") {
                lyricsSyncWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.LRCLIB_WORKER,
        name = "LrcLib Worker",
        task = {
            scheduleService.logTask("LrcLib Worker") {
                lrcLibWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.RECENT_RELEASE_WORKER,
        name = "Recent Release Worker",
        task = {
            scheduleService.logTask("Recent Release Worker") {
                recentReleaseWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.PROVIDER_ENRICHMENT_WORKER,
        name = "Provider Enrichment Worker",
        task = {
            scheduleService.logTask("Provider Enrichment Worker") {
                providerEnrichmentWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.DELETE_EMPTY_ALBUMS,
        name = "Delete Empty Albums",
        task = {
            scheduleService.logTask("Delete Empty Albums") {
                val count = albumService.deleteEmptyAlbums { p, l -> updateProgress(p, l) }
                mapOf("albumsDeleted" to count)
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.DELETE_UNREFERENCED_ARTISTS,
        name = "Delete Unreferenced Artists",
        task = {
            scheduleService.logTask("Delete Unreferenced Artists") {
                val count = artistService.deleteUnreferencedArtists { p, l -> updateProgress(p, l) }
                mapOf("artistsDeleted" to count)
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.DELETE_UNREFERENCED_IMAGES,
        name = "Delete Unreferenced Images",
        task = {
            scheduleService.logTask("Delete Unreferenced Images") {
                val count = imageService.deleteUnreferencedImages { p, l ->
                    updateProgress(p, l)
                }
                mapOf("imagesDeleted" to count)
            }
        }
    )

    scheduleService.registerManagedTask(
        key = TaskKeys.IMAGE_ANALYSIS,
        name = "Image Analysis",
        task = {
            scheduleService.logTask("Image Analysis") {
                imageAnalysisWorker.run { p, l -> updateProgress(p, l) }
            }
        }
    )

    scheduleService.triggerTask(TaskKeys.REVERSE_PROXY_HEALTH_CHECK)
    scheduleService.triggerTask(TaskKeys.MERGE_LIBRARY_DUPLICATES)
    scheduleService.triggerTask(TaskKeys.AUDIO_ANALYSIS)
    scheduleService.triggerTask(TaskKeys.FLAC_ANALYSIS)
    scheduleService.triggerTask(TaskKeys.MUSICBRAINZ_WORKER)
    scheduleService.triggerTask(TaskKeys.MUSICBRAINZ_CACHE_WORKER)
    scheduleService.triggerTask(TaskKeys.LYRICS_SYNC_WORKER)
    scheduleService.triggerTask(TaskKeys.LRCLIB_WORKER)
    scheduleService.triggerTask(TaskKeys.RECENT_RELEASE_WORKER)
    scheduleService.triggerTask(TaskKeys.IMAGE_ANALYSIS)
}
