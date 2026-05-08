package dev.dertyp.services.download

import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.FavSyncService
import dev.dertyp.services.ImageService
import dev.dertyp.services.SongService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class DownloadServiceTest {
    private val downloaderProxy = mockk<DownloaderProxy>(relaxed = true)
    private val songService = mockk<SongService>()
    private val favSyncService = mockk<FavSyncService>()
    private val imageService = mockk<ImageService>()
    private val pluginManager = mockk<PluginManager>()

    private val service = DownloadService(
        downloaderProxy, songService, favSyncService, imageService, pluginManager
    )

    @Test
    fun testDownloadUrls() = runBlocking {
        val urls = listOf("https://tidal.com/track/1")
        val entry = UrlDownloadQueueEntry(urls = urls.toMutableList())

        coEvery { downloaderProxy.downloadContent(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "ok", "")

        val job = launch {
            service.startService()
        }

        service.addToQueue(entry)

        withTimeout(2.seconds) {
            while (service.finishedDownloads().isEmpty()) {
                yield()
            }
        }

        coVerify { downloaderProxy.downloadContent(eq(urls), any(), any(), any(), any(), any()) }
        assertEquals(1, service.finishedDownloads().size)

        service.stopService()
        job.cancelAndJoin()
    }
}
