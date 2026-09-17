package dev.dertyp.services.podcast.index

import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiSchemaVersion
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PodcastIndexCredentialsContributionTest {
    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val settings = mockk<PluginSettings>(relaxed = true)
    private val admin = User(UUID.randomUUID(), "root", displayName = "Root", passwordHash = "", isAdmin = true)

    init {
        translations.forSource(SOURCE).registerBundlesFromResources(javaClass.classLoader, "i18n/podcastindex", listOf("en", "de"))
    }

    private fun contribution(env: Map<String, String> = emptyMap()): PodcastIndexCredentialsContribution {
        val config = MapApplicationConfig()
        env.forEach { (key, value) -> config.put(key, value) }
        return PodcastIndexCredentialsContribution(PodcastIndexCredentialSource(settings, config), settings)
    }

    private fun scope() = UiRenderScope(
        user = UserInfo.fromUser(admin),
        context = UiContext(),
        i18n = translations.translator(SOURCE, "en"),
        settings = settings,
        clientSchemaVersion = UiSchemaVersion.CURRENT,
    )

    private fun UiComponent.Card.fields(): List<UiComponent.TextField> =
        children.filterIsInstance<UiComponent.Form>().single().children.filterIsInstance<UiComponent.TextField>()

    @Test
    fun `is an admin settings slot item`() {
        val contribution = contribution()
        assertEquals("podcastindex.credentials", contribution.id)
        assertEquals(UiSlots.SETTINGS, contribution.slot)
        assertEquals(70, contribution.order)
        assertTrue(contribution.access.requiresAdmin)
    }

    @Test
    fun `renders the missing badge and required fields when unconfigured`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()

        val card = contribution().render(scope()) as UiComponent.Card
        assertEquals("Podcast Index credentials", card.title)
        val badge = card.children.filterIsInstance<UiComponent.Badge>().single()
        assertEquals("Not configured", badge.text)
        assertEquals(UiTone.WARNING, badge.tone)
        val hint = card.children.filterIsInstance<UiComponent.Text>().single()
        assertTrue(hint.text.startsWith("Create a free API key"))

        val fields = card.fields()
        assertEquals(listOf("apiKey", "apiSecret"), fields.map { it.key })
        assertTrue(fields.all { it.secret && it.required })
        assertTrue(fields.all { it.value == null })
        assertTrue(card.actions.isEmpty())

        val form = card.children.filterIsInstance<UiComponent.Form>().single()
        assertEquals(UiAction.Invoke("podcastindex.credentials", "save", formId = "podcastindex"), form.submit)
    }

    @Test
    fun `renders the configured badge and the environment hint when only the environment is set`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()

        val card = contribution(mapOf("podcastIndex.apiKey" to "envKey", "podcastIndex.apiSecret" to "envSecret"))
            .render(scope()) as UiComponent.Card
        val badge = card.children.filterIsInstance<UiComponent.Badge>().single()
        assertEquals("Configured", badge.text)
        assertEquals(UiTone.SUCCESS, badge.tone)
        assertTrue(card.children.filterIsInstance<UiComponent.Text>().single().text.startsWith("Using the credentials from the server environment"))
        assertTrue(card.fields().all { it.required })
        assertTrue(card.actions.isEmpty())
    }

    @Test
    fun `renders the stored hint, optional fields and a clear action once stored`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf("apiKey" to "storedKey", "apiSecret" to "storedSecret")

        val card = contribution().render(scope()) as UiComponent.Card
        assertEquals("Using the credentials stored here.", card.children.filterIsInstance<UiComponent.Text>().single().text)
        assertTrue(card.fields().none { it.required })
        assertTrue(card.fields().all { it.value == null })
        val clear = card.actions.single() as UiComponent.Button
        assertEquals("Remove stored credentials", clear.label)
        assertEquals(UiAction.Invoke("podcastindex.credentials", "clear"), clear.action)
    }

    @Test
    fun `save stores both values and refreshes`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()

        val result = contribution().invoke(
            scope(),
            "save",
            mapOf("apiKey" to UiValue.of("  key123 "), "apiSecret" to UiValue.of("secret456")),
        )

        assertEquals(UiInvokeStatus.OK, result.status)
        assertEquals("Credentials saved", result.message)
        assertTrue(result.refresh)
        coVerify { settings.setAll(mapOf("apiKey" to "key123", "apiSecret" to "secret456")) }
    }

    @Test
    fun `save rejects empty and incomplete input`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()
        val contribution = contribution()

        val empty = contribution.invoke(scope(), "save", mapOf("apiKey" to UiValue.of("   "), "apiSecret" to UiValue.of("")))
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, empty.status)
        assertEquals("The API key is required.", empty.fieldErrors["apiKey"])

        val missingSecret = contribution.invoke(scope(), "save", mapOf("apiKey" to UiValue.of("key123")))
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, missingSecret.status)
        assertNotNull(missingSecret.fieldErrors["apiSecret"])
        assertNull(missingSecret.fieldErrors["apiKey"])

        coVerify(exactly = 0) { settings.setAll(any()) }
    }

    @Test
    fun `save accepts a single value once a pair is stored`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf("apiKey" to "storedKey", "apiSecret" to "storedSecret")

        val result = contribution().invoke(scope(), "save", mapOf("apiSecret" to UiValue.of("newSecret")))
        assertEquals(UiInvokeStatus.OK, result.status)
        coVerify { settings.setAll(mapOf("apiSecret" to "newSecret")) }
    }

    @Test
    fun `clear removes the stored values`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf("apiKey" to "storedKey", "apiSecret" to "storedSecret")

        val result = contribution().invoke(scope(), "clear", emptyMap())
        assertEquals(UiInvokeStatus.OK, result.status)
        assertEquals("Stored credentials removed", result.message)
        assertTrue(result.refresh)
        coVerify { settings.setAll(mapOf("apiKey" to null, "apiSecret" to null)) }
    }

    @Test
    fun `unknown actions are rejected`() = runBlocking {
        val result = contribution().invoke(scope(), "nope", emptyMap())
        assertEquals(UiInvokeStatus.ERROR, result.status)
        assertFalse(result.refresh)
    }

    companion object {
        private const val SOURCE = "podcastindex"
    }
}
