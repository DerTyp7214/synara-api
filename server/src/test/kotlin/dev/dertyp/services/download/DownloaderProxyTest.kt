package dev.dertyp.services.download

import dev.dertyp.plugins.IDownloader
import dev.dertyp.plugins.PluginManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DownloaderProxyTest {
    private val pluginManager = mockk<PluginManager>()
    private val proxy = DownloaderProxy(pluginManager)

    @Test
    fun testRouting() = runBlocking {
        val tdnDownloader = mockk<IDownloader>(relaxed = true)
        val tiddlDownloader = mockk<IDownloader>(relaxed = true)

        every { tdnDownloader.id } returns DownloadBackend.Tdn.id
        every { tiddlDownloader.id } returns DownloadBackend.Tiddl.id

        every { pluginManager.getDownloader(DownloadBackend.Tdn.id) } returns tdnDownloader
        every { pluginManager.getDownloader(DownloadBackend.Tiddl.id) } returns tiddlDownloader
        every { pluginManager.getAllDownloaders() } returns listOf(tdnDownloader, tiddlDownloader)

        every { tiddlDownloader.canHandle("tidal.com/track/1") } returns true
        every { tdnDownloader.canHandle("tidal.com/track/1") } returns true
        every { tiddlDownloader.canHandle("tdn:track/2") } returns false
        every { tdnDownloader.canHandle("tdn:track/2") } returns true

        coEvery { tiddlDownloader.downloadContent(any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "tiddl", "")
        coEvery { tdnDownloader.downloadContent(any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "tdn", "")

        proxy.defaultService = DownloadBackend.Tiddl

        proxy.downloadContent(
            urls = listOf("tidal.com/track/1", "tdn:track/2"),
            maxRetries = 1,
            aliveCheck = { true },
            userId = null,
            onLiveOutput = {}
        )

        coVerify { tiddlDownloader.downloadContent(listOf("tidal.com/track/1"), 1, any(), any(), any()) }
        coVerify { tdnDownloader.downloadContent(listOf("tdn:track/2"), 1, any(), any(), any()) }
    }
}
