package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.podcast.PodcastImportService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.PODCAST_IMPORT, "Podcast Import")
class PodcastImportWorker : Worker("PodcastImportWorker") {
    private val importService by inject<PodcastImportService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> =
        importService.processQueue(2) { progress, label -> onProgress(progress, label) }
}
