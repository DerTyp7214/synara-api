package dev.dertyp.services.schedule

import dev.dertyp.data.TaskKeys
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.dbQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

@WorkerTask(TaskKeys.LOG_CLEANUP_WORKER, "Log Cleanup Worker")
class LogCleanupWorker : Worker("LogCleanupWorker") {

    override suspend fun execute(onProgress: suspend (Double, String) -> Unit): Map<String, Any?> {
        val thirtyDaysAgo = Clock.System.now().toEpochMilliseconds() - 30.days.inWholeMilliseconds
        
        val deletedCount = dbQuery {
            ScheduledTaskLogTable.deleteWhere {
                ScheduledTaskLogTable.logTime less thirtyDaysAgo
            }
        }

        return mapOf("deletedLogs" to deletedCount)
    }
}
