package dev.dertyp.services.ui.credentialserver

import dev.dertyp.plugins.PluginSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class InMemoryPluginSettings : PluginSettings {
    private val values = MutableStateFlow<Map<String, String>>(emptyMap())

    override suspend fun get(key: String): String? = values.value[key]

    override suspend fun getAll(): Map<String, String> = values.value

    override suspend fun set(key: String, value: String?) = setAll(mapOf(key to value))

    override suspend fun setAll(values: Map<String, String?>) {
        this.values.update { current ->
            val next = current.toMutableMap()
            values.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
            next
        }
    }

    override fun changes(): Flow<Map<String, String>> = values
}
