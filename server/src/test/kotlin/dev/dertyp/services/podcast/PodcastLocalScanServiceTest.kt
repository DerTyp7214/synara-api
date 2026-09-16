package dev.dertyp.services.podcast

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastSource
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PodcastEpisodeProgressTable
import dev.dertyp.db.PodcastEpisodeTable
import dev.dertyp.db.PodcastShowTable
import dev.dertyp.db.PodcastSubscriptionTable
import dev.dertyp.db.PodcastTranscriptTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.ImageService
import dev.dertyp.services.StorageService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class PodcastLocalScanServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var tempDir: File
    private lateinit var libraryDir: File
    private lateinit var storageService: StorageService
    private lateinit var podcastService: PodcastService
    private lateinit var imageService: ImageService
    private lateinit var scanService: PodcastLocalScanService
    private lateinit var imageId: UUID

    private fun mockHttp(): PodcastHttp {
        val http = spyk(PodcastHttp())
        every { http.feedClient } returns HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) })
        every { http.mediaClient } returns HttpClient(MockEngine { respond(ByteArray(0), HttpStatusCode.NotFound) })
        return http
    }

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        tempDir = Files.createTempDirectory("podcast_scan").toFile()
        libraryDir = File(tempDir, "library").apply { mkdirs() }

        val config = MapApplicationConfig().apply {
            put("audio.custom", File(tempDir, "custom").absolutePath)
            put("data.images", File(tempDir, "images").absolutePath)
            put("data.animated-images", File(tempDir, "animated-images").absolutePath)
            put("podcasts.library", libraryDir.absolutePath)
            put("podcasts.imports", File(tempDir, "imports").absolutePath)
        }
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config
        storageService = StorageService(environment)

        database = TestDatabase.connect(dialect, "podcast_scan_test")
        transaction(database) {
            SchemaUtils.create(
                UserTable,
                ImageTable,
                PodcastShowTable,
                PodcastEpisodeTable,
                PodcastTranscriptTable,
                PodcastSubscriptionTable,
                PodcastEpisodeProgressTable,
            )
        }

        imageId = transaction(database) {
            val id = UUID.randomUUID()
            ImageTable.insert {
                it[ImageTable.id] = id
                it[path] = "images/cover.png"
                it[imageHash] = "hash"
                it[origin] = "test"
            }
            id
        }

        podcastService = PodcastService(mockHttp())
        imageService = mockk()
        coEvery { imageService.createImage(any(), any()) } returns imageId
        scanService = PodcastLocalScanService(podcastService, imageService, storageService)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    private fun writeSilentWav(file: File): File {
        file.parentFile?.mkdirs()
        val format = AudioFormat(44100f, 16, 1, true, false)
        val stream = AudioInputStream(ByteArrayInputStream(ByteArray(44100 * 2)), format, 44100L)
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
        return file
    }

    private fun tagLyrics(file: File, lyrics: String): File {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.LYRICS, lyrics)
        audio.commit()
        return file
    }

    private fun writePng(file: File): File {
        file.parentFile?.mkdirs()
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", file)
        return file
    }

    private fun buildLibrary() {
        writeSilentWav(File(libraryDir, "ShowA/ep1.wav"))
        writeSilentWav(File(libraryDir, "ShowA/sub/ep2.wav"))
        File(libraryDir, "ShowA/ep1.vtt").writeText(SIDECAR_VTT)
        File(libraryDir, "ShowA/partial.mp3.part").writeText("incomplete")

        writePng(File(libraryDir, "ShowB/cover.png"))
        tagLyrics(writeSilentWav(File(libraryDir, "ShowB/ep.wav")), EMBEDDED_VTT)

        writeSilentWav(File(libraryDir, ".hidden/x.wav"))
        File(libraryDir, "notes.txt").writeText("not a show")
    }

    private fun showByPath(localPath: String): PodcastShowRow? = runBlocking {
        podcastService.localShows().firstOrNull { it.localPath == localPath }
    }

    private fun transcriptsOf(episodeId: UUID) = transaction(database) {
        PodcastTranscriptTable
            .selectAll()
            .where { PodcastTranscriptTable.episodeId eq episodeId }
            .map { row ->
                Triple(
                    row[PodcastTranscriptTable.sourceKey],
                    row[PodcastTranscriptTable.type],
                    row[PodcastTranscriptTable.filePath] to row[PodcastTranscriptTable.content]
                )
            }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `first scan creates shows and episodes from the library`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        buildLibrary()

        val result = scanService.scan()

        assertEquals(2, result.shows)
        assertEquals(3, result.episodesAdded)
        assertEquals(0, result.episodesUpdated)
        assertEquals(0, result.episodesRemoved)
        assertEquals(0, result.showsRemoved)

        val shows = podcastService.localShows().sortedBy { it.localPath }
        assertEquals(listOf("ShowA", "ShowB"), shows.map { it.localPath })
        assertEquals(listOf("ShowA", "ShowB"), shows.map { it.title })
        assertTrue(shows.all { it.source == PodcastSource.LOCAL })

        val showA = shows.first { it.localPath == "ShowA" }
        val showB = shows.first { it.localPath == "ShowB" }
        assertNull(showA.imageId)
        assertEquals(imageId, showB.imageId)

        val episodesA = podcastService.episodesOfShow(showA.id)
        assertEquals(setOf("ep1.wav", "sub/ep2.wav"), episodesA.map { it.guid }.toSet())

        val episodesB = podcastService.episodesOfShow(showB.id)
        assertEquals(listOf("ep.wav"), episodesB.map { it.guid })

        (episodesA + episodesB).forEach { episode ->
            assertEquals(PodcastImportState.IMPORTED, episode.importState)
            assertEquals("wav", episode.format)
            assertTrue(File(episode.filePath!!).isAbsolute)
            assertTrue(File(episode.filePath!!).isFile)
            val duration = episode.durationMs
            assertNotNull(duration)
            assertTrue(duration!! in 900L..1100L, "expected about 1000 ms but got $duration")
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `scan stores sidecar and embedded transcripts`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        buildLibrary()

        scanService.scan()

        val showA = showByPath("ShowA")!!
        val ep1 = podcastService.episodesOfShow(showA.id).first { it.guid == "ep1.wav" }
        val sidecars = transcriptsOf(ep1.id)
        assertEquals(1, sidecars.size)
        assertEquals("text/vtt", sidecars.single().second)
        assertEquals(File(libraryDir, "ShowA/ep1.vtt").absolutePath, sidecars.single().third.first)

        val ep2 = podcastService.episodesOfShow(showA.id).first { it.guid == "sub/ep2.wav" }
        assertTrue(transcriptsOf(ep2.id).isEmpty())

        val showB = showByPath("ShowB")!!
        val epB = podcastService.episodesOfShow(showB.id).single()
        val embedded = transcriptsOf(epB.id)
        assertEquals(1, embedded.size)
        assertEquals(PodcastKeys.EMBEDDED_TRANSCRIPT_KEY, embedded.single().first)
        assertEquals("text/vtt", embedded.single().second)
        assertTrue(embedded.single().third.second!!.startsWith("WEBVTT"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a second scan reports no changes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        buildLibrary()
        scanService.scan()

        val second = scanService.scan()

        assertEquals(2, second.shows)
        assertEquals(0, second.episodesAdded)
        assertEquals(0, second.episodesUpdated)
        assertEquals(0, second.episodesRemoved)
        assertEquals(0, second.showsRemoved)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a rescan removes gone episodes and shows without touching library files`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        buildLibrary()
        scanService.scan()

        assertTrue(File(libraryDir, "ShowA/sub/ep2.wav").delete())
        assertTrue(File(libraryDir, "ShowB").deleteRecursively())

        val result = scanService.scan()

        assertEquals(1, result.shows)
        assertEquals(1, result.episodesRemoved)
        assertEquals(1, result.showsRemoved)
        assertEquals(0, result.episodesAdded)

        val shows = podcastService.localShows()
        assertEquals(listOf("ShowA"), shows.map { it.localPath })
        assertEquals(listOf("ep1.wav"), podcastService.episodesOfShow(shows.single().id).map { it.guid })

        assertTrue(File(libraryDir, "ShowA/ep1.wav").isFile, "the scan must never delete library files")
        assertTrue(File(libraryDir, "ShowA/ep1.vtt").isFile)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a missing library root is created and yields an empty result`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        assertTrue(libraryDir.deleteRecursively())

        val result = scanService.scan()

        assertTrue(libraryDir.isDirectory, "the library root should have been created")
        assertEquals(0, result.shows)
        assertEquals(0, result.episodesAdded)
        assertEquals(0, result.episodesUpdated)
        assertEquals(0, result.episodesRemoved)
        assertEquals(0, result.showsRemoved)
        assertTrue(podcastService.localShows().isEmpty())
    }

    companion object {
        private const val SIDECAR_VTT = "WEBVTT\n\n00:00.000 --> 00:01.000\nsidecar\n"
        private const val EMBEDDED_VTT = "WEBVTT\n\n00:00.000 --> 00:01.000\nembedded\n"
    }
}
