package dev.dertyp.services.credentials

import dev.dertyp.core.db.dbQuery
import dev.dertyp.credentials.CredentialJson
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.db.PluginSettingTable
import dev.dertyp.services.ui.PluginSettingsService
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.select
import java.util.concurrent.ConcurrentHashMap

class LocalPluginCredentialStore(
    private val settingsService: PluginSettingsService,
    private val cipher: CredentialCipher,
) {
    private val logger = KtorSimpleLogger("LocalPluginCredentialStore")
    private val known = ConcurrentHashMap.newKeySet<String>()
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    suspend fun load() {
        val stored = dbQuery {
            PluginSettingTable.select(PluginSettingTable.pluginId, PluginSettingTable.key)
                .where { PluginSettingTable.key like "$KEY_PREFIX%" }
                .map { CredentialNames.plugin(it[PluginSettingTable.pluginId], it[PluginSettingTable.key].removePrefix(KEY_PREFIX)) }
        }
        known.addAll(stored)
    }

    fun has(fullName: String): Boolean = fullName in known

    suspend fun get(pluginId: String, name: String): ResolvedCredential? {
        val stored = settingsService.get(pluginId, settingKey(name)) ?: return null
        val plain = cipher.decrypt(aad(pluginId, name), stored) ?: return null
        return try {
            CredentialJson.json.decodeFromString<ResolvedCredential>(plain).also { known += aad(pluginId, name) }
        } catch (e: Exception) {
            logger.warn("Stored plugin credential ${aad(pluginId, name)} could not be read: ${e.message}")
            null
        }
    }

    suspend fun store(pluginId: String, name: String, credential: ResolvedCredential) {
        val plain = CredentialJson.json.encodeToString(ResolvedCredential.serializer(), credential)
        settingsService.setAll(pluginId, mapOf(settingKey(name) to cipher.encrypt(aad(pluginId, name), plain)))
        known += aad(pluginId, name)
        updates.emit(aad(pluginId, name))
    }

    suspend fun remove(pluginId: String, name: String) {
        settingsService.setAll(pluginId, mapOf(settingKey(name) to null))
        known -= aad(pluginId, name)
        updates.emit(aad(pluginId, name))
    }

    fun changes(): Flow<String> = updates.asSharedFlow()

    companion object {
        const val KEY_PREFIX = "credential."

        fun settingKey(name: String) = "$KEY_PREFIX$name"

        fun aad(pluginId: String, name: String) = CredentialNames.plugin(pluginId, name)
    }
}
