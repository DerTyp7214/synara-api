package dev.dertyp.services.import

import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ImporterProxyTest {
    private val pluginManager = mockk<PluginManager>()
    private val proxy = ImporterProxy(pluginManager)

    @Test
    fun testRouting() = runBlocking {
        val tdnDownloader = mockk<IImporter>(relaxed = true)
        val tiddlDownloader = mockk<IImporter>(relaxed = true)

        every { tdnDownloader.id } returns ImportBackend.Tdn.id
        every { tdnDownloader.enabled } returns true
        every { tiddlDownloader.id } returns ImportBackend.Tiddl.id
        every { tiddlDownloader.enabled } returns true

        every { pluginManager.getImporter(ImportBackend.Tdn.id) } returns tdnDownloader
        every { pluginManager.getImporter(ImportBackend.Tiddl.id) } returns tiddlDownloader
        every { pluginManager.getAllImporters() } returns listOf(tdnDownloader, tiddlDownloader)

        every { tiddlDownloader.canHandle("tidal.com/track/1") } returns true
        every { tdnDownloader.canHandle("tidal.com/track/1") } returns true
        every { tiddlDownloader.canHandle("tdn:track/2") } returns false
        every { tdnDownloader.canHandle("tdn:track/2") } returns true

        coEvery { tiddlDownloader.importContent(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "tiddl", "")
        coEvery { tdnDownloader.importContent(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "tdn", "")

        proxy.defaultService = ImportBackend.Tiddl

        proxy.importContent(
            urls = listOf("tidal.com/track/1", "tdn:track/2"),
            maxRetries = 1,
            aliveCheck = { true },
            userId = null,
            onLiveOutput = {}
        )

        coVerify { tiddlDownloader.importContent(listOf("tidal.com/track/1"), 1, any(), any(), any(), any()) }
        coVerify { tdnDownloader.importContent(listOf("tdn:track/2"), 1, any(), any(), any(), any()) }
    }

    @Test
    fun `default downloader should win if it can handle the url`() = runBlocking {
        val d1 = mockk<IImporter>(relaxed = true)
        val d2 = mockk<IImporter>(relaxed = true)

        every { d1.id } returns "d1"
        every { d1.enabled } returns true
        every { d2.id } returns "d2"
        every { d2.enabled } returns true
        every { d1.canHandle(any()) } returns true
        every { d2.canHandle(any()) } returns true

        every { pluginManager.getImporter("d1") } returns d1
        every { pluginManager.getImporter("d2") } returns d2
        every { pluginManager.getAllImporters() } returns listOf(d1, d2)

        proxy.defaultService = ImportBackend("d1")
        proxy.importContent(listOf("any"), 1, { true }, null, null) {}
        coVerify { d1.importContent(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { d2.importContent(any(), any(), any(), any(), any(), any()) }

        proxy.defaultService = ImportBackend("d2")
        proxy.importContent(listOf("any"), 1, { true }, null, null) {}
        coVerify { d2.importContent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fallback should occur if default importer is disabled`() = runBlocking {
        val d1 = mockk<IImporter>(relaxed = true)
        val d2 = mockk<IImporter>(relaxed = true)

        every { d1.id } returns "d1"
        every { d1.enabled } returns false
        every { d1.canHandle(any()) } returns true

        every { d2.id } returns "d2"
        every { d2.enabled } returns true
        every { d2.canHandle(any()) } returns true

        every { pluginManager.getImporter("d1") } returns d1
        every { pluginManager.getImporter("d2") } returns d2
        every { pluginManager.getAllImporters() } returns listOf(d1, d2)

        proxy.defaultService = ImportBackend("d1")

        proxy.importContent(
            urls = listOf("any"),
            maxRetries = 1,
            aliveCheck = { true },
            userId = null,
            onLiveOutput = {}
        )

        coVerify { d2.importContent(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { d1.importContent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `should use DisabledImporter when no importers are enabled`() = runBlocking {
        val d1 = mockk<IImporter>(relaxed = true)
        every { d1.id } returns "d1"
        every { d1.enabled } returns false

        every { pluginManager.getImporter("d1") } returns d1
        every { pluginManager.getAllImporters() } returns listOf(d1)

        proxy.defaultService = ImportBackend("d1")

        val result = proxy.importContent(
            urls = listOf("any"),
            maxRetries = 1,
            aliveCheck = { true },
            userId = null,
            onLiveOutput = {}
        )

        assert(result.exitCode == -1)
        assert(result.fullOutput.contains("is disabled"))
    }
}
