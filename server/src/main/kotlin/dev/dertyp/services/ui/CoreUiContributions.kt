package dev.dertyp.services.ui

import dev.dertyp.plugins.UiContribution
import dev.dertyp.services.UserService
import dev.dertyp.services.import.ImportService
import dev.dertyp.services.import.ImporterProxy
import dev.dertyp.services.intake.IntakeService
import dev.dertyp.services.jobs.JobService

class CoreUiContributions(
    private val registry: UiRegistry,
    private val uiService: UiService,
    private val importService: ImportService,
    private val importerProxy: ImporterProxy,
    private val userService: UserService,
    private val intakeService: IntakeService,
    private val jobService: JobService,
) {
    private val importerState by lazy { ImporterState(importService, importerProxy, intakeService, jobService) }

    fun contributions(): List<UiContribution> = listOf(
        ImporterPageContribution(importerState, uiService),
        ImporterSettingsPageContribution(importerState, uiService),
        ImporterQueuePageContribution(importerState, userService),
        ImporterLibraryEntryContribution(),
        ImporterHomeCardContribution(importerState),
    )

    fun register() {
        val registrar = registry.forSource(UiRegistry.SERVER_SOURCE)
        contributions().forEach { registrar.register(it) }
    }
}
