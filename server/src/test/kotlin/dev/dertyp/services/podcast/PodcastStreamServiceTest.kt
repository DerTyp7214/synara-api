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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class PodcastStreamServiceTest : KoinTest {
    private lateinit var database: Database
    private lateinit var podcastService: PodcastService
    private lateinit var streamService: PodcastStreamService
    private lateinit var directory: File

    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private var respondTo: MockRequestHandler = { respondError(HttpStatusCode.NotFound) }

    private val originAddress = "https://example.com/audio.mp3"

    private fun setup(dialect: DbDialect) {
        startKoin { modules(module { }) }

        database = TestDatabase.connect(dialect, "podcast_stream_test")
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

        directory = Files.createTempDirectory("podcast-stream").toFile()

        val http = spyk(PodcastHttp())
        every { http.mediaClient } returns HttpClient(
            MockEngine { request ->
                requests += request
                respondTo(this, request)
            }
        )

        podcastService = PodcastService(http)
        streamService = PodcastStreamService(podcastService, http)
    }

    @AfterEach
    fun tearDown() {
        requests.clear()
        respondTo = { respondError(HttpStatusCode.NotFound) }
        directory.deleteRecursively()
        stopKoin()
        TestDatabase.cleanUp()
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    private fun writeFile(name: String, bytes: ByteArray): File {
        val file = File(directory, name)
        file.writeBytes(bytes)
        return file
    }

    private fun insertShow(): UUID = transaction(database) {
        val show = UUID.randomUUID()
        PodcastShowTable.insert {
            it[id] = show
            it[showSource] = PodcastSource.FEED
            it[sourceKey] = "FEED:$show"
            it[feedUrl] = "https://example.com/feed.xml"
            it[title] = "Show"
        }
        show
    }

    private fun insertEpisode(
        show: UUID,
        storedFile: File? = null,
        storedSize: Long? = null,
        origin: String? = null,
        originLength: Long? = null,
    ): UUID = transaction(database) {
        val episode = UUID.randomUUID()
        PodcastEpisodeTable.insert {
            it[id] = episode
            it[showId] = show
            it[guid] = "guid-$episode"
            it[guidKey] = PodcastKeys.guidKey("guid-$episode")
            it[title] = "Episode"
            it[publishedAt] = 1_725_969_600_000L
            it[filePath] = storedFile?.absolutePath
            it[fileSize] = storedSize ?: storedFile?.length()
            it[format] = storedFile?.extension
            it[enclosureUrl] = origin
            it[enclosureLength] = originLength
            it[importState] = if (storedFile == null) PodcastImportState.NONE else PodcastImportState.IMPORTED
        }
        episode
    }

    private suspend fun collect(episodeId: UUID, offset: Long, chunkSize: Int = 4096): List<ByteArray> {
        val flow = requireNotNull(streamService.streamEpisode(episodeId, offset, chunkSize)) {
            "Episode $episodeId did not produce a stream"
        }
        return flow.toList()
    }

    private fun ByteArray.slice(from: Int): ByteArray = copyOfRange(from, size)

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a local file is streamed completely and honours offset and chunk size`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bytes = payload(1000)
        val file = writeFile("episode.mp3", bytes)
        val episodeId = insertEpisode(insertShow(), storedFile = file)

        val full = collect(episodeId, 0, 64)
        assertArrayEquals(bytes, full.reduce { left, right -> left + right })
        assertEquals(16, full.size)
        assertTrue(full.all { it.size <= 64 })

        val fromOffset = collect(episodeId, 100, 4096)
        assertArrayEquals(bytes.slice(100), fromOffset.reduce { left, right -> left + right })
        assertEquals(1, fromOffset.size)
        assertEquals(0, requests.size)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `resolveFile describes stored files and is null for origin only episodes`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bytes = payload(512)
        val file = writeFile("episode.mp3", bytes)
        val showId = insertShow()
        val storedId = insertEpisode(showId, storedFile = file)
        val originId = insertEpisode(showId, origin = originAddress)

        val info = requireNotNull(streamService.resolveFile(storedId))
        assertEquals(file.absolutePath, info.file.absolutePath)
        assertEquals(ContentType.Audio.MPEG, info.contentType)
        assertEquals(512L, info.contentLength)
        assertEquals("episode.mp3", info.fileName)

        assertNull(streamService.resolveFile(originId))
        assertNull(streamService.resolveFile(UUID.randomUUID()))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `a vanished local file clears the import and resolves to null`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val file = writeFile("gone.mp3", payload(64))
        val episodeId = insertEpisode(insertShow(), storedFile = file)
        assertTrue(file.delete())

        assertNull(streamService.resolveFile(episodeId))

        val row = requireNotNull(podcastService.episodeById(episodeId))
        assertEquals(PodcastImportState.NONE, row.importState)
        assertNull(row.filePath)
        assertNull(row.fileSize)
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an origin episode is streamed from the range the origin honours`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bytes = payload(600)
        val episodeId = insertEpisode(insertShow(), origin = originAddress)

        respondTo = { request ->
            val range = request.headers[HttpHeaders.Range]
            if (range == "bytes=100-") {
                respond(
                    bytes.slice(100),
                    HttpStatusCode.PartialContent,
                    headersOf(HttpHeaders.ContentRange, "bytes 100-599/600")
                )
            } else {
                respondError(HttpStatusCode.BadRequest)
            }
        }

        val chunks = collect(episodeId, 100, 4096)

        assertArrayEquals(bytes.slice(100), chunks.reduce { left, right -> left + right })
        assertEquals("bytes=100-", requests.single().headers[HttpHeaders.Range])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `an origin that ignores the range is skipped manually`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val bytes = payload(600)
        val episodeId = insertEpisode(insertShow(), origin = originAddress)

        respondTo = { respond(bytes, HttpStatusCode.OK) }

        val chunks = collect(episodeId, 250, 128)

        assertArrayEquals(bytes.slice(250), chunks.reduce { left, right -> left + right })
        assertEquals("bytes=250-", requests.single().headers[HttpHeaders.Range])
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `streamEpisode is null when there is neither a file nor an origin`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val episodeId = insertEpisode(insertShow())

        assertNull(streamService.streamEpisode(episodeId, 0, 4096))
        assertNull(streamService.streamEpisode(UUID.randomUUID(), 0, 4096))
    }

    @ParameterizedTest
    @EnumSource(DbDialect::class)
    fun `getStreamSize prefers the file then the stored sizes then a head request`(dialect: DbDialect) = runBlocking {
        setup(dialect)
        val showId = insertShow()

        val file = writeFile("sized.mp3", payload(321))
        val storedId = insertEpisode(showId, storedFile = file, storedSize = 1L)
        assertEquals(321L, streamService.getStreamSize(storedId))

        val fileSizeId = insertEpisode(showId, storedSize = 777L, origin = originAddress, originLength = 555L)
        assertEquals(777L, streamService.getStreamSize(fileSizeId))

        val enclosureId = insertEpisode(showId, origin = originAddress, originLength = 555L)
        assertEquals(555L, streamService.getStreamSize(enclosureId))

        assertEquals(0, requests.size)

        respondTo = {
            respond(ByteArray(0), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "4242"))
        }
        val headId = insertEpisode(showId, origin = originAddress)
        assertEquals(4242L, streamService.getStreamSize(headId))
        assertEquals(4242L, requireNotNull(podcastService.episodeById(headId)).enclosureLength)
        assertNotNull(requests.firstOrNull())

        val emptyId = insertEpisode(showId)
        assertEquals(0L, streamService.getStreamSize(emptyId))
        assertEquals(0L, streamService.getStreamSize(UUID.randomUUID()))
    }
}
