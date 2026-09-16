package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.podcast.PodcastFeedService
import dev.dertyp.services.podcast.PodcastLocalScanService
import dev.dertyp.services.podcast.PodcastMaintenanceService
import dev.dertyp.services.podcast.PodcastService
import org.koin.core.component.inject
import java.util.concurrent.atomic.AtomicInteger

@WorkerTask(TaskKeys.PODCAST_REFRESH, "Podcast Refresh")
class PodcastRefreshWorker : Worker("PodcastRefreshWorker") {
    private val podcastService by inject<PodcastService>()
    private val feedService by inject<PodcastFeedService>()
    private val localScanService by inject<PodcastLocalScanService>()
    private val maintenanceService by inject<PodcastMaintenanceService>()
    private val scheduleService by inject<ScheduleService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val shows = podcastService.feedShowsWithSubscribers()
        val total = shows.size.coerceAtLeast(1)

        val changed = AtomicInteger(0)
        val notModified = AtomicInteger(0)
        val errors = AtomicInteger(0)
        val inserted = AtomicInteger(0)
        val updated = AtomicInteger(0)

        onProgress(0.0, "Refreshing ${shows.size} podcast feeds")

        runParallel(
            items = shows,
            baseThreadCount = 3,
            onItemProcessed = { processed -> onProgress(processed * 60.0 / total, "Refreshed $processed of ${shows.size} feeds") }
        ) { show ->
            val outcome = feedService.refreshShow(show.id, force = true)
            when {
                outcome.error != null -> errors.incrementAndGet()
                outcome.changed -> {
                    changed.incrementAndGet()
                    inserted.addAndGet(outcome.inserted)
                    updated.addAndGet(outcome.updated)
                }

                else -> notModified.incrementAndGet()
            }
        }

        onProgress(60.0, "Scanning the local podcast library")
        val scan = localScanService.scan { progress, label -> onProgress(60.0 + progress * 0.1, label) }

        onProgress(70.0, "Queueing new episodes")
        val queued = maintenanceService.queueImports()
        if (queued > 0) scheduleService.triggerTask(TaskKeys.PODCAST_IMPORT)

        onProgress(80.0, "Enforcing episode retention")
        val retentionDeleted = maintenanceService.enforceRetention()

        onProgress(90.0, "Purging orphaned shows")
        val purgedShows = maintenanceService.purgeOrphanedShows()

        return mapOf(
            "shows" to shows.size,
            "changed" to changed.get(),
            "notModified" to notModified.get(),
            "errors" to errors.get(),
            "episodesInserted" to inserted.get(),
            "episodesUpdated" to updated.get(),
            "queued" to queued,
            "retentionDeleted" to retentionDeleted,
            "purgedShows" to purgedShows,
            "localShows" to scan.shows,
            "localEpisodesAdded" to scan.episodesAdded,
            "localEpisodesUpdated" to scan.episodesUpdated,
            "localEpisodesRemoved" to scan.episodesRemoved,
            "localShowsRemoved" to scan.showsRemoved
        )
    }
}
