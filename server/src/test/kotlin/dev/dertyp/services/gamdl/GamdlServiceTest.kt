package dev.dertyp.services.gamdl

import dev.dertyp.data.User
import dev.dertyp.executeCommand
import dev.dertyp.findInPath
import dev.dertyp.plugins.IPluginIndexer
import dev.dertyp.plugins.IServerStorageService
import dev.dertyp.services.SongService
import dev.dertyp.services.import.*
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.services.metadata.MetadataService
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.nio.file.Path
import java.util.*
import kotlin.time.Duration.Companion.minutes

class GamdlServiceTest : KoinTest {
    private val indexer = mockk<IPluginIndexer>(relaxed = true)
    private val storageService = mockk<IServerStorageService>(relaxed = true)
    private val songService = mockk<SongService>(relaxed = true)
    private val importService = mockk<ImportService>(relaxed = true)

    private lateinit var environment: ApplicationEnvironment
    private lateinit var config: ApplicationConfig
    private lateinit var service: GamdlService

    @TempDir
    lateinit var tempDir: Path
    private lateinit var cookiesFile: File

    private fun stubConfig(cookies: String?, wvd: String? = null, codec: String? = null) {
        every { config.propertyOrNull("gamdl.cookiesPath") } returns cookies?.let { v -> mockk { every { getString() } returns v } }
        every { config.propertyOrNull("gamdl.wvdPath") } returns wvd?.let { v -> mockk { every { getString() } returns v } }
        every { config.propertyOrNull("gamdl.codecSong") } returns codec?.let { v -> mockk { every { getString() } returns v } }
    }

