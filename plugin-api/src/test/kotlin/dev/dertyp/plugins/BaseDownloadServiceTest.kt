package dev.dertyp.plugins

import dev.dertyp.data.User
import dev.dertyp.services.download.DownloadQueueEntry
import dev.dertyp.services.download.Type
import dev.dertyp.services.download.UrlDownloadQueueEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BaseDownloadServiceTest {
    private val context = mockk<PluginContext>(relaxed = true)
    
    private val service = object : BaseDownloadService(context) {
        val downloaders = mutableListOf<IDownloader>()
        override suspend fun getDownloaderForEntry(entry: DownloadQueueEntry): IDownloader? = downloaders.firstOrNull()
        override suspend fun getAllDownloaders(): Collection<IDownloader> = downloaders

        fun getQueue() = downloadQueue
    }

    @Test
    fun testAddToQueue() = runBlocking {
        val entry1 = UrlDownloadQueueEntry(urls = mutableListOf("url1"))
        val entry2 = UrlDownloadQueueEntry(urls = mutableListOf("url1", "url2"))

        service.addToQueue(entry1)
        assertEquals(1, service.getQueue().size)
        assertEquals(listOf("url1"), (service.getQueue()[0] as UrlDownloadQueueEntry).urls)

        // entry2 has "url1" which is already in queue. addToQueue should filter it out.
        service.addToQueue(entry2)
        assertEquals(2, service.getQueue().size)
        assertEquals(listOf("url2"), (service.getQueue()[1] as UrlDownloadQueueEntry).urls)
    }

    @Test
    fun testDownloadIdsRouting() = runBlocking {
        val downloader1 = mockk<IDownloader>()
        val downloader2 = mockk<IDownloader>()

        every { downloader1.id } returns "dl1"
        every { downloader2.id } returns "dl2"

        coEvery { downloader1.downloadIds(any(), any(), any(), any()) } returns (true to emptyList())
        coEvery { downloader2.downloadIds(any(), any(), any(), any()) } returns (true to emptyList())

        service.downloaders.addAll(listOf(downloader1, downloader2))

        val user = mockk<User>()

        // Routing with explicit ID
        service.downloadIds(listOf("id1").asFlow(), Type.SONG, user, "dl1")
        coVerify { downloader1.downloadIds(listOf("id1"), Type.SONG, user, any()) }

        service.downloadIds(listOf("id2").asFlow(), Type.SONG, user, "dl2")
        coVerify { downloader2.downloadIds(listOf("id2"), Type.SONG, user, any()) }

        // Fallback to first downloader if no ID provided
        service.downloadIds(listOf("id3").asFlow(), Type.SONG, user, null)
        coVerify { downloader1.downloadIds(listOf("id3"), Type.SONG, user, any()) }
    }
}
