package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.sync.ListenBackupService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.LISTEN_BACKUP, "Listen Backup")
class ListenBackupWorker : Worker("ListenBackupWorker") {
    private val listenBackupService by inject<ListenBackupService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        return listenBackupService.sync(onProgress)
    }
}
