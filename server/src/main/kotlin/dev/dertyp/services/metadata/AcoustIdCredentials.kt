package dev.dertyp.services.metadata

import dev.dertyp.services.credentials.CredentialCipher
import dev.dertyp.services.credentials.CredentialField
import dev.dertyp.services.credentials.CredentialSource
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.UiRegistry
import io.ktor.server.config.ApplicationConfig

const val ACOUSTID_API_KEY_SETTING = "acoustid.apiKey"

class AcoustIdCredentialSource(
    settingsService: PluginSettingsService,
    config: ApplicationConfig,
    cipher: CredentialCipher,
) : CredentialSource<String>(settingsService.forPlugin(PLUGIN_ID), config, cipher) {
    override val fields: List<CredentialField> = listOf(CredentialField(KEY_API_KEY, "acoustid.apiKey"))

    override fun build(values: Map<String, String>): String? = values[KEY_API_KEY]

    companion object {
        const val PLUGIN_ID = UiRegistry.SERVER_SOURCE
        const val KEY_API_KEY = ACOUSTID_API_KEY_SETTING
    }
}
