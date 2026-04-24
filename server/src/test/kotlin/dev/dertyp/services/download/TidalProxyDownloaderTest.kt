package dev.dertyp.services.download

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TidalProxyDownloaderTest {
    private val tiddl = mockk<TiddlService>(relaxed = true)
    private val tdn = mockk<TdnService>(relaxed = true)
    private val downloaderProxy = mockk<DownloaderProxy>()
    private lateinit var proxyDownloader: TidalProxyDownloader

    @BeforeEach
    fun setup() {
        every { tiddl.id } returns TiddlService.ID
        every { tdn.id } returns TdnService.ID
        proxyDownloader = TidalProxyDownloader(tiddl, tdn, downloaderProxy)
    }

    @Test
    fun `enabled should be true if either is enabled`() {
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns false
        assertTrue(proxyDownloader.enabled)

        every { tiddl.enabled } returns false
        every { tdn.enabled } returns true
        assertTrue(proxyDownloader.enabled)

        every { tiddl.enabled } returns false
        every { tdn.enabled } returns false
        assertFalse(proxyDownloader.enabled)
    }

    @Test
    fun `should use tiddl if it is default and enabled`() {
        every { downloaderProxy.defaultService } returns DownloadBackend.Tiddl
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns true

        proxyDownloader.canHandle("url")

        verify { tiddl.canHandle("url") }
        verify(exactly = 0) { tdn.canHandle(any()) }
    }

    @Test
    fun `should use tdn if it is default and enabled`() {
        every { downloaderProxy.defaultService } returns DownloadBackend.Tdn
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns true

        proxyDownloader.canHandle("url")

        verify { tdn.canHandle("url") }
        verify(exactly = 0) { tiddl.canHandle(any()) }
    }

    @Test
    fun `should fallback to tdn if tiddl is default but disabled`() {
        every { downloaderProxy.defaultService } returns DownloadBackend.Tiddl
        every { tiddl.enabled } returns false
        every { tdn.enabled } returns true

        proxyDownloader.canHandle("url")

        verify { tdn.canHandle("url") }
        verify(exactly = 0) { tiddl.canHandle(any()) }
    }

    @Test
    fun `should fallback to tiddl if tdn is default but disabled`() {
        every { downloaderProxy.defaultService } returns DownloadBackend.Tdn
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns false

        proxyDownloader.canHandle("url")

        verify { tiddl.canHandle("url") }
        verify(exactly = 0) { tdn.canHandle(any()) }
    }

    @Test
    fun `should fallback to tiddl as default if neither matches and both enabled`() {
        every { downloaderProxy.defaultService } returns DownloadBackend.Youtube
        every { tiddl.enabled } returns true
        every { tdn.enabled } returns true

        proxyDownloader.canHandle("url")

        verify { tiddl.canHandle("url") }
        verify(exactly = 0) { tdn.canHandle(any()) }
    }
}
