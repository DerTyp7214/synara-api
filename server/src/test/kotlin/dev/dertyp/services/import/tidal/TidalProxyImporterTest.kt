package dev.dertyp.services.import.tidal

import dev.dertyp.services.import.*

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TidalProxyImporterTest {
    private val tiddl = mockk<TiddlService>(relaxed = true)
    private val tdn = mockk<TdnService>(relaxed = true)
    private val importerProxy = mockk<ImporterProxy>()
    private lateinit var proxyImporter: TidalProxyImporter

    @BeforeEach
    fun setup() {
        every { tiddl.id } returns TiddlService.ID
        every { tdn.id } returns TdnService.ID
        proxyImporter = TidalProxyImporter(tiddl, tdn, importerProxy)
    }

    @Test
    fun `enabled should be true if either is enabled`() {
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns false
        assertTrue(proxyImporter.enabled)

        every { tiddl.enabled } returns false
        every { tdn.enabled } returns true
        assertTrue(proxyImporter.enabled)

        every { tiddl.enabled } returns false
        every { tdn.enabled } returns false
        assertFalse(proxyImporter.enabled)
    }

    @Test
    fun `should use tiddl if it is default and enabled`() {
        every { importerProxy.defaultService } returns ImportBackend.Tiddl
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns true

        proxyImporter.canHandle("url")

        verify { tiddl.canHandle("url") }
        verify(exactly = 0) { tdn.canHandle(any()) }
    }

    @Test
    fun `should use tdn if it is default and enabled`() {
        every { importerProxy.defaultService } returns ImportBackend.Tdn
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns true

        proxyImporter.canHandle("url")

        verify { tdn.canHandle("url") }
        verify(exactly = 0) { tiddl.canHandle(any()) }
    }

    @Test
    fun `should fallback to tdn if tiddl is default but disabled`() {
        every { importerProxy.defaultService } returns ImportBackend.Tiddl
        every { tiddl.enabled } returns false
        every { tdn.enabled } returns true

        proxyImporter.canHandle("url")

        verify { tdn.canHandle("url") }
        verify(exactly = 0) { tiddl.canHandle(any()) }
    }

    @Test
    fun `should fallback to tiddl if tdn is default but disabled`() {
        every { importerProxy.defaultService } returns ImportBackend.Tdn
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns false

        proxyImporter.canHandle("url")

        verify { tiddl.canHandle("url") }
        verify(exactly = 0) { tdn.canHandle(any()) }
    }

    @Test
    fun `should fallback to tiddl as default if neither matches and both enabled`() {
        every { importerProxy.defaultService } returns ImportBackend.Youtube
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns true

        proxyImporter.canHandle("url")

        verify { tiddl.canHandle("url") }
        verify(exactly = 0) { tdn.canHandle(any()) }
    }
}
