package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.MetadataFetchingService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.GENRE_METADATA_WORKER, "Genre Metadata Worker")
class GenreMetadataWorker : Worker("GenreMetadataWorker") {
    private val metadataFetchingService by inject<MetadataFetchingService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return metadataFetchingService.fetchAllGenresWithMbId { p, m ->
            onProgress(p, m)
        }
    }
}
