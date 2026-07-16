package dev.dertyp.plugins

sealed class ApiKeyScope(val id: String, val name: String, val description: String) {
    object Radio : ApiKeyScope("radio", "Radio streaming", "Stream radio channels.")

    class Plugin(pluginId: String, id: String, name: String, description: String) : ApiKeyScope(
        if (id == pluginId || id.startsWith("$pluginId.")) id else "$pluginId.$id",
        name,
        description,
    )
}

interface ApiKeyScopeRegistrar {
    fun registerScope(scope: ApiKeyScope.Plugin)
}
