package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.ClientSettingsService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.CLIENT_SETTINGS_CLEANUP, "Client Settings Cleanup")
class ClientSettingsCleanupWorker : Worker("ClientSettingsCleanupWorker") {
    private val clientSettingsService by inject<ClientSettingsService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> =
        clientSettingsService.cleanup { p, l -> onProgress(p, l) }
}
