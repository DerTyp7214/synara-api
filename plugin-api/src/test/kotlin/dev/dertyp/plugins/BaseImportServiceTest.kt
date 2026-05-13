package dev.dertyp.plugins

import dev.dertyp.data.User
import dev.dertyp.services.import.ImportQueueEntry
import dev.dertyp.services.import.Type
import dev.dertyp.services.import.UrlImportQueueEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BaseImportServiceTest {
    private val context = mockk<PluginContext>(relaxed = true)
    
    private val service = object : BaseImportService(context) {
        val importers = mutableListOf<IImporter>()
        override suspend fun getImporterForEntry(entry: ImportQueueEntry): IImporter? = importers.firstOrNull()
        override suspend fun getAllImporters(): Collection<IImporter> = importers

        fun getQueue() = importQueue
    }

    @Test
    fun testAddToQueue() = runBlocking {
        val entry1 = UrlImportQueueEntry(urls = mutableListOf("url1"))
        val entry2 = UrlImportQueueEntry(urls = mutableListOf("url1", "url2"))

        service.addToQueue(entry1)
        assertEquals(1, service.getQueue().size)
        assertEquals(listOf("url1"), (service.getQueue()[0] as UrlImportQueueEntry).urls)

        // entry2 has "url1" which is already in queue. addToQueue should filter it out.
        service.addToQueue(entry2)
        assertEquals(2, service.getQueue().size)
        assertEquals(listOf("url2"), (service.getQueue()[1] as UrlImportQueueEntry).urls)
    }

    @Test
    fun testDownloadIdsRouting() = runBlocking {
        val importer1 = mockk<IImporter>()
        val importer2 = mockk<IImporter>()

        every { importer1.id } returns "dl1"
        every { importer2.id } returns "dl2"

        coEvery { importer1.importIds(any(), any(), any(), any()) } returns (true to emptyList())
        coEvery { importer2.importIds(any(), any(), any(), any()) } returns (true to emptyList())

        service.importers.addAll(listOf(importer1, importer2))

        val user = mockk<User>()

        // Routing with explicit ID
        service.importIds(listOf("id1").asFlow(), Type.SONG, user, "dl1")
        coVerify { importer1.importIds(listOf("id1"), Type.SONG, user, any()) }

        service.importIds(listOf("id2").asFlow(), Type.SONG, user, "dl2")
        coVerify { importer2.importIds(listOf("id2"), Type.SONG, user, any()) }

        // Fallback to first importer if no ID provided
        service.importIds(listOf("id3").asFlow(), Type.SONG, user, null)
        coVerify { importer1.importIds(listOf("id3"), Type.SONG, user, any()) }
    }
}
