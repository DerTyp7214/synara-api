package dev.dertyp.services.ui

import dev.dertyp.plugins.UiContribution
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreUiContributionsTest {

    @Test
    fun `every server ui contribution registers with a valid and unique id`() {
        val registry = spyk(UiRegistry())
        val contributions = CoreUiContributions(
            registry = registry,
            translationService = TranslationService(registry),
            uiService = mockk(relaxed = true),
            importerState = mockk(relaxed = true),
            userService = mockk(relaxed = true),
            jobService = mockk(relaxed = true),
            coverGenerationService = mockk(relaxed = true),
            hueService = mockk(relaxed = true),
            listenShareService = mockk(relaxed = true),
            credentialServerAdmin = mockk(relaxed = true),
            credentialServerConnection = mockk(relaxed = true),
            credentialProvider = mockk(relaxed = true),
            remoteCredentials = mockk(relaxed = true),
            localCredentials = mockk(relaxed = true),
            serverConfig = mockk(relaxed = true),
            pluginManager = mockk(relaxed = true),
        )

        contributions.register()

        val registered = mutableListOf<UiContribution>()
        verify { registry.register(capture(registered), any()) }
        val ids = registered.map { it.id }

        assertTrue(ids.isNotEmpty())
        ids.forEach { assertTrue(UiRegistry.ID_PATTERN.matches(it), "Invalid contribution id '$it'") }
        assertEquals(ids.size, ids.toSet().size, "Duplicate contribution ids: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        assertEquals(ids.toSet(), registry.all().map { it.contribution.id }.toSet())
    }
}
