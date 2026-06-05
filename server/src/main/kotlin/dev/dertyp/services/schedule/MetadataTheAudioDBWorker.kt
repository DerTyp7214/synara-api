package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.MetadataFetchingService
import dev.dertyp.services.metadata.IMetadataService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.FETCH_METADATA_THEAUDIODB, "Fetch Metadata (TheAudioDB)")
class MetadataTheAudioDBWorker : Worker("MetadataTheAudioDBWorker") {
    private val metadataFetchingService by inject<MetadataFetchingService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        metadataFetchingService.fetchMetadata(IMetadataService.MetadataType.theAudioDB) { p, l ->
            onProgress(p, l)
        }
        return emptyMap()
    }
}
