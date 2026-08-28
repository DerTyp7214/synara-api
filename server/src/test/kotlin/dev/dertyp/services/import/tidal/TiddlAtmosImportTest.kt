package dev.dertyp.services.import.tidal

import dev.dertyp.audio.AtmosProcessor
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.plugins.atmosSibling
import dev.dertyp.services.import.ProcessExecutionResult
import dev.dertyp.services.import.TiddlService
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.exists
import kotlin.io.path.extension

class TiddlAtmosImportTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
    private val pluginStorage = mockk<IServerStorageService>(relaxed = true)
    private val atmosProcessor = mockk<AtmosProcessor>()
    private lateinit var tempDir: Path
    private lateinit var service: TiddlService

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("tiddl-atmos-test")
        mockkStatic("dev.dertyp.UtilsKt")
        every { findInPath("tiddl") } returns "/usr/local/bin/tiddl"
        every { storageService.forImporter(any()) } returns pluginStorage
        every { pluginStorage.tracksPath } returns tempDir.toString()
        every { pluginStorage.playlistsPath } returns null
        every { pluginStorage.albumsPath } returns null
        every { indexer.isAudio(any()) } answers { firstArg<Path>().extension == "flac" }
        coEvery { indexer.queue(any(), any(), any(), any(), any()) } returns CompletableDeferred(Unit)

        every { atmosProcessor.isAtmos(any()) } answers { firstArg<Path>().extension == "m4a" }
        coEvery { atmosProcessor.process(any(), any()) } coAnswers {
            val m4a = firstArg<Path>()
            val flac = Files.createFile(m4a.resolveSibling("1.flac"))
            Files.move(m4a, m4a.atmosSibling)
            flac
        }

        service = AtmosTiddlService(indexer, storageService, atmosProcessor)
    }

    private class AtmosTiddlService(
        indexer: IPluginIndexer,
        storageService: IServerStorageService,
        override val atmosProcessor: AtmosProcessor
    ) : TiddlService(indexer, storageService)

    @AfterEach
    fun tearDown() {
        unmockkAll()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `atmos m4a downloads are converted and the lossless file is queued for indexing`() = runBlocking {
        val m4a = tempDir.resolve("1.m4a")
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } coAnswers {
            Files.createFile(m4a)
            Files.setLastModifiedTime(m4a, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            ProcessExecutionResult(0, "", "")
        }

        val (_, paths) = service.collectImportedFiles(listOf("tiddl", "download", "url", "x"), 0, 0, { true }, null, {})

        val flac = tempDir.resolve("1.flac")
        assertEquals(listOf(flac), paths)
        assertTrue(flac.exists())
        assertTrue(tempDir.resolve("1.atmos.m4a").exists())
        assertTrue(!m4a.exists())
        coVerify { atmosProcessor.process(m4a, any()) }
        coVerify { indexer.queue(listOf(flac), emptyList(), any(), any(), any()) }
    }

    @Test
    fun `non-atmos m4a files are left untouched`() = runBlocking {
        every { atmosProcessor.isAtmos(any()) } returns false
        val m4a = tempDir.resolve("2.m4a")
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } coAnswers {
            Files.createFile(m4a)
            Files.setLastModifiedTime(m4a, FileTime.fromMillis(System.currentTimeMillis() + 5_000))
            ProcessExecutionResult(0, "", "")
        }

        val (_, paths) = service.collectImportedFiles(listOf("tiddl", "download", "url", "x"), 0, 0, { true }, null, {})

        assertEquals(listOf(m4a), paths)
        assertTrue(m4a.exists())
        coVerify(exactly = 0) { atmosProcessor.process(any(), any()) }
        coVerify { indexer.queue(emptyList(), emptyList(), any(), any(), any()) }
    }
}
