package dev.dertyp.services.ui

import dev.dertyp.config.ServerConfig
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.UiContribution
import dev.dertyp.services.UserService
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.credentials.CredentialServerConnectionSource
import dev.dertyp.services.credentials.LocalCredentialStore
import dev.dertyp.services.credentials.admin.CredentialServerAdminClient
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import dev.dertyp.services.cover.CoverGenerationService
import dev.dertyp.services.hue.HueService
import dev.dertyp.services.jobs.JobService
import dev.dertyp.services.ui.credentialserver.CREDENTIAL_SERVER_UI_SOURCE
import dev.dertyp.services.ui.credentialserver.CredentialServerUiContext

class CoreUiContributions(
    private val registry: UiRegistry,
    private val translationService: TranslationService,
    private val uiService: UiService,
    private val importerState: ImporterState,
    private val userService: UserService,
    private val jobService: JobService,
    private val coverGenerationService: CoverGenerationService,
    private val hueService: HueService,
    private val credentialServerAdmin: CredentialServerAdminClient,
    private val credentialServerConnection: CredentialServerConnectionSource,
    private val credentialProvider: CredentialProvider,
    private val remoteCredentials: RemoteCredentialProvider,
    private val localCredentials: LocalCredentialStore,
    private val serverConfig: ServerConfig,
    private val pluginManager: PluginManager,
) {
    fun contributions(): List<UiContribution> = listOf(
        ImporterPageContribution(importerState, uiService),
        ImporterSettingsPageContribution(importerState, uiService),
        ImporterQueuePageContribution(importerState, userService),
        ImporterLibraryEntryContribution(),
        ImporterHomeCardContribution(importerState),
        PlaylistCoverContribution(coverGenerationService, jobService),
        CollectionCoverContribution(coverGenerationService, jobService),
        HueSettingsEntryContribution(),
        HueSettingsContribution(hueService),
    )

    private fun credentialContributions(): List<UiContribution> =
        CredentialServerUiContext(
            admin = credentialServerAdmin,
            connection = credentialServerConnection,
            provider = credentialProvider,
            remote = remoteCredentials,
            localStore = localCredentials,
            serverConfig = serverConfig,
            pluginManager = pluginManager,
            translations = translationService,
        ).contributions()

    fun register() {
        val registrar = registry.forSource(UiRegistry.SERVER_SOURCE)
        contributions().forEach { registrar.register(it) }

        translationService.forSource(CREDENTIAL_SERVER_UI_SOURCE)
            .registerBundlesFromResources(javaClass.classLoader, "i18n/credentialserver", listOf("en", "de"))
        val credentialRegistrar = registry.forSource(CREDENTIAL_SERVER_UI_SOURCE)
        credentialContributions().forEach { credentialRegistrar.register(it) }
    }
}
