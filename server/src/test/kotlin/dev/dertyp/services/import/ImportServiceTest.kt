package dev.dertyp.services.import

import dev.dertyp.plugins.PluginManager
import dev.dertyp.services.FavSyncService
import dev.dertyp.services.ImageService
import dev.dertyp.services.SongService
import dev.dertyp.services.metadata.IMetadataService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class ImportServiceTest {
    private val importerProxy = mockk<ImporterProxy>(relaxed = true)
    private val songService = mockk<SongService>()
    private val favSyncService = mockk<FavSyncService>()
    private val imageService = mockk<ImageService>()
    private val pluginManager = mockk<PluginManager>()

    private val service = ImportService(
        importerProxy, songService, favSyncService, imageService, pluginManager
    )

    @Test
    fun testImportUrls() = runBlocking {
        val urls = listOf("https://tidal.com/track/1")
        val entry = UrlImportQueueEntry(urls = urls.toMutableList())

        coEvery { importerProxy.importContent(any(), any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "ok", "")

        val job = launch {
            service.startService()
        }

        service.addToQueue(entry)

        withTimeout(2.seconds) {
            while (service.finishedImports().isEmpty()) {
                yield()
            }
        }

        coVerify { importerProxy.importContent(eq(urls), any(), any(), any(), any(), any(), any()) }
        assertEquals(1, service.finishedImports().size)

        service.stopService()
        job.cancelAndJoin()
    }

    @Test
    fun testMetadataPassThrough() = runBlocking {
        val urls = listOf("https://tidal.com/track/metadata")
        val metadata = mockk<IMetadataService.Track>()
        val entry = UrlImportQueueEntry(urls = urls.toMutableList(), metadata = metadata)

        coEvery { importerProxy.importContent(any(), any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "ok", "")

        val job = launch {
            service.startService()
        }

        service.addToQueue(entry)

        withTimeout(5.seconds) {
            while (service.finishedImports().isEmpty()) {
                yield()
            }
        }

        coVerify { importerProxy.importContent(any(), any(), any(), any(), any(), eq(metadata), any()) }

        service.stopService()
        job.cancelAndJoin()
    }

    @Test
    fun testDeduplication() = runBlocking {
        val urls1 = mutableListOf("url1", "url2")
        val urls2 = mutableListOf("url2", "url3")

        service.addToQueue(UrlImportQueueEntry(urls = urls1))
        service.addToQueue(UrlImportQueueEntry(urls = urls2))

        val queue = service.importQueue()
        assertEquals(2, queue.size)
        assertEquals(listOf("url1", "url2"), (queue[0] as UrlImportQueueEntry).urls)
        assertEquals(listOf("url3"), (queue[1] as UrlImportQueueEntry).urls)
    }
}
