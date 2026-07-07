package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.sync.ListenBrainzService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.LISTENBRAINZ_SYNC, "ListenBrainz Sync")
class ListenBrainzSyncWorker : Worker("ListenBrainzSyncWorker") {
    private val listenBrainzService by inject<ListenBrainzService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        onProgress(0.0, "Syncing ListenBrainz accounts")
        val listens = listenBrainzService.syncAllAccounts(onProgress)
        return mapOf("listens" to listens)
    }
}
