package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TaskKeys
import dev.dertyp.data.TriggerDefinition
import dev.dertyp.db.ScheduledTaskConfigurationTable
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.inject
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScheduleServiceConfigIntegrationTest : KoinTest {
    private lateinit var database: Database
    private val scheduleService by inject<ScheduleService>()
    private val configService by inject<ScheduledTaskConfigurationService>()

    fun setupDb(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "schedule_config_integration_test")
        transaction(database) {
            SchemaUtils.create(ScheduledTaskConfigurationTable)
        }
    }

    @BeforeEach
    fun setup() {
        startKoin {
            modules(module {
                single { ScheduleService() }
                single { ScheduledTaskConfigurationService() }
            })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        ApplicationScope.scope.coroutineContext.cancelChildren()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ScheduleService should schedule tasks based on initial config`(dialect: DbDialect) = runBlocking {
        setupDb(dialect)
        
        val executed = CompletableDeferred<Unit>()

        scheduleService.registerManagedTask(
            key = TaskKeys.DATABASE_BACKUP,
            name = "Test Task",
            task = { executed.complete(Unit) }
        )

        configService.updateConfiguration(TaskConfiguration(
            key = TaskKeys.DATABASE_BACKUP,
            name = "Test Task",
            enabled = true,
            trigger = TriggerDefinition.Interval(1)
        ))

        val job = launch { scheduleService.startService() }

        withTimeout(5.seconds) {
            executed.await()
        }

        scheduleService.stopService()
        job.join()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ScheduleService should update tasks when config changes`(dialect: DbDialect) = runBlocking {
        setupDb(dialect)
        
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        
        scheduleService.registerManagedTask(
            key = "dynamic-task",
            name = "Dynamic Task",
            task = { count.incrementAndGet() }
        )

        configService.updateConfiguration(TaskConfiguration(
            key = "dynamic-task",
            name = "Dynamic Task",
            enabled = false,
            trigger = TriggerDefinition.Interval(1)
        ))

        val job = launch { scheduleService.startService() }
        
        delay(2.seconds)
        assertEquals(0, count.get(), "Task should not run when disabled")

        configService.updateConfiguration(TaskConfiguration(
            key = "dynamic-task",
            name = "Dynamic Task",
            enabled = true,
            trigger = TriggerDefinition.Interval(1)
        ))

        withTimeout(10.seconds) {
            while (count.get() == 0) delay(100.milliseconds)
        }
        assertTrue(count.get() > 0, "Task should start running after being enabled")

        scheduleService.stopService()
        job.join()
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ScheduleService should handle AfterTask trigger from config`(dialect: DbDialect) = runBlocking {
        setupDb(dialect)
        
        val firstExecuted = CompletableDeferred<Unit>()
        val secondExecuted = CompletableDeferred<Unit>()

        scheduleService.registerManagedTask(
            key = "task-a",
            name = "Task A",
            task = { firstExecuted.complete(Unit) }
        )

        scheduleService.registerManagedTask(
            key = "task-b",
            name = "Task B",
            task = { secondExecuted.complete(Unit) }
        )

        configService.updateConfiguration(TaskConfiguration(
            key = "task-a",
            name = "Task A",
            enabled = true,
            trigger = TriggerDefinition.Manual
        ))

        configService.updateConfiguration(TaskConfiguration(
            key = "task-b",
            name = "Task B",
            enabled = true,
            trigger = TriggerDefinition.AfterTask("task-a")
        ))

        val job = launch { scheduleService.startService() }
        
        delay(500.milliseconds)

        scheduleService.triggerTask("task-a")

        withTimeout(5.seconds) {
            firstExecuted.await()
            secondExecuted.await()
        }

        scheduleService.stopService()
        job.join()
    }
}
