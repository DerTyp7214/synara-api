package dev.dertyp.services

import dev.dertyp.data.ApiKeyScopeInfo
import dev.dertyp.plugins.ApiKeyScope
import dev.dertyp.plugins.ApiKeyScopeRegistrar
import io.ktor.util.logging.KtorSimpleLogger
import java.util.concurrent.ConcurrentHashMap

class ApiKeyScopeRegistry {
    private val logger = KtorSimpleLogger("ApiKeyScopeRegistry")
    private val scopes = ConcurrentHashMap<String, ApiKeyScopeInfo>()

    init {
        register(ApiKeyScope.Radio, SERVER_SOURCE)
    }

    fun register(scope: ApiKeyScope, source: String) {
        val existing = scopes.putIfAbsent(scope.id, ApiKeyScopeInfo(scope.id, scope.name, scope.description, source))
        if (existing != null) {
            logger.warn("Ignoring duplicate API key scope registration: ${scope.id} (from $source)")
        }
    }

    fun all(): List<ApiKeyScopeInfo> = scopes.values.sortedBy { it.id }

    fun contains(id: String): Boolean = scopes.containsKey(id)

    fun forPlugin(pluginId: String): ApiKeyScopeRegistrar = object : ApiKeyScopeRegistrar {
        override fun registerScope(scope: ApiKeyScope.Plugin) {
            register(scope, pluginId)
        }
    }

    companion object {
        const val SERVER_SOURCE = "server"
    }
}
