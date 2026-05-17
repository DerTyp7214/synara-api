package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.services.AlbumService
import dev.dertyp.services.SongService
import kotlinx.coroutines.flow.toList
import org.koin.core.component.inject

class ProviderEnrichmentWorker : Worker("Provider Enrichment Worker") {
    private val albumService by inject<AlbumService>()
    private val songService by inject<SongService>()
    private val recentReleaseWorker by inject<RecentReleaseWorker>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        if (recentReleaseWorker.active) {
            logger.info("Skipping ProviderEnrichmentWorker because RecentReleaseWorker is running")
            return mapOf("skipped" to 1)
        }

        logger.info("Starting ProviderEnrichmentWorker")
        
        val albumIds = albumService.albumIdsForProviderEnrichment().toList()
        onProgress(0.0, "Enriching ${albumIds.size} albums first...")

        var albumsEnriched = 0
        runParallel(albumIds, baseThreadCount = 5, onItemProcessed = {
            albumsEnriched = it
            onProgress((it.toDouble() / albumIds.size) * 50.0, "Enriched $it/${albumIds.size} albums")
        }) { id ->
            if (recentReleaseWorker.active) return@runParallel
            try {
                albumService.enrichProviders(id, HttpClientPriority.LOW)
            } catch (e: Exception) {
                logger.error("Failed to enrich album $id", e)
            }
        }

        if (recentReleaseWorker.active) {
            logger.info("Aborting ProviderEnrichmentWorker because RecentReleaseWorker started")
            return mapOf("aborted" to 1, "albumsEnriched" to albumsEnriched)
        }

        val songIds = songService.songIdsForProviderEnrichment().toList()
        onProgress(50.0, "Enriching ${songIds.size} songs...")

        var songsEnriched = 0
        runParallel(songIds, baseThreadCount = 5, onItemProcessed = {
            songsEnriched = it
            onProgress(50.0 + (it.toDouble() / songIds.size) * 50.0, "Enriched $it/${songIds.size} songs")
        }) { id ->
            if (recentReleaseWorker.active) return@runParallel
            try {
                songService.enrichProviders(id, HttpClientPriority.LOW)
            } catch (e: Exception) {
                logger.error("Failed to enrich song $id", e)
            }
        }

        onProgress(100.0, "Finished provider enrichment")
        logger.info("Finished ProviderEnrichmentWorker")
        
        return mapOf("albumsEnriched" to albumsEnriched, "songsEnriched" to songsEnriched)
    }
}
