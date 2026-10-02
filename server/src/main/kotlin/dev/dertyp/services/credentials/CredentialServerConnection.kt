package dev.dertyp.services.credentials

import dev.dertyp.config.CredentialServerConfig
import dev.dertyp.services.ui.PluginSettingsService
import io.ktor.server.config.ApplicationConfig

data class CredentialServerConnection(
    val url: String?,
    val clientId: String?,
    val clientSecret: String?,
    val adminKey: String?,
) {
    val consumerConfigured: Boolean
        get() = !url.isNullOrBlank() && !clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()

    val adminConfigured: Boolean
        get() = !url.isNullOrBlank() && !adminKey.isNullOrBlank()

    val baseUrl: String?
        get() = url?.trim()?.trimEnd('/')?.ifBlank { null }

    companion object {
        val NONE = CredentialServerConnection(null, null, null, null)
    }
}

class CredentialServerConnectionSource(
    settingsService: PluginSettingsService,
    config: ApplicationConfig,
    cipher: CredentialCipher,
) : CredentialSource<CredentialServerConnection>(settingsService.forPlugin(PLUGIN_ID), config, cipher) {
    override val fields: List<CredentialField> = listOf(
        CredentialField(KEY_URL, CredentialServerConfig.URL_PATH),
        CredentialField(KEY_CLIENT_ID, CredentialServerConfig.CLIENT_ID_PATH),
        CredentialField(KEY_CLIENT_SECRET, CredentialServerConfig.CLIENT_SECRET_PATH),
        CredentialField(KEY_ADMIN_KEY, CredentialServerConfig.ADMIN_KEY_PATH),
    )

    override fun build(values: Map<String, String>): CredentialServerConnection? {
        val url = values[KEY_URL] ?: return null
        return CredentialServerConnection(
            url = url,
            clientId = values[KEY_CLIENT_ID],
            clientSecret = values[KEY_CLIENT_SECRET],
            adminKey = values[KEY_ADMIN_KEY],
        )
    }

    companion object {
        const val PLUGIN_ID = "credentialserver"
        const val KEY_URL = "credentialserver.url"
        const val KEY_CLIENT_ID = "credentialserver.clientId"
        const val KEY_CLIENT_SECRET = "credentialserver.clientSecret"
        const val KEY_ADMIN_KEY = "credentialserver.adminKey"
    }
}
