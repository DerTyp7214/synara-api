package dev.dertyp.services.gamdl

import dev.dertyp.core.ClientInfo
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.data.ApiVersion
import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.services.ui.ServerUiRenderScope
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.testing.FakeCredentialProvider
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiSchemaVersion
import dev.dertyp.ui.UiValue
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class GamdlCredentialsContributionTest {
    private val admin = User(UUID.randomUUID(), "admin", passwordHash = "")
    private val translations = TranslationService(UiRegistry())
    init {
        translations.forSource(UiRegistry.SERVER_SOURCE).registerBundlesFromResources(javaClass.classLoader, "i18n/gamdl", listOf("en", "de"))
    }

    private val gamdlService =mockk<GamdlService>(relaxed = true) { every { tokenFileExists() } returns true }
    private val credentials = FakeCredentialProvider()
    private val contribution = GamdlCredentialsContribution(gamdlService, credentials)

    private fun scope() = ServerUiRenderScope(
        user = UserInfo.fromUser(admin),
        context = UiContext(),
        i18n = translations.translator(UiRegistry.SERVER_SOURCE, "en"),
        settings = mockk<PluginSettings>(relaxed = true),
        clientSchemaVersion = UiSchemaVersion.CURRENT,
        account = admin,
        client = ClientInfo(ApiVersion.CURRENT, UiSchemaVersion.CURRENT, "en"),
        call = null,
    )

    @Test
    fun `local credentials show the form`() = runBlocking {
        val card = contribution.render(scope()) as UiComponent.Card
        assertTrue(card.children.any { it is UiComponent.Form })
        assertTrue(card.children.none { it is UiComponent.Text })
    }

    @Test
    fun `remote credentials show the note and no form`() = runBlocking {
        credentials.markRemote(CredentialNames.IMPORTER_GAMDL)
        val card = contribution.render(scope()) as UiComponent.Card
        assertTrue(card.children.none { it is UiComponent.Form })
        assertEquals("Managed by the credential server", card.children.filterIsInstance<UiComponent.Text>().single().text)
    }

    @Test
    fun `remote credentials reject the save action`() = runBlocking {
        credentials.markRemote(CredentialNames.IMPORTER_GAMDL)
        val result = contribution.invoke(scope(), "save", mapOf("cookiesTxt" to UiValue.of("cookie")))
        assertEquals(UiInvokeStatus.ERROR, result.status)
        coVerify(exactly = 0) { gamdlService.provideCredentials(any()) }
    }

    @Test
    fun `local credentials accept the save action`() = runBlocking {
        val result = contribution.invoke(scope(), "save", mapOf("cookiesTxt" to UiValue.of("cookie")))
        assertEquals(UiInvokeStatus.OK, result.status)
        coVerify(exactly = 1) { gamdlService.provideCredentials(any()) }
    }
}
