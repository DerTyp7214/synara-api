package dev.dertyp.services.podcast

import dev.dertyp.data.PodcastDeliveryMode
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastRetention
import dev.dertyp.data.PodcastSource
import dev.dertyp.services.Service
import java.util.UUID

class PodcastMaintenanceService(
    private val podcastService: PodcastService,
    private val importService: PodcastImportService
) : Service() {

    suspend fun queueImports(): Int {
        var queued = 0

        podcastService.feedShowsWithSubscribers()
            .filter { it.deliveryMode == PodcastDeliveryMode.IMPORT }
            .forEach { show ->
                val candidates = when (show.retention) {
                    PodcastRetention.NEWEST -> {
                        val keep = show.keepEpisodes ?: DEFAULT_KEEP
                        podcastService.importCandidates(show.id, keep, PodcastImportService.MAX_ATTEMPTS)
                    }

                    PodcastRetention.UNLISTENED -> {
                        val unlistened = podcastService.unlistenedImportCandidates(show.id, PodcastImportService.MAX_ATTEMPTS)
                        val room = show.keepEpisodes
                            ?.let { it - podcastService.importedOrPendingCount(show.id) }
                            ?.coerceAtLeast(0)
                        if (room == null) unlistened else unlistened.take(room)
                    }
                }

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
            .filter { it.deliveryMode == PodcastDeliveryMode.IMPORT }
            .forEach { show ->
                val stale = when (show.retention) {
                    PodcastRetention.NEWEST -> {
                        val keep = show.keepEpisodes ?: return@forEach
                        podcastService.importedBeyond(show.id, keep)
                    }

                    PodcastRetention.UNLISTENED -> podcastService.finishedImportedEpisodes(show.id)
                }

                stale.forEach { episode ->
                    importService.deleteFile(episode)
                    deleted++
                }
            }

        return deleted
    }

    suspend fun purgeOrphanedShows(olderThanMs: Long = ORPHAN_MAX_AGE_MS): Int {
        val shows = podcastService.orphanedFeedShows(olderThanMs)

        shows.forEach { removeShow(it) }

        return shows.size
    }

    suspend fun deleteShow(showId: UUID): Boolean {
        val show = podcastService.showById(showId) ?: return false
        require(show.source == PodcastSource.FEED) { "Only feed shows can be removed" }

        removeShow(show)

        return true
    }

    private suspend fun removeShow(show: PodcastShowRow) {
        podcastService.episodesOfShow(show.id)
            .filter { it.filePath != null }
            .forEach { importService.deleteFile(it) }

        podcastService.deleteShow(show.id)
    }

    companion object {
        const val DEFAULT_KEEP = 5
        const val ORPHAN_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
