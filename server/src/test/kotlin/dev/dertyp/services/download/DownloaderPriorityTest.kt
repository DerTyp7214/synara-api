package dev.dertyp.services.download

import dev.dertyp.plugins.IDownloader
import dev.dertyp.plugins.PluginManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class DownloaderPriorityTest {
    private val pluginManager = mockk<PluginManager>()

    @Test
    fun `downloadContent should group URLs by downloader and prioritize default`() = runBlocking {
        val proxy = DownloaderProxy(pluginManager)
        
        val url1 = "https://tidal.com/1"
        val url2 = "https://youtube.com/2"
        
        val tidalDownloader = mockk<IDownloader> {
            every { id } returns "tidal"
            every { canHandle(url1) } returns true
            every { canHandle(url2) } returns false
            coEvery { downloadContent(any(), any(), any(), any(), any()) } returns ProcessExecutionResult.EMPTY
        }
        
        val youtubeDownloader = mockk<IDownloader> {
            every { id } returns "youtube"
            every { canHandle(url1) } returns false
            every { canHandle(url2) } returns true
            coEvery { downloadContent(any(), any(), any(), any(), any()) } returns ProcessExecutionResult.EMPTY
        }

        every { pluginManager.getDownloader("tiddl") } returns tidalDownloader // Mocking default
        every { pluginManager.getAllDownloaders() } returns listOf(tidalDownloader, youtubeDownloader)

        proxy.downloadContent(listOf(url1, url2), 3, { true }) {}

        coVerify { tidalDownloader.downloadContent(listOf(url1), 3, any(), any(), any()) }
        coVerify { youtubeDownloader.downloadContent(listOf(url2), 3, any(), any(), any()) }
    }
}
