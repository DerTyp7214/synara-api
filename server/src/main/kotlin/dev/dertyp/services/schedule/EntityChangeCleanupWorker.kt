package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.services.EntityChangeService
import org.koin.core.component.inject

@WorkerTask(TaskKeys.ENTITY_CHANGE_CLEANUP_WORKER, "Entity Change Cleanup Worker", cron = "15 0 * * *")
class EntityChangeCleanupWorker : Worker("EntityChangeCleanupWorker") {
    private val entityChangeService by inject<EntityChangeService>()

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> =
        entityChangeService.cleanup()
}
