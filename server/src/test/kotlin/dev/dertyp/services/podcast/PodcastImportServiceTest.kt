package dev.dertyp.services.podcast

import dev.dertyp.DbDialect
import dev.dertyp.TestDatabase
import dev.dertyp.data.PodcastImportState
import dev.dertyp.data.PodcastSource
import dev.dertyp.data.TaskKeys
import dev.dertyp.db.ImageTable
import dev.dertyp.db.PodcastEpisodeProgressTable
import dev.dertyp.db.PodcastEpisodeTable
import dev.dertyp.db.PodcastShowTable
import dev.dertyp.db.PodcastSubscriptionTable
import dev.dertyp.db.PodcastTranscriptTable
import dev.dertyp.db.UserTable
import dev.dertyp.services.StorageService
import dev.dertyp.services.schedule.ScheduleService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.io.File
import java.nio.file.Files
import java.util.UUID

class PodcastImportServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var tempDir: File
    private lateinit var importsDir: File
    private lateinit var libraryDir: File
    private lateinit var storageService: StorageService
    private lateinit var podcastService: PodcastService
    private lateinit var scheduleService: ScheduleService
    private lateinit var importService: PodcastImportService

    private var respondWith: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond(ByteArray(0), HttpStatusCode.NotFound)
    }

    private fun mockHttp(): PodcastHttp {
        val http = spyk(PodcastHttp())
        every { http.feedClient } returns HttpClient(MockEngine { request -> respondWith.invoke(this, request) })
        every { http.mediaClient } returns HttpClient(MockEngine { request -> respondWith.invoke(this, request) })
        return http
    }

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        tempDir = Files.createTempDirectory("podcast_import").toFile()
        libraryDir = File(tempDir, "library").apply { mkdirs() }
        importsDir = File(tempDir, "imports").apply { mkdirs() }

        val config = MapApplicationConfig().apply {
            put("audio.custom", File(tempDir, "custom").absolutePath)
            put("data.images", File(tempDir, "images").absolutePath)
            put("data.animated-images", File(tempDir, "animated-images").absolutePath)
            put("podcasts.library", libraryDir.absolutePath)
            put("podcasts.imports", importsDir.absolutePath)
        }
        val environment = mockk<ApplicationEnvironment>()
        every { environment.config } returns config
        storageService = StorageService(environment)

        database = TestDatabase.connect(dialect, "podcast_import_test")
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

        val http = mockHttp()
        podcastService = PodcastService(http)
        scheduleService = mockk(relaxed = true)
        importService = PodcastImportService(podcastService, storageService, scheduleService, http)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        TestDatabase.cleanUp()
        if (::tempDir.isInitialized) tempDir.deleteRecursively()
    }

    private fun insertShow(
        source: PodcastSource = PodcastSource.FEED,
        key: String = UUID.randomUUID().toString()
    ): UUID = transaction(database) {
        val id = UUID.randomUUID()
        PodcastShowTable.insert {
            it[PodcastShowTable.id] = id
            it[showSource] = source
            it[sourceKey] = key
            it[title] = "Show"
            it[feedUrl] = if (source == PodcastSource.FEED) "$ORIGIN/feed.xml" else null
            it[localPath] = if (source == PodcastSource.LOCAL) "Show" else null
        }
        id
    }

    private fun insertEpisode(
        showId: UUID,
        url: String? = "$ORIGIN/media/ep1.mp3",
        contentType: String? = "audio/mpeg",
        state: PodcastImportState = PodcastImportState.NONE,
        attempts: Int = 0,
        path: String? = null,
        guid: String = UUID.randomUUID().toString(),
        published: Long = 1_000L
    ): UUID = transaction(database) {
        val id = UUID.randomUUID()
        PodcastEpisodeTable.insert {
            it[PodcastEpisodeTable.id] = id
            it[PodcastEpisodeTable.showId] = showId
            it[PodcastEpisodeTable.guid] = guid
            it[guidKey] = PodcastKeys.guidKey(guid)
            it[title] = "Episode"
            it[publishedAt] = published
            it[enclosureUrl] = url
            it[enclosureType] = contentType
            it[importState] = state
            it[importAttempts] = attempts
            it[filePath] = path
        }
        id
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun respondAudio(bytes: ByteArray, declaredLength: Int = bytes.size) {
        respondWith = {
            respond(
                bytes,
                HttpStatusCode.OK,
                headersOf(
                    HttpHeaders.ContentType to listOf("audio/mpeg"),
                    HttpHeaders.ContentLength to listOf(declaredLength.toString())
                )
            )
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `import writes the file and marks the episode imported`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId)
        val bytes = payload(1024 * 1024)
        respondAudio(bytes)

        val episode = podcastService.episodeById(episodeId)!!
        val result = importService.import(episode)

        assertTrue(result is ImportResult.Done, "expected Done but got $result")
        val expected = File(File(importsDir, showId.toString()), "$episodeId.mp3")
        assertTrue(expected.isFile, "expected ${expected.absolutePath} to exist")
        assertEquals(bytes.size.toLong(), expected.length())
        assertFalse(File(File(importsDir, showId.toString()), "$episodeId.part").exists())

        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.IMPORTED, row.importState)
        assertEquals(expected.absolutePath, row.filePath)
        assertEquals(bytes.size.toLong(), row.fileSize)
        assertEquals("mp3", row.format)
        assertNotNull(row.importedAt)
        assertNull(row.importError)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `import reports a permanent failure on 404`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId)
        respondWith = { respond(ByteArray(0), HttpStatusCode.NotFound) }

        val result = importService.import(podcastService.episodeById(episodeId)!!)

        assertTrue(result is ImportResult.Failed, "expected Failed but got $result")
        assertTrue((result as ImportResult.Failed).permanent)
        assertTrue(result.error.contains("404"), "expected the error to mention 404 but got ${result.error}")

        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.FAILED, row.importState)
        assertEquals(1, row.importAttempts)
        assertTrue(row.importError!!.contains("404"))
        assertNull(row.filePath)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `import fails when the body is shorter than the declared length`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId)
        respondAudio(payload(1000), declaredLength = 2000)

        val result = importService.import(podcastService.episodeById(episodeId)!!)

        assertTrue(result is ImportResult.Failed, "expected Failed but got $result")

        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.FAILED, row.importState)
        assertNull(row.filePath)

        val directory = File(importsDir, showId.toString())
        assertFalse(File(directory, "$episodeId.mp3").exists())
        assertFalse(File(directory, "$episodeId.part").exists())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `import rejects an html response`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId)
        respondWith = {
            respond(
                "<html>not audio</html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("text/html; charset=utf-8"))
            )
        }

        val result = importService.import(podcastService.episodeById(episodeId)!!)

        assertTrue(result is ImportResult.Failed, "expected Failed but got $result")
        assertTrue((result as ImportResult.Failed).permanent)

        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.FAILED, row.importState)
        assertNull(row.filePath)
        assertFalse(File(File(importsDir, showId.toString()), "$episodeId.mp3").exists())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `extensionFor maps content types and url extensions`(dialect: DbDialect) = runBlocking {
        setup(dialect)

        assertEquals("mp3", importService.extensionFor("audio/mpeg", "$ORIGIN/a"))
        assertEquals("m4a", importService.extensionFor("audio/x-m4a", "$ORIGIN/a"))
        assertEquals("opus", importService.extensionFor("audio/ogg", "$ORIGIN/a.opus"))
        assertEquals("ogg", importService.extensionFor("audio/ogg", "$ORIGIN/a.ogg"))
        assertEquals("flac", importService.extensionFor("application/octet-stream", "$ORIGIN/a.flac"))
        assertEquals("mp3", importService.extensionFor(null, "$ORIGIN/a.bin"))
        assertEquals("mp3", importService.extensionFor("audio/mpeg", "not a url at all"))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `processQueue recovers a stale importing row and imports it`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId, state = PodcastImportState.IMPORTING)
        val directory = File(importsDir, showId.toString()).apply { mkdirs() }
        val leftover = File(directory, "$episodeId.part").apply { writeText("partial") }
        respondAudio(payload(4096))

        val stats = importService.processQueue(maxConcurrent = 1)

        assertEquals(1, stats["recovered"])
        assertEquals(1, stats["imported"])
        assertEquals(0, stats["failed"])
        assertFalse(leftover.exists())
        assertTrue(File(directory, "$episodeId.mp3").isFile)

        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.IMPORTED, row.importState)
        assertEquals(0, row.importAttempts)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `processQueue imports every queued episode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val ids = (1..3).map { insertEpisode(showId, state = PodcastImportState.QUEUED, published = it * 1000L) }
        respondAudio(payload(2048))

        val stats = importService.processQueue(maxConcurrent = if (dialect == DbDialect.SQLITE) 1 else 2)

        assertEquals(3, stats["imported"])
        assertEquals(0, stats["failed"])
        assertEquals(0, stats["recovered"])
        ids.forEach { id ->
            assertEquals(PodcastImportState.IMPORTED, podcastService.episodeById(id)!!.importState)
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `importEpisode queues the episode resets attempts and triggers the task`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId, state = PodcastImportState.FAILED, attempts = 3)

        importService.importEpisode(episodeId)

        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.QUEUED, row.importState)
        assertEquals(0, row.importAttempts)
        verify { scheduleService.triggerTask(TaskKeys.PODCAST_IMPORT) }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `importEpisode without an enclosure is rejected`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId, url = null, contentType = null)

        assertThrows<IllegalArgumentException> {
            runBlocking { importService.importEpisode(episodeId) }
        }
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `removeImport deletes the imported file of a feed episode`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()
        val episodeId = insertEpisode(showId)
        respondAudio(payload(2048))
        importService.import(podcastService.episodeById(episodeId)!!)
        val imported = File(File(importsDir, showId.toString()), "$episodeId.mp3")
        assertTrue(imported.isFile)

        importService.removeImport(episodeId)

        assertFalse(imported.exists())
        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.NONE, row.importState)
        assertNull(row.filePath)
        assertNull(row.fileSize)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `removeImport rejects episodes of local shows`(dialect: DbDialect) = runBlocking<Unit> {
        setup(dialect)
        val showId = insertShow(source = PodcastSource.LOCAL)
        val file = File(libraryDir, "Show").apply { mkdirs() }.let { File(it, "ep.wav").apply { writeText("audio") } }
        val episodeId = insertEpisode(showId, url = null, contentType = null, path = file.absolutePath)

        assertThrows<IllegalArgumentException> {
            runBlocking { importService.removeImport(episodeId) }
        }
        assertTrue(file.exists())
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `deleteFile never removes a file outside the imports root`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val outside = File(tempDir, "elsewhere").apply { mkdirs() }.let {
            File(it, "kept.mp3").apply { writeText("keep me") }
        }
        val showId = insertShow()
        val episodeId = insertEpisode(showId, path = outside.absolutePath, state = PodcastImportState.IMPORTED)

        importService.deleteFile(podcastService.episodeById(episodeId)!!)

        assertTrue(outside.exists(), "a file outside the imports root must not be deleted")
        val row = podcastService.episodeById(episodeId)!!
        assertEquals(PodcastImportState.NONE, row.importState)
        assertNull(row.filePath)
    }

    companion object {
        private const val ORIGIN = "https://203.0.113.10"
    }
}
