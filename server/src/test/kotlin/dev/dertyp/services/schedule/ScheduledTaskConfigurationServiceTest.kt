package dev.dertyp.services.schedule

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TaskKeys
import dev.dertyp.data.TriggerDefinition
import dev.dertyp.db.ScheduledTaskConfigurationTable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class ScheduledTaskConfigurationServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var service: ScheduledTaskConfigurationService

    @BeforeEach
    fun setupKoin() {
        service = ScheduledTaskConfigurationService()
        startKoin {
            modules(module {
                single { service }
            })
        }
    }

    fun setup(dialect: DbDialect) {
        database = TestDatabase.connect(dialect, "task_config_test")
        transaction(database) {
            SchemaUtils.create(ScheduledTaskConfigurationTable)
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
    fun `updateConfiguration should insert and update configurations`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val config = TaskConfiguration(
            key = TaskKeys.DATABASE_BACKUP,
            name = "Database Backup",
            enabled = true,
            trigger = TriggerDefinition.Cron("0 2 * * *")
        )

        service.updateConfiguration(config)

        var configs = service.getConfigurations()
        assertEquals(1, configs.size)
        assertEquals(config.key, configs[0].key)
        assertEquals(config.name, configs[0].name)
        assertEquals(config.enabled, configs[0].enabled)
        assertEquals(config.trigger, configs[0].trigger)

        val updatedConfig = config.copy(enabled = false, trigger = TriggerDefinition.Interval(3600))
        service.updateConfiguration(updatedConfig)

        configs = service.getConfigurations()
        assertEquals(1, configs.size)
        assertEquals(updatedConfig.key, configs[0].key)
        assertEquals(updatedConfig.enabled, configs[0].enabled)
        assertEquals(updatedConfig.trigger, configs[0].trigger)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `ensureDefaults should only insert missing configurations`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val existingConfig = TaskConfiguration(
            key = TaskKeys.DATABASE_BACKUP,
            name = "Existing Backup",
            enabled = false,
            trigger = TriggerDefinition.Cron("0 0 * * *")
        )
        service.updateConfiguration(existingConfig)

        val defaults = listOf(
            existingConfig.copy(name = "Default Backup", enabled = true),
            TaskConfiguration(TaskKeys.AUDIO_ANALYSIS, "Audio Analysis", true, TriggerDefinition.Cron("0 3 * * *"))
        )

        service.ensureDefaults(defaults)

        val configs = service.getConfigurations().associateBy { it.key }
        assertEquals(2, configs.size)
        assertEquals("Existing Backup", configs[TaskKeys.DATABASE_BACKUP]?.name)
        assertEquals(false, configs[TaskKeys.DATABASE_BACKUP]?.enabled)
        assertEquals("Audio Analysis", configs[TaskKeys.AUDIO_ANALYSIS]?.name)
    }
}
