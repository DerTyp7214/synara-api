package dev.dertyp.services.schedule

import dev.dertyp.core.ApplicationScope
import dev.dertyp.data.TaskConfiguration
import dev.dertyp.data.TriggerDefinition
import dev.dertyp.db.ScheduledTaskConfigurationTable
import dev.dertyp.core.db.dbQuery
import dev.dertyp.services.IScheduledTaskConfigurationService
import dev.dertyp.services.Service
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert

class RpcScheduledTaskConfigurationService(
    private val configService: ScheduledTaskConfigurationService,
    private val scheduleService: ScheduleService,
) : IScheduledTaskConfigurationService {
    override suspend fun getConfigurations(): List<TaskConfiguration> {
        return configService.getConfigurations()
    }

    override suspend fun updateConfiguration(configuration: TaskConfiguration) {
        configService.updateConfiguration(configuration)
    }

    override fun getConfigurationsFlow(): Flow<List<TaskConfiguration>> {
        return configService.configurationsFlow
    }

    override suspend fun triggerTask(key: String): Boolean {
        return scheduleService.triggerTask(key)
    }
}

class ScheduledTaskConfigurationService : Service() {
    companion object {
        val DEFAULTS: List<TaskConfiguration>
            get() = WorkerTasks.defaults
    }

    private val _configurationsFlow = MutableSharedFlow<List<TaskConfiguration>>(replay = 1)
    val configurationsFlow: Flow<List<TaskConfiguration>> = _configurationsFlow.onStart {
        emit(getConfigurations())
    }

    suspend fun getConfigurations(): List<TaskConfiguration> = dbQuery {
        ScheduledTaskConfigurationTable.selectAll().map {
            TaskConfiguration(
                key = it[ScheduledTaskConfigurationTable.id].value,
                name = it[ScheduledTaskConfigurationTable.name],
                enabled = it[ScheduledTaskConfigurationTable.enabled],
                trigger = ApplicationScope.json.decodeFromString<TriggerDefinition>(it[ScheduledTaskConfigurationTable.trigger])
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    suspend fun updateConfiguration(configuration: TaskConfiguration) {
        dbQuery {
            ScheduledTaskConfigurationTable.upsert(ScheduledTaskConfigurationTable.id) {
                it[id] = configuration.key
                it[name] = configuration.name
                it[enabled] = configuration.enabled
                it[trigger] = ApplicationScope.json.encodeToString(configuration.trigger)
            }
        }
        _configurationsFlow.emit(getConfigurations())
    }

    suspend fun ensureDefaults(defaults: List<TaskConfiguration>) {
        val existing = getConfigurations().map { it.key }.toSet()
        defaults.filter { it.key !in existing }.forEach {
            updateConfiguration(it)
        }
    }
}
