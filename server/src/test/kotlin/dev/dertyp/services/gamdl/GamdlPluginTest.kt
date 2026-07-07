package dev.dertyp.services.gamdl

import dev.dertyp.plugins.PluginContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class GamdlPluginTest : KoinTest {
    private val gamdlService = mockk<GamdlService>(relaxed = true)
    private lateinit var plugin: GamdlPlugin

    @BeforeEach
    fun setup() {
        startKoin { modules(module { single { gamdlService } }) }
        plugin = GamdlPlugin()
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `plugin exposes its id and name`() {
        assertEquals("gamdl", plugin.id)
        assertEquals("gamdl (Apple Music)", plugin.name)
    }

    @Test
    fun `enabled reflects the underlying service`() {
        every { gamdlService.enabled } returns true
        assertTrue(plugin.enabled)
        every { gamdlService.enabled } returns false
        assertFalse(plugin.enabled)
    }

    @Test
    fun `init wires the indexer into the service and exposes both`() {
        val context = mockk<PluginContext>(relaxed = true)
        plugin.init(context)

        assertSame(gamdlService, plugin.getImporter())
        assertTrue(plugin.getIndexer() is GamdlIndexer)
        verify { gamdlService.indexer = any() }
    }

    @Test
    fun `getKoinModule registers the gamdl service`() {
        assertNotNull(plugin.getKoinModule())
    }
}
