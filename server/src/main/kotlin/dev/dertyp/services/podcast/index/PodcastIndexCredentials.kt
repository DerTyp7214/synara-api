package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.PluginSettings
import dev.dertyp.services.credentials.CredentialCipher
import dev.dertyp.services.credentials.CredentialField
import dev.dertyp.services.credentials.CredentialSource
import io.ktor.server.config.ApplicationConfig

data class PodcastIndexCredentials(val apiKey: String, val apiSecret: String)

class PodcastIndexCredentialSource(
    settings: PluginSettings,
    config: ApplicationConfig,
    cipher: CredentialCipher,
) : CredentialSource<PodcastIndexCredentials>(settings, config, cipher) {
    override val fields: List<CredentialField> = listOf(
        CredentialField(KEY_API_KEY, "podcastIndex.apiKey"),
        CredentialField(KEY_API_SECRET, "podcastIndex.apiSecret"),
    )

    override fun build(values: Map<String, String>): PodcastIndexCredentials? {
        val apiKey = values[KEY_API_KEY] ?: return null
        val apiSecret = values[KEY_API_SECRET] ?: return null
        return PodcastIndexCredentials(apiKey, apiSecret)
    }

    companion object {
        const val PLUGIN_ID = "podcastindex"
        const val KEY_API_KEY = "apiKey"
        const val KEY_API_SECRET = "apiSecret"
    }
}
