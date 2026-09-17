package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.PluginSettings
import io.ktor.server.config.ApplicationConfig

data class PodcastIndexCredentials(val apiKey: String, val apiSecret: String)

class PodcastIndexCredentialSource(
    private val settings: PluginSettings,
    private val config: ApplicationConfig,
) {
    suspend fun current(): PodcastIndexCredentials? {
        val values = settings.getAll()
        val apiKey = values[KEY_API_KEY]?.trim().orEmpty()
        val apiSecret = values[KEY_API_SECRET]?.trim().orEmpty()
        if (apiKey.isNotBlank() && apiSecret.isNotBlank()) return PodcastIndexCredentials(apiKey, apiSecret)
        return fromEnvironment()
    }

    suspend fun stored(): Boolean {
        val values = settings.getAll()
        return !values[KEY_API_KEY].isNullOrBlank() && !values[KEY_API_SECRET].isNullOrBlank()
    }

    fun fromEnvironment(): PodcastIndexCredentials? {
        val apiKey = config.propertyOrNull("podcastIndex.apiKey")?.getString()?.trim().orEmpty()
        val apiSecret = config.propertyOrNull("podcastIndex.apiSecret")?.getString()?.trim().orEmpty()
        if (apiKey.isBlank() || apiSecret.isBlank()) return null
        return PodcastIndexCredentials(apiKey, apiSecret)
    }

    companion object {
        const val KEY_API_KEY = "apiKey"
        const val KEY_API_SECRET = "apiSecret"
    }
}
