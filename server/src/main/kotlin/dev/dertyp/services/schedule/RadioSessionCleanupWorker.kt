package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.RadioService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.RADIO_SESSION_CLEANUP, "Radio Session Cleanup")
class RadioSessionCleanupWorker : Worker("RadioSessionCleanupWorker") {
    private val radioService by inject<RadioService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val removed = radioService.cleanupExpiredSessions()
        return mapOf("sessionsRemoved" to removed)
    }
}
