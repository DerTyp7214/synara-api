package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.SessionService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.SESSION_CLEANUP, "Session Cleanup")
class SessionCleanupWorker : Worker("SessionCleanupWorker") {
    private val sessionService by inject<SessionService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val count = sessionService.cleanupOldSessions { p, l -> onProgress(p, l) }
        return mapOf("sessionsDeleted" to count)
    }
}
