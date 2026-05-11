package dev.dertyp.services.schedule

import dev.dertyp.services.MetadataFetchingService
import org.koin.core.component.inject

class ArtistImageWorker : Worker("ArtistImageWorker") {
    private val metadataFetchingService by inject<MetadataFetchingService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Int> {
        return metadataFetchingService.fetchAllArtistImages { p, m ->
            onProgress(p, m)
        }
    }
}
