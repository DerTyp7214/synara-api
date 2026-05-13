package dev.dertyp.services.import.tidal

import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.import.ImportFavType
import dev.dertyp.services.import.ProcessExecutionResult
import dev.dertyp.services.import.TiddlService
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TiddlServiceTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
    private lateinit var service: TiddlService

    @BeforeEach
    fun setup() {
        mockkStatic("dev.dertyp.UtilsKt")
        every { findInPath("tiddl") } returns "/usr/local/bin/tiddl"
        service = TiddlService(indexer, storageService)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `executeDownloader should prefix tiddl command with python3 -u`() = runBlocking {
        val command = listOf("tiddl", "download", "url", "https://tidal.com/track/1")
        val expectedCommand = listOf("python3", "-u", "/usr/local/bin/tiddl", "download", "url", "https://tidal.com/track/1")

        coEvery {
            executeCommand(expectedCommand, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, "Success", "")

        service.executeImporter(command, { true }, null) {}

        coVerify {
            executeCommand(expectedCommand, any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `executeDownloader should not prefix if already starts with python3`() = runBlocking {
        val command = listOf("python3", "/usr/local/bin/tiddl", "download", "url", "https://tidal.com/track/1")

        coEvery {
            executeCommand(command, any(), any(), any(), any(), any())
        } returns ProcessExecutionResult(0, "Success", "")

        service.executeImporter(command, { true }, null) {}

        coVerify {
            executeCommand(command, any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `canHandle should handle various Tidal and tiddl links`() {
        assertEquals(true, service.canHandle("https://tidal.com/track/1"))
        assertEquals(true, service.canHandle("https://listen.tidal.com/album/1"))
        assertEquals(true, service.canHandle("tiddl:track/1"))
        assertEquals(false, service.canHandle("https://youtube.com/watch?v=1"))
    }

    @Test
    fun `downloadFavoriteCollection should call executeDownloader with correct command`() = runBlocking {
        val tiddlService = spyk(service)
        val expectedCommand = listOf("tiddl", "download", "fav", "--types", "track")
        
        coEvery { tiddlService.executeImporter(any(), any(), any(), any()) } returns ProcessExecutionResult(0, "", "")
        
        tiddlService.importFavoriteCollection(ImportFavType.tracks, 3, { true }, null) {}
        
        coVerify { tiddlService.executeImporter(expectedCommand, any(), any(), any()) }
    }

    @Test
    fun `login should call executeDownloader with correct command`() = runBlocking {
        val tiddlService = spyk(service)
        val expectedCommand = listOf("tiddl", "auth", "login", "--no-browser")
        
        coEvery { tiddlService.executeImporter(any(), any(), any(), any()) } returns ProcessExecutionResult(0, "", "")
        
        tiddlService.login({ true }) {}
        
        coVerify { tiddlService.executeImporter(expectedCommand, any(), any(), any()) }
    }
}
