package dev.dertyp.plugins

import kotlinx.coroutines.flow.Flow

interface PluginSettings {
    suspend fun get(key: String): String?
    suspend fun getAll(): Map<String, String>
    suspend fun set(key: String, value: String?)
    suspend fun setAll(values: Map<String, String?>)
    fun changes(): Flow<Map<String, String>>
}
