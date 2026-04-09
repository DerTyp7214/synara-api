@file:OptIn(ExperimentalSerializationApi::class, FlowPreview::class)

package dev.dertyp.services

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.ScheduledTaskLog
import dev.dertyp.data.TaskStatus
import dev.dertyp.data.User
import dev.dertyp.db.ScheduledTaskLogTable
import dev.dertyp.dbQuery
import dev.dertyp.serializers.AppCbor
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
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

    override fun getGroupedLogsFlow(): Flow<Map<String, List<ScheduledTaskLog>>> {
        if (!user.isAdmin) {
            throw SecurityException("Only admins can access scheduled task logs")
        }
        return logService.groupedLogsFlow
    }
}

class ScheduledTaskLogService : Service() {
    private val updateTrigger = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val groupedLogsFlow: Flow<Map<String, List<ScheduledTaskLog>>> = updateTrigger
        .onStart { emit(Unit) }
        .sample(1.seconds)
        .map { getGroupedLogs() }

    private fun triggerUpdate() {
        updateTrigger.tryEmit(Unit)
    }

    fun startLog(taskName: String, startTime: Long) = transaction {
        val id = ScheduledTaskLogTable.insert {
            it[ScheduledTaskLogTable.taskName] = taskName
            it[ScheduledTaskLogTable.startTime] = startTime
            it[ScheduledTaskLogTable.endTime] = 0
            it[ScheduledTaskLogTable.status] = TaskStatus.RUNNING
        } get ScheduledTaskLogTable.id
        triggerUpdate()
        id
    }

    fun updateProgress(runningId: UUID, progress: Double, logs: List<String>) = transaction {
        ScheduledTaskLogTable.update({ ScheduledTaskLogTable.id eq runningId }) {
            it[ScheduledTaskLogTable.progress] = progress
            it[ScheduledTaskLogTable.logs] = ApplicationScope.json.encodeToString(logs)
        }
        triggerUpdate()
    }

    fun logTask(
        taskName: String,
        startTime: Long,
        endTime: Long,
        status: TaskStatus,
        message: String? = null,
        details: Map<String, String>? = null,
        progress: Double = 0.0,
        logs: List<String> = emptyList(),
        runningId: UUID? = null
    ) {
        transaction {
            if (runningId != null) {
                ScheduledTaskLogTable.update({ ScheduledTaskLogTable.id eq runningId }) {
                    it[ScheduledTaskLogTable.taskName] = taskName
                    it[ScheduledTaskLogTable.startTime] = startTime
                    it[ScheduledTaskLogTable.endTime] = endTime
                    it[ScheduledTaskLogTable.status] = status
                    it[ScheduledTaskLogTable.message] = message
                    it[ScheduledTaskLogTable.details] = details?.let { details -> AppCbor.encodeToByteArray(details) }
                    it[ScheduledTaskLogTable.progress] = progress
                    it[ScheduledTaskLogTable.logs] = ApplicationScope.json.encodeToString(logs)
                    it[ScheduledTaskLogTable.logTime] = Instant.now().toEpochMilli()
                }
            } else {
                ScheduledTaskLogTable.insert {
                    it[ScheduledTaskLogTable.taskName] = taskName
                    it[ScheduledTaskLogTable.startTime] = startTime
                    it[ScheduledTaskLogTable.endTime] = endTime
                    it[ScheduledTaskLogTable.status] = status
                    it[ScheduledTaskLogTable.message] = message
                    it[ScheduledTaskLogTable.details] = details?.let { details -> AppCbor.encodeToByteArray(details) }
                    it[ScheduledTaskLogTable.progress] = progress
                    it[ScheduledTaskLogTable.logs] = ApplicationScope.json.encodeToString(logs)
                }
            }

            val taskLogs = ScheduledTaskLogTable.selectAll()
                .where { ScheduledTaskLogTable.taskName eq taskName }
                .orderBy(ScheduledTaskLogTable.logTime, SortOrder.DESC)
                .toList()

            if (taskLogs.size > 100) {
                val idsToKeep = taskLogs.take(100).map { it[ScheduledTaskLogTable.id] }
                ScheduledTaskLogTable.deleteWhere {
                    (ScheduledTaskLogTable.taskName eq taskName) and (id notInList idsToKeep)
                }
            }
        }
        triggerUpdate()
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
                    progress = it[ScheduledTaskLogTable.progress],
                    logs = try {
                        ApplicationScope.json.decodeFromString<List<String>>(it[ScheduledTaskLogTable.logs])
                    } catch (_: Exception) {
                        emptyList()
                    },
                    logTime = it[ScheduledTaskLogTable.logTime]
                )
            }
            .groupBy { it.taskName }
    }

    suspend fun cleanupRunningLogs() = dbQuery {
        logger.info("Cleaning up stuck running task logs")
        ScheduledTaskLogTable.deleteWhere { status eq TaskStatus.RUNNING }
        triggerUpdate()
    }
}
