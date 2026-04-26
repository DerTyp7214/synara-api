package dev.dertyp.services.schedule

import dev.dertyp.services.MetadataFetchingService
import org.koin.core.component.inject

class GenreMetadataWorker : Worker("GenreMetadataWorker") {
    private val metadataFetchingService by inject<MetadataFetchingService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        return metadataFetchingService.fetchAllGenresWithMbId { p, m ->
            onProgress(p, m)
        }
    }
}
