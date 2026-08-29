package dev.dertyp.ui

import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TranslationServiceTest {
    private val registry = UiRegistry()
    private val service = TranslationService(registry)

    @Test
    fun `core bundles are loaded from resources`() {
        assertEquals(setOf("en", "de"), service.locales(UiRegistry.SERVER_SOURCE))
        assertEquals("Importer", service.translator(UiRegistry.SERVER_SOURCE, "en").t("importer.title"))
        assertEquals("Warteschlange", service.translator(UiRegistry.SERVER_SOURCE, "de-AT").t("importer.queue.title"))
    }

    @Test
    fun `plugin bundles override core and fall back to core and english`() {
        service.forSource("plugin").registerBundle("de", mapOf("plugin.hello" to "Hallo", "importer.title" to "Mein Importer"))
        service.forSource("plugin").registerBundle("en", mapOf("plugin.hello" to "Hello", "plugin.only" to "Only english"))

        val de = service.translator("plugin", "de-CH")
        assertEquals("Hallo", de.t("plugin.hello"))
        assertEquals("Mein Importer", de.t("importer.title"))
        assertEquals("Only english", de.t("plugin.only"))
        assertEquals("Warteschlange", de.t("importer.queue.title"))
        assertEquals("missing.key", de.t("missing.key"))

        assertEquals("Importer", service.translator("other", "fr").t("importer.title"))
    }

    @Test
    fun `placeholders are substituted`() {
        service.forSource("p").registerBundle("en", mapOf("greet" to "Hi {user}, {count} new"))
        assertEquals("Hi Ann, 3 new", service.translator("p", "en").t("greet", "user" to "Ann", "count" to "3"))
        assertEquals("2 items queued", service.translator(UiRegistry.SERVER_SOURCE, "en").t("importer.queued", "count" to "2"))
    }

    @Test
    fun `bundles can be replaced and removed at any time`() {
        val registrar = service.forSource("live")
        registrar.registerBundle("en", mapOf("k" to "one"))
        assertEquals("one", service.translator("live", "en").t("k"))
        registrar.registerBundle("en", mapOf("k" to "two"))
        assertEquals("two", service.translator("live", "en").t("k"))
        registrar.remove("en")
        assertEquals("k", service.translator("live", "en").t("k"))
        assertTrue(registrar.locales().isEmpty())
    }
}
