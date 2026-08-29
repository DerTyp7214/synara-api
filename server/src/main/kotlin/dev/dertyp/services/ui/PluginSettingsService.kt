package dev.dertyp.services.ui

import dev.dertyp.db.PluginSettingTable
import dev.dertyp.dbQuery
import dev.dertyp.plugins.PluginSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onStart
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class PluginSettingsService {
    private val flows = ConcurrentHashMap<String, MutableSharedFlow<Map<String, String>>>()

    private fun flowFor(pluginId: String) = flows.getOrPut(pluginId) { MutableSharedFlow(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST) }

    suspend fun getAll(pluginId: String): Map<String, String> = dbQuery {
        PluginSettingTable.selectAll()
            .where { PluginSettingTable.pluginId eq pluginId }
            .associate { it[PluginSettingTable.key] to it[PluginSettingTable.value] }
    }

    suspend fun get(pluginId: String, key: String): String? = dbQuery {
        PluginSettingTable.selectAll()
            .where { (PluginSettingTable.pluginId eq pluginId) and (PluginSettingTable.key eq key) }
            .firstOrNull()?.get(PluginSettingTable.value)
    }

    suspend fun setAll(pluginId: String, values: Map<String, String?>) {
        if (values.isEmpty()) return
        dbQuery {
            values.forEach { (settingKey, settingValue) ->
                if (settingValue == null) {
                    PluginSettingTable.deleteWhere { (PluginSettingTable.pluginId eq pluginId) and (PluginSettingTable.key eq settingKey) }
                } else {
                    PluginSettingTable.upsert(PluginSettingTable.pluginId, PluginSettingTable.key) {
                        it[PluginSettingTable.pluginId] = pluginId
                        it[key] = settingKey
                        it[value] = settingValue
                        it[updatedAt] = Instant.now().toEpochMilli()
                    }
                }
            }
        }
        flowFor(pluginId).emit(getAll(pluginId))
    }

    fun changes(pluginId: String): Flow<Map<String, String>> = flowFor(pluginId).onStart { emit(getAll(pluginId)) }

    fun forPlugin(pluginId: String): PluginSettings = object : PluginSettings {
        override suspend fun get(key: String): String? = this@PluginSettingsService.get(pluginId, key)
        override suspend fun getAll(): Map<String, String> = this@PluginSettingsService.getAll(pluginId)
        override suspend fun set(key: String, value: String?) = setAll(mapOf(key to value))
        override suspend fun setAll(values: Map<String, String?>) = this@PluginSettingsService.setAll(pluginId, values)
        override fun changes(): Flow<Map<String, String>> = this@PluginSettingsService.changes(pluginId)
    }
}
