package dev.dertyp.services.metadata

import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.credentials.CredentialCipher
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.UiAction
import dev.dertyp.ui.UiComponent
import dev.dertyp.ui.UiContext
import dev.dertyp.ui.UiContributionKind
import dev.dertyp.ui.UiInvokeStatus
import dev.dertyp.ui.UiSchemaVersion
import dev.dertyp.ui.UiSlots
import dev.dertyp.ui.UiTone
import dev.dertyp.ui.UiValue
import io.ktor.server.config.MapApplicationConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AcoustIdCredentialsContributionTest {
    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val settings = mockk<PluginSettings>(relaxed = true)
    private val settingsService = mockk<PluginSettingsService> {
        every { forPlugin(UiRegistry.SERVER_SOURCE) } returns settings
    }
    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))
    private val admin = User(UUID.randomUUID(), "root", displayName = "Root", passwordHash = "", isAdmin = true)

    init {
        translations.forSource(SOURCE).registerBundlesFromResources(javaClass.classLoader, "i18n/acoustid", listOf("en", "de"))
    }

    private fun sealed(value: String) = cipher.encrypt(ACOUSTID_API_KEY_SETTING, value)

    private fun source(env: Map<String, String> = emptyMap()): AcoustIdCredentialSource {
        val config = MapApplicationConfig()
        env.forEach { (key, value) -> config.put(key, value) }
        return AcoustIdCredentialSource(settingsService, config, cipher)
    }

    private fun contribution(env: Map<String, String> = emptyMap()): AcoustIdCredentialsContribution =
        AcoustIdCredentialsContribution(source(env), settingsService)

    private fun entry(env: Map<String, String> = emptyMap()): AcoustIdCredentialsEntryContribution =
        AcoustIdCredentialsEntryContribution(source(env), settingsService)

    private fun scope() = UiRenderScope(
        user = UserInfo.fromUser(admin),
        context = UiContext(),
        i18n = translations.translator(SOURCE, "en"),
        settings = settings,
        clientSchemaVersion = UiSchemaVersion.CURRENT,
    )

    private fun UiComponent.Column.fields(): List<UiComponent.TextField> =
        children.filterIsInstance<UiComponent.Form>().single().children.filterIsInstance<UiComponent.TextField>()

    private fun UiComponent.Column.buttons(): List<UiComponent.Button> =
        children.filterIsInstance<UiComponent.Column>().flatMap { it.children }.filterIsInstance<UiComponent.Button>()

    @Test
    fun `the settings entry is an admin list item opening the page`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()
        val entry = entry()
        assertEquals("acoustid.credentials.entry", entry.id)
        assertEquals(UiContributionKind.SLOT, entry.kind)
        assertEquals(UiSlots.SETTINGS, entry.slot)
        assertEquals(71, entry.order)
        assertTrue(entry.access.requiresAdmin)

        val item = entry.render(scope()) as UiComponent.ListItem
        assertEquals("AcoustID credentials", item.title)
        assertEquals(UiAction.OpenPage("acoustid.credentials"), item.action)
        assertEquals("No key configured", item.trailing)

        val stored = entry().also { coEvery { settings.getAll() } returns mapOf(ACOUSTID_API_KEY_SETTING to sealed("storedKey")) }
            .render(scope()) as UiComponent.ListItem
        assertEquals("Stored key", stored.trailing)
    }

    @Test
    fun `the page is an admin page without a slot`() {
        val contribution = contribution()
        assertEquals("acoustid.credentials", contribution.id)
        assertEquals(UiContributionKind.PAGE, contribution.kind)
        assertNull(contribution.slot)
        assertEquals(71, contribution.order)
        assertTrue(contribution.access.requiresAdmin)
    }

    @Test
    fun `renders the none badge and hint when nothing is configured`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()

        val page = contribution().render(scope()) as UiComponent.Column
        val badge = page.children.filterIsInstance<UiComponent.Badge>().single()
        assertEquals("No key configured", badge.text)
        assertEquals(UiTone.MUTED, badge.tone)
        val hint = page.children.filterIsInstance<UiComponent.Text>().single()
        assertTrue(hint.text.startsWith("No AcoustID key is configured, so AcoustID matching is skipped."))

        val fields = page.fields()
        assertEquals(listOf(ACOUSTID_API_KEY_SETTING), fields.map { it.key })
        assertTrue(fields.all { it.secret && !it.required })
        assertTrue(page.children.none { it is UiComponent.Divider })
        assertTrue(page.buttons().isEmpty())

        val form = page.children.filterIsInstance<UiComponent.Form>().single()
        assertEquals(UiAction.Invoke("acoustid.credentials", "save", formId = "acoustid"), form.submit)
    }

    @Test
    fun `renders the environment badge and hint when only the environment is set`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()

        val page = contribution(mapOf("acoustid.apiKey" to "envKey")).render(scope()) as UiComponent.Column
        val badge = page.children.filterIsInstance<UiComponent.Badge>().single()
        assertEquals("Environment key", badge.text)
        assertEquals(UiTone.SUCCESS, badge.tone)
        assertTrue(page.children.filterIsInstance<UiComponent.Text>().single().text.startsWith("Using the key from the server environment"))
        assertTrue(page.children.none { it is UiComponent.Divider })
        assertTrue(page.buttons().isEmpty())
    }

    @Test
    fun `renders the stored hint and a clear action once stored`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf(ACOUSTID_API_KEY_SETTING to sealed("storedKey"))

        val page = contribution().render(scope()) as UiComponent.Column
        val badge = page.children.filterIsInstance<UiComponent.Badge>().single()
        assertEquals("Stored key", badge.text)
        assertEquals(UiTone.SUCCESS, badge.tone)
        assertEquals("Using the key stored here.", page.children.filterIsInstance<UiComponent.Text>().single().text)
        assertTrue(page.children.any { it is UiComponent.Divider })
        val clear = page.buttons().single()
        assertEquals("Remove stored key", clear.label)
        assertEquals(UiAction.Invoke("acoustid.credentials", "clear"), clear.action)
    }

    @Test
    fun `renders the unreadable badge and hint without an environment fallback`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf(ACOUSTID_API_KEY_SETTING to "plainKey")

        val page = contribution().render(scope()) as UiComponent.Column
        val badge = page.children.filterIsInstance<UiComponent.Badge>().single()
        assertEquals("Unreadable key", badge.text)
        assertEquals(UiTone.WARNING, badge.tone)
        val hint = page.children.filterIsInstance<UiComponent.Text>().single().text
        assertTrue(hint.startsWith("The stored key can't be decrypted."))
        assertFalse(hint.contains("server environment"))
        assertTrue(page.children.any { it is UiComponent.Divider })
        val clear = page.buttons().single()
        assertEquals("Remove stored key", clear.label)
    }

    @Test
    fun `renders the unreadable badge and hint with an environment fallback`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf(ACOUSTID_API_KEY_SETTING to "plainKey")

        val page = contribution(mapOf("acoustid.apiKey" to "envKey")).render(scope()) as UiComponent.Column
        val badge = page.children.filterIsInstance<UiComponent.Badge>().single()
        assertEquals("Unreadable key", badge.text)
        assertEquals(UiTone.WARNING, badge.tone)
        val hint = page.children.filterIsInstance<UiComponent.Text>().single().text
        assertTrue(hint.startsWith("The stored key can't be decrypted."))
        assertTrue(hint.contains("The key from the server environment is used meanwhile."))
        assertTrue(page.children.any { it is UiComponent.Divider })
    }

    @Test
    fun `save stores a trimmed key encrypted and refreshes`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()
        val captured = slot<Map<String, String?>>()
        coEvery { settings.setAll(capture(captured)) } just Runs

        val result = contribution().invoke(scope(), "save", mapOf(ACOUSTID_API_KEY_SETTING to UiValue.of("  key123 ")))

        assertEquals(UiInvokeStatus.OK, result.status)
        assertEquals("API key saved", result.message)
        assertTrue(result.refresh)
        val stored = captured.captured
        assertEquals(setOf(ACOUSTID_API_KEY_SETTING), stored.keys)
        assertTrue(stored.getValue(ACOUSTID_API_KEY_SETTING)!!.startsWith(CredentialCipher.PREFIX))
        assertEquals("key123", cipher.decrypt(ACOUSTID_API_KEY_SETTING, stored.getValue(ACOUSTID_API_KEY_SETTING)!!))
    }

    @Test
    fun `save with a blank key clears it`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf(ACOUSTID_API_KEY_SETTING to sealed("storedKey"))

        val result = contribution().invoke(scope(), "save", mapOf(ACOUSTID_API_KEY_SETTING to UiValue.of("   ")))

        assertEquals(UiInvokeStatus.OK, result.status)
        assertEquals("Stored key removed", result.message)
        coVerify { settings.setAll(mapOf(ACOUSTID_API_KEY_SETTING to null)) }
    }

    @Test
    fun `clear removes the stored key`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf(ACOUSTID_API_KEY_SETTING to sealed("storedKey"))

        val result = contribution().invoke(scope(), "clear", emptyMap())
        assertEquals(UiInvokeStatus.OK, result.status)
        assertEquals("Stored key removed", result.message)
        assertTrue(result.refresh)
        coVerify { settings.setAll(mapOf(ACOUSTID_API_KEY_SETTING to null)) }
    }

    @Test
    fun `unknown actions are rejected`() = runBlocking {
        val result = contribution().invoke(scope(), "nope", emptyMap())
        assertEquals(UiInvokeStatus.ERROR, result.status)
        assertFalse(result.refresh)
    }

    companion object {
        private const val SOURCE = ACOUSTID_UI_SOURCE
    }
}