    @BeforeEach
    fun setup() {
        environment = mockk()
        config = mockk()
        every { environment.config } returns config
        cookiesFile = tempDir.resolve("cookies.txt").toFile()
        stubConfig(cookiesFile.absolutePath)

        startKoin {
            modules(module {
                single { environment }
                single { songService }
                single { importService }
            })
        }

        mockkStatic("dev.dertyp.UtilsKt")
        every { findInPath("gamdl") } returns "/usr/bin/gamdl"
        every { findInPath("ffmpeg") } returns "/usr/bin/ffmpeg"

        // per-importer storage resolves back to a tracksPath under the temp dir
        every { storageService.forImporter(any()) } returns storageService
        every { storageService.tracksPath } returns tempDir.toString()

        service = GamdlService(indexer, storageService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun track(id: String) = IMetadataService.Track(id = id, title = "Track $id", duration = 3.minutes, images = emptyList())

    private fun user(): User = mockk<User>(relaxed = true).also { every { it.id } returns UUID.randomUUID() }

    @Test
    fun `capabilities cover song, album, artist, playlist and credentials`() {
        assertEquals(
            setOf(
                ImporterCapability.IMPORT_SONG,
                ImporterCapability.IMPORT_ALBUM,
                ImporterCapability.IMPORT_ARTIST,
                ImporterCapability.IMPORT_PLAYLIST,
                ImporterCapability.CREDENTIALS,
            ),
            service.capabilities
        )
    }

    @Test
    fun `canHandle recognises Apple Music urls and rejects others`() {
        assertTrue(service.canHandle("https://music.apple.com/us/album/name/123"))
        assertTrue(service.canHandle("https://music.apple.com/us/song/456"))
        assertFalse(service.canHandle("https://tidal.com/track/1"))
        assertFalse(service.canHandle("https://www.youtube.com/watch?v=1"))
    }

    @Test
    fun `parseUrl extracts apple id and type`() = runBlocking {
        assertEquals("123" to Type.ALBUM, service.parseUrl("https://music.apple.com/us/album/name/123"))
    }

    @Test
    fun `enabled and tokenFileExists require gamdl on PATH and a cookies file`() {
        assertFalse(service.enabled)
        assertFalse(service.tokenFileExists())
        cookiesFile.writeText("# Netscape HTTP Cookie File")
        assertTrue(service.enabled)
        assertTrue(service.tokenFileExists())
    }

    @Test
    fun `importCommand has cookies, output path and Tidal-style templates, without wvd or codec by default`() {
        val cmd = service.importCommand
        assertEquals("gamdl", cmd.first())
        assertTrue(cmd.contains("--no-config-file"))
        assertContainsPair(cmd, "--cookies-path", cookiesFile.absolutePath)
        assertContainsPair(cmd, "--output-path", tempDir.toFile().absolutePath)
        assertContainsPair(cmd, "--template-folder-album", "{album_id}")
        assertContainsPair(cmd, "--template-file-single-disc", "{title_id}")
        assertContainsPair(cmd, "--template-file-multi-disc", "{title_id}")
        assertFalse(cmd.contains("--wvd-path"))
        assertFalse(cmd.contains("--codec-song"))
    }

    @Test
    fun `importCommand includes wvd and codec when configured`() {
        stubConfig(cookiesFile.absolutePath, wvd = "/data/device.wvd", codec = "alac")
        val cmd = service.importCommand
        assertContainsPair(cmd, "--wvd-path", "/data/device.wvd")
        assertContainsPair(cmd, "--codec-song", "alac")
    }

    @Test
    fun `executeImporter rewrites the gamdl token to the resolved binary path`() = runBlocking {
        coEvery { executeCommand(any(), any(), any(), any(), any(), any()) } returns ProcessExecutionResult(0, "", "")
        service.executeImporter(listOf("gamdl", "--no-config-file", "url"), { true }, null) {}
        coVerify {
            executeCommand(
                match { it.first() == "/usr/bin/gamdl" && it.contains("--no-config-file") },
                any(), any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `executeImporter refuses a command that is not gamdl`() = runBlocking {
        val result = service.executeImporter(listOf("rm", "-rf", "/"), { true }, null) {}
        assertEquals(-1, result.exitCode)
        coVerify(exactly = 0) { executeCommand(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `provideCredentials writes the cookies file and enables the importer`() = runBlocking {
        assertFalse(cookiesFile.exists())
        service.provideCredentials(GamdlCredentials(cookiesTxt = "# Netscape HTTP Cookie File\ntoken"))
        assertTrue(cookiesFile.exists())
        assertEquals("# Netscape HTTP Cookie File\ntoken", cookiesFile.readText())
        assertTrue(service.enabled)
    }

    @Test
    fun `importFavoriteCollection is unsupported`() = runBlocking {
        val result = service.importFavoriteCollection(ImportFavType.tracks, 1, { true }, null) {}
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun `importIds for songs enqueues Apple Music track urls`() = runBlocking {
        coEvery { songService.byOriginalIds(any(), any()) } returns emptyList()
        service.importIds(listOf("111", "222"), Type.SONG, user()) {}
        coVerify {
            importService.addToQueue(match {
                it is UrlImportQueueEntry &&
                    it.urls.contains("https://music.apple.com/us/song/111") &&
                    it.urls.contains("https://music.apple.com/us/song/222")
            })
        }
    }

    @Test
    fun `importIds for an album expands its tracks (metadata flow) into per-track urls`() = runBlocking {
        mockkObject(MetadataService)
        val meta = mockk<MetadataService>(relaxed = true)
        every { MetadataService.getMetadataService(any(), any()) } returns meta
        every { meta.getAlbumTracks(any(), any()) } returns flowOf(track("1"), track("2"))
        coEvery { songService.byOriginalIds(any(), any()) } returns emptyList()

        service.importIds(listOf("999"), Type.ALBUM, user()) {}

        coVerify {
            importService.addToQueue(match {
                it is UrlImportQueueEntry &&
                    it.urls.contains("https://music.apple.com/us/song/1") &&
                    it.urls.contains("https://music.apple.com/us/song/2")
            })
        }
    }

    @Test
    fun `importContent runs gamdl, transcodes the downloaded m4a to flac and queues it`() = runBlocking {
        val albumDir = File(tempDir.toFile(), "123").apply { mkdirs() }
        val m4a = File(albumDir, "456.m4a")
        val flac = File(albumDir, "456.flac")

        coEvery {
            executeCommand(match { it.first() == "/usr/bin/gamdl" }, any(), any(), any(), any(), any())
        } answers {
            m4a.writeText("fake-m4a")
            m4a.setLastModified(System.currentTimeMillis() + 10_000)
            ProcessExecutionResult(0, "", "")
        }
        coEvery {
            executeCommand(match { it.first() == "/usr/bin/ffmpeg" }, any(), any(), any(), any(), any())
        } answers {
            flac.writeText("fake-flac")
            ProcessExecutionResult(0, "", "")
        }
        coEvery { indexer.queue(any(), any(), any(), any(), any()) } returns CompletableDeferred(Unit)

        service.importContent(listOf("https://music.apple.com/us/album/x/123"), 1, { true }, null) {}

        coVerify {
            executeCommand(
                match { it.first() == "/usr/bin/ffmpeg" && it.any { a -> a.endsWith("456.m4a") } },
                any(), any(), any(), any(), any()
            )
        }
        assertFalse(m4a.exists(), "source m4a should be removed after transcode")
        assertTrue(flac.exists())
        coVerify {
            @Suppress("DeferredResultUnused")
            indexer.queue(match { paths -> paths.any { it.toString().endsWith("456.flac") } }, any(), any(), any(), any())
        }
    }
}

private fun assertContainsPair(cmd: List<String>, flag: String, value: String) {
    val i = cmd.indexOf(flag)
    assertTrue(i >= 0 && i + 1 < cmd.size, "expected flag $flag in command")
    assertEquals(value, cmd[i + 1], "value after $flag")
}
