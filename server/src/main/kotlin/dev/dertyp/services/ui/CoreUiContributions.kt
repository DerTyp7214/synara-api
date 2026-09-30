package dev.dertyp.services.ui

import dev.dertyp.plugins.UiContribution
import dev.dertyp.services.UserService
import dev.dertyp.services.cover.CoverGenerationService
import dev.dertyp.services.hue.HueService
import dev.dertyp.services.jobs.JobService
import dev.dertyp.services.metadata.ACOUSTID_UI_SOURCE
import dev.dertyp.services.metadata.AcoustIdCredentialSource
import dev.dertyp.services.metadata.AcoustIdCredentialsContribution
import dev.dertyp.services.metadata.AcoustIdCredentialsEntryContribution

class CoreUiContributions(
    private val registry: UiRegistry,
    private val translationService: TranslationService,
    private val uiService: UiService,
    private val importerState: ImporterState,
    private val userService: UserService,
    private val jobService: JobService,
    private val coverGenerationService: CoverGenerationService,
    private val hueService: HueService,
    private val acoustIdCredentials: AcoustIdCredentialSource,
    private val pluginSettingsService: PluginSettingsService,
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

    private fun acoustIdContributions(): List<UiContribution> = listOf(
        AcoustIdCredentialsEntryContribution(acoustIdCredentials, pluginSettingsService),
        AcoustIdCredentialsContribution(acoustIdCredentials, pluginSettingsService),
    )

    fun register() {
        val registrar = registry.forSource(UiRegistry.SERVER_SOURCE)
        contributions().forEach { registrar.register(it) }

        translationService.forSource(ACOUSTID_UI_SOURCE)
            .registerBundlesFromResources(javaClass.classLoader, "i18n/acoustid", listOf("en", "de"))
        val acoustIdRegistrar = registry.forSource(ACOUSTID_UI_SOURCE)
        acoustIdContributions().forEach { acoustIdRegistrar.register(it) }
    }
}
