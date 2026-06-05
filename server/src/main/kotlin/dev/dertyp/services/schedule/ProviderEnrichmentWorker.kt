package dev.dertyp.services.schedule

import dev.dertyp.core.HttpClientPriority
import dev.dertyp.data.TaskKeys
import dev.dertyp.services.AlbumService
import dev.dertyp.services.SongService
import kotlinx.coroutines.flow.toList
import org.koin.core.component.inject

@WorkerTask(TaskKeys.PROVIDER_ENRICHMENT_WORKER, "Provider Enrichment Worker")
class ProviderEnrichmentWorker : Worker("Provider Enrichment Worker") {
    private val albumService by inject<AlbumService>()
    private val songService by inject<SongService>()
    private val recentReleaseWorker by inject<RecentReleaseWorker>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        if (recentReleaseWorker.active) {
            logger.info("Skipping ProviderEnrichmentWorker because RecentReleaseWorker is running")
            return mapOf("skipped" to 1)
        }

        logger.info("Starting ProviderEnrichmentWorker")

        var totalAlbumsEnriched = 0
        var songsEnriched = 0
        var singlesEnriched = 0

        val albumIds = albumService.albumIdsForProviderEnrichment(excludeSingles = true).toList()
        onProgress(0.0, "Enriching ${albumIds.size} albums first...")

        runParallel(albumIds, baseThreadCount = 5, onItemProcessed = {
            totalAlbumsEnriched = it
            onProgress((it.toDouble() / albumIds.size) * 33.3, "Enriched $it/${albumIds.size} albums")
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
            return mapOf("aborted" to 1, "albumsEnriched" to totalAlbumsEnriched)
        }

        val songIds = songService.songIdsForProviderEnrichment().toList()
        onProgress(33.3, "Enriching ${songIds.size} songs...")

        runParallel(songIds, baseThreadCount = 5, onItemProcessed = {
            songsEnriched = it
            onProgress(33.3 + (it.toDouble() / songIds.size) * 33.3, "Enriched $it/${songIds.size} songs")
        }) { id ->
            if (recentReleaseWorker.active) return@runParallel
            try {
                songService.enrichProviders(id, HttpClientPriority.LOW)
            } catch (e: Exception) {
                logger.error("Failed to enrich song $id", e)
            }
        }

        if (recentReleaseWorker.active) {
            logger.info("Aborting ProviderEnrichmentWorker because RecentReleaseWorker started")
            return mapOf(
                "aborted" to 1,
                "albumsEnriched" to totalAlbumsEnriched,
                "songsEnriched" to songsEnriched
            )
        }

        val singleIds = albumService.albumIdsForProviderEnrichment(onlySingles = true).toList()
        onProgress(66.6, "Enriching ${singleIds.size} singles...")

        runParallel(singleIds, baseThreadCount = 5, onItemProcessed = {
            singlesEnriched = it
            onProgress(66.6 + (it.toDouble() / singleIds.size) * 33.3, "Enriched $it/${singleIds.size} singles")
        }) { id ->
            if (recentReleaseWorker.active) return@runParallel
            try {
                albumService.enrichProviders(id, HttpClientPriority.LOW)
            } catch (e: Exception) {
                logger.error("Failed to enrich single $id", e)
            }
        }

        onProgress(100.0, "Finished provider enrichment")
        logger.info("Finished ProviderEnrichmentWorker")

        return mapOf(
            "albumsEnriched" to totalAlbumsEnriched + singlesEnriched,
            "songsEnriched" to songsEnriched,
            "singlesEnriched" to singlesEnriched
        )
    }
}
