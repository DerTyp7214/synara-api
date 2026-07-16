package dev.dertyp.services.subsonic

import dev.dertyp.plugins.ApiKeyScope
import dev.dertyp.services.ApiKeyScopeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiKeyScopeTest {
    @Test
    fun `registry contains built-in radio scope`() {
        val registry = ApiKeyScopeRegistry()
        assertTrue(registry.contains(ApiKeyScope.Radio.id))
        assertEquals("server", registry.all().single { it.id == "radio" }.source)
    }

    @Test
    fun `plugin scopes are self-namespacing`() {
        assertEquals("subsonic", ApiKeyScope.Plugin("subsonic", "subsonic", "n", "d").id)
        assertEquals("subsonic.extra", ApiKeyScope.Plugin("subsonic", "extra", "n", "d").id)
        assertEquals("subsonic.extra", ApiKeyScope.Plugin("subsonic", "subsonic.extra", "n", "d").id)
    }

    @Test
    fun `plugin registrar stamps the plugin id as source`() {
        val registry = ApiKeyScopeRegistry()
        registry.forPlugin("subsonic").registerScope(SubsonicPlugin.SCOPE)
        assertEquals("subsonic", registry.all().single { it.id == "subsonic" }.source)
    }

    @Test
    fun `duplicate registration keeps the first entry`() {
        val registry = ApiKeyScopeRegistry()
        registry.register(ApiKeyScope.Plugin("other", "radio", "Fake radio", "hijack"), "other")
        val entry = registry.all().single { it.id == "radio" }
        assertEquals("server", entry.source)
        assertFalse(entry.description.contains("hijack"))
    }
}
