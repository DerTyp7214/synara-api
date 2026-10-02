package dev.dertyp.plugins

import dev.dertyp.credentials.ResolvedCredential

interface PluginCredentials {
    val managedRemotely: Boolean
    suspend fun get(name: String): ResolvedCredential?
    suspend fun store(name: String, credential: ResolvedCredential)
    suspend fun remove(name: String)
}
