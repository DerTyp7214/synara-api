package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.StorageService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.STORAGE_SIZE_REFRESH, "Storage Size Refresh")
class StorageSizeRefreshWorker : Worker("StorageSizeRefreshWorker") {
    private val storageService by inject<StorageService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return storageService.recomputeAll().mapKeys { it.key.name.lowercase() }
    }
}
