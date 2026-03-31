package dev.dertyp.services

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.TaskStatus
import dev.dertyp.db.ScheduledTaskLogTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ScheduledTaskLogServiceTest {
    private lateinit var database: Database
    private val service = ScheduledTaskLogService()

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "task_log_test")
        transaction(database) {
            SchemaUtils.create(ScheduledTaskLogTable)
        }
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanUp()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `startLog should create a running entry`(dialect: DbDialect) {
        setup(dialect)
        val taskName = "test-task"
        val startTime = System.currentTimeMillis()

        val id = service.startLog(taskName, startTime)

        transaction(database) {
            val entry = ScheduledTaskLogTable.selectAll().where { ScheduledTaskLogTable.id eq id }.single()
            assertEquals(taskName, entry[ScheduledTaskLogTable.taskName])
            assertEquals(startTime, entry[ScheduledTaskLogTable.startTime])
            assertEquals(TaskStatus.RUNNING, entry[ScheduledTaskLogTable.status])
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `logTask should replace running entry and limit to 100`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val taskName = "limit-task"

        val runningId = service.startLog(taskName, System.currentTimeMillis()).value

        service.logTask(
            taskName = taskName,
            startTime = System.currentTimeMillis() - 1000,
            endTime = System.currentTimeMillis(),
            status = TaskStatus.SUCCESS,
            runningId = runningId
        )

        transaction(database) {
            assertEquals(1, ScheduledTaskLogTable.selectAll().where { ScheduledTaskLogTable.taskName eq taskName }.count())
        }

        for (i in 1..105) {
            service.logTask(
                taskName = taskName,
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis(),
                status = TaskStatus.SUCCESS
            )
        }

        transaction(database) {
            val count = ScheduledTaskLogTable.selectAll().where { ScheduledTaskLogTable.taskName eq taskName }.count()
            assertEquals(100, count)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getGroupedLogs should return logs grouped by task name`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        service.logTask("task1", 0, 0, TaskStatus.SUCCESS)
        service.logTask("task1", 1, 1, TaskStatus.SUCCESS)
        service.logTask("task2", 2, 2, TaskStatus.SUCCESS)

        val grouped = service.getGroupedLogs()
        assertEquals(2, grouped.size)
        assertEquals(2, grouped["task1"]?.size)
        assertEquals(1, grouped["task2"]?.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `cleanupRunningLogs should remove running entries`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        service.startLog("task1", 0)
        service.logTask("task2", 1, 1, TaskStatus.SUCCESS)

        service.cleanupRunningLogs()

        transaction(database) {
            val logs = ScheduledTaskLogTable.selectAll().toList()
            assertEquals(1, logs.size)
            assertEquals(TaskStatus.SUCCESS, logs[0][ScheduledTaskLogTable.status])
        }
    }
}
