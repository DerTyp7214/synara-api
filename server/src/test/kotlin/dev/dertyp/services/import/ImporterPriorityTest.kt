package dev.dertyp.services.import

import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ImporterPriorityTest {
    private val pluginManager = mockk<PluginManager>()

    @Test
    fun `importContent should group URLs by importer and prioritize default`() = runBlocking {
        val proxy = ImporterProxy(pluginManager)
        
        val url1 = "https://tidal.com/1"
        val url2 = "https://youtube.com/2"
        
        val tidalImporter = mockk<IImporter> {
            every { id } returns "tidal"
            every { enabled } returns true
            every { canHandle(url1) } returns true
            every { canHandle(url2) } returns false
            coEvery { importContent(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult.EMPTY
        }
        
        val youtubeImporter = mockk<IImporter> {
            every { id } returns "youtube"
            every { enabled } returns true
            every { canHandle(url1) } returns false
            every { canHandle(url2) } returns true
            coEvery { importContent(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult.EMPTY
        }

        every { pluginManager.getImporter("tiddl") } returns tidalImporter
        every { pluginManager.getAllImporters() } returns listOf(tidalImporter, youtubeImporter)

        proxy.importContent(listOf(url1, url2), 3, { true }) { _ -> }

        coVerify { tidalImporter.importContent(listOf(url1), 3, any(), any(), any(), any()) }
        coVerify { youtubeImporter.importContent(listOf(url2), 3, any(), any(), any(), any()) }
    }
}
