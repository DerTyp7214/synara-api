@file:OptIn(ExperimentalSerializationApi::class)

package dev.dertyp.services

import dev.dertyp.data.ScheduledTaskLog
import dev.dertyp.data.TaskStatus
import dev.dertyp.data.User
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.dbQuery
import dev.dertyp.serializers.AppCbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

class RpcScheduledTaskLogService(
    private val user: User,
    private val logService: ScheduledTaskLogService
) : IScheduledTaskLogService {
    override suspend fun getGroupedLogs(): Map<String, List<ScheduledTaskLog>> {
        if (!user.isAdmin) {
            throw SecurityException("Only admins can access scheduled task logs")
        }
        return logService.getGroupedLogs()
    }
}

class ScheduledTaskLogService : Service() {
    fun startLog(taskName: String, startTime: Long) = transaction {
        ScheduledTaskLogTable.insert {
            it[ScheduledTaskLogTable.taskName] = taskName
            it[ScheduledTaskLogTable.startTime] = startTime
            it[ScheduledTaskLogTable.endTime] = 0
            it[ScheduledTaskLogTable.status] = TaskStatus.RUNNING
        } get ScheduledTaskLogTable.id
    }

    fun logTask(
        taskName: String,
        startTime: Long,
        endTime: Long,
        status: TaskStatus,
        message: String? = null,
        details: Map<String, String>? = null,
        runningId: UUID? = null
    ) {
        transaction {
            if (runningId != null) {
                ScheduledTaskLogTable.deleteWhere { ScheduledTaskLogTable.id eq runningId }
            }

            ScheduledTaskLogTable.insert {
                it[ScheduledTaskLogTable.taskName] = taskName
                it[ScheduledTaskLogTable.startTime] = startTime
                it[ScheduledTaskLogTable.endTime] = endTime
                it[ScheduledTaskLogTable.status] = status
                it[ScheduledTaskLogTable.message] = message
                it[ScheduledTaskLogTable.details] = details?.let { AppCbor.encodeToByteArray(it) }
            }

            val taskLogs = ScheduledTaskLogTable.selectAll()
                .where { ScheduledTaskLogTable.taskName eq taskName }
                .orderBy(ScheduledTaskLogTable.logTime, SortOrder.DESC)
                .toList()

            if (taskLogs.size > 100) {
                val oldestToKeepTime = taskLogs[99][ScheduledTaskLogTable.logTime]
                ScheduledTaskLogTable.deleteWhere {
                    (ScheduledTaskLogTable.taskName eq taskName) and (logTime less oldestToKeepTime)
                }
            }
        }
    }

    suspend fun getGroupedLogs(): Map<String, List<ScheduledTaskLog>> = dbQuery {
        ScheduledTaskLogTable.selectAll()
            .orderBy(ScheduledTaskLogTable.logTime, SortOrder.DESC)
            .map {
                ScheduledTaskLog(
                    id = it[ScheduledTaskLogTable.id].value,
                    taskName = it[ScheduledTaskLogTable.taskName],
                    startTime = it[ScheduledTaskLogTable.startTime],
                    endTime = it[ScheduledTaskLogTable.endTime],
                    status = it[ScheduledTaskLogTable.status],
                    message = it[ScheduledTaskLogTable.message],
                    details = it[ScheduledTaskLogTable.details]?.let { bytes -> AppCbor.decodeFromByteArray<Map<String, String>>(bytes) },
                    logTime = it[ScheduledTaskLogTable.logTime]
                )
            }
            .groupBy { it.taskName }
    }
}
