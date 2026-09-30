package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.MetadataFetchingService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.ARTIST_IMAGE_WORKER, "Artist Image Worker", afterTask = TaskKeys.GENRE_METADATA_WORKER)
class ArtistImageWorker : Worker("ArtistImageWorker") {
    private val metadataFetchingService by inject<MetadataFetchingService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return metadataFetchingService.fetchAllArtistImages { p, m ->
            onProgress(p, m)
        }
    }
}
