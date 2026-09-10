package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.QueueService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.QUEUE_CLEANUP, "Queue Cleanup")
class QueueCleanupWorker : Worker("QueueCleanupWorker") {
    private val queueService by inject<QueueService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val count = queueService.cleanupStaleQueues { p, l -> onProgress(p, l) }
        return mapOf("queuesCleared" to count)
    }
}
