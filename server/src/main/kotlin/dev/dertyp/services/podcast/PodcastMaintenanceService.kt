package dev.dertyp.services.podcast

import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastImportState
import dev.dertyp.services.Service

class PodcastMaintenanceService(
    private val podcastService: PodcastService,
    private val importService: PodcastImportService
) : Service() {

    suspend fun queueImports(): Int {
        var queued = 0

        podcastService.feedShowsWithSubscribers()
            .filter { it.deliveryMode == PodcastDeliveryMode.IMPORT }
            .forEach { show ->
                val keep = show.keepEpisodes ?: DEFAULT_KEEP
                val candidates = podcastService.importCandidates(show.id, keep, PodcastImportService.MAX_ATTEMPTS)

                candidates.forEach { episode ->
                    podcastService.markImportState(episode.id, PodcastImportState.QUEUED)
                    queued++
                }
            }

        return queued
    }

    suspend fun enforceRetention(): Int {
        var deleted = 0

        podcastService.feedShowsWithSubscribers()
            .filter { it.deliveryMode == PodcastDeliveryMode.IMPORT && it.keepEpisodes != null }
            .forEach { show ->
                val keep = show.keepEpisodes ?: return@forEach
                podcastService.importedBeyond(show.id, keep).forEach { episode ->
                    importService.deleteFile(episode)
                    deleted++
                }
            }

        return deleted
    }

    suspend fun purgeOrphanedShows(olderThanMs: Long = ORPHAN_MAX_AGE_MS): Int {
        val shows = podcastService.orphanedFeedShows(olderThanMs)

        shows.forEach { show ->
            podcastService.episodesOfShow(show.id)
                .filter { it.filePath != null }
                .forEach { importService.deleteFile(it) }

            podcastService.deleteShow(show.id)
        }

        return shows.size
    }

    companion object {
        const val DEFAULT_KEEP = 5
        const val ORPHAN_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
