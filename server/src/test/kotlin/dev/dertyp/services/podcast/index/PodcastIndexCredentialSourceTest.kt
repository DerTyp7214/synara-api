package dev.dertyp.services.podcast.index

import dev.dertyp.plugins.PluginSettings
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PodcastIndexCredentialSourceTest {
    private val settings = mockk<PluginSettings>()

    private fun source(env: Map<String, String> = emptyMap()): PodcastIndexCredentialSource {
        val config = MapApplicationConfig()
        env.forEach { (key, value) -> config.put(key, value) }
        return PodcastIndexCredentialSource(settings, config)
    }

    private val environment = mapOf(
        "podcastIndex.apiKey" to "envKey",
        "podcastIndex.apiSecret" to "envSecret",
    )

    @Test
    fun `stored credentials win over the environment`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf("apiKey" to " storedKey ", "apiSecret" to "storedSecret")
        val source = source(environment)
        assertEquals(PodcastIndexCredentials("storedKey", "storedSecret"), source.current())
        assertTrue(source.stored())
    }

    @Test
    fun `falls back to the environment when nothing is stored`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()
        val source = source(environment)
        assertEquals(PodcastIndexCredentials("envKey", "envSecret"), source.current())
        assertFalse(source.stored())
    }

    @Test
    fun `a partial stored pair falls back to the environment`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf("apiKey" to "storedKey", "apiSecret" to "   ")
        val source = source(environment)
        assertEquals(PodcastIndexCredentials("envKey", "envSecret"), source.current())
        assertFalse(source.stored())
    }

    @Test
    fun `a partial environment pair is not usable`() = runBlocking {
        coEvery { settings.getAll() } returns emptyMap()
        val source = source(mapOf("podcastIndex.apiKey" to "envKey", "podcastIndex.apiSecret" to ""))
        assertNull(source.fromEnvironment())
        assertNull(source.current())
    }

    @Test
    fun `unconfigured when neither settings nor environment carry values`() = runBlocking {
        coEvery { settings.getAll() } returns mapOf("apiKey" to "", "apiSecret" to "")
        val source = source()
        assertNull(source.current())
        assertNull(source.fromEnvironment())
        assertFalse(source.stored())
    }
}
