package dev.dertyp.services.credentials

import dev.dertyp.plugins.PluginSettings
import io.ktor.server.config.ApplicationConfig
import kotlinx.coroutines.flow.Flow

enum class CredentialOrigin { STORED, ENVIRONMENT, UNREADABLE, NONE }

data class CredentialField(val settingKey: String, val configPath: String)

abstract class CredentialSource<T : Any>(
    protected val settings: PluginSettings,
    protected val config: ApplicationConfig,
    protected val cipher: CredentialCipher,
) {
    abstract val fields: List<CredentialField>

    protected abstract fun build(values: Map<String, String>): T?

    suspend fun current(): T? = stored() ?: fromEnvironment()

    suspend fun origin(): CredentialOrigin {
        val raw = storedRaw()
        val decrypted = decrypt(raw)
        return when {
            build(decrypted) != null -> CredentialOrigin.STORED
            raw.keys.any { it !in decrypted } -> CredentialOrigin.UNREADABLE
            fromEnvironment() != null -> CredentialOrigin.ENVIRONMENT
            else -> CredentialOrigin.NONE
        }
    }

    suspend fun stored(): T? = build(decrypt(storedRaw()))

    fun fromEnvironment(): T? = build(
        fields.mapNotNull { field ->
            config.propertyOrNull(field.configPath)?.getString()?.trim()?.takeIf { it.isNotEmpty() }?.let { field.settingKey to it }
        }.toMap()
    )

    suspend fun store(values: Map<String, String?>) {
        val known = fields.map { it.settingKey }.toSet()
        val updates = values.filterKeys { it in known }.mapValues { (settingKey, value) ->
            value?.trim()?.takeIf { it.isNotEmpty() }?.let { cipher.encrypt(settingKey, it) }
        }
        if (updates.isNotEmpty()) settings.setAll(updates)
    }

    suspend fun clear() {
        settings.setAll(fields.associate { it.settingKey to null })
    }

    fun changes(): Flow<Map<String, String>> = settings.changes()

    private suspend fun storedRaw(): Map<String, String> {
        val all = settings.getAll()
        return fields.mapNotNull { field -> all[field.settingKey]?.takeIf { it.isNotBlank() }?.let { field.settingKey to it } }.toMap()
    }

    private fun decrypt(raw: Map<String, String>): Map<String, String> =
        raw.mapNotNull { (settingKey, value) ->
            cipher.decrypt(settingKey, value)?.trim()?.takeIf { it.isNotEmpty() }?.let { settingKey to it }
        }.toMap()
}
