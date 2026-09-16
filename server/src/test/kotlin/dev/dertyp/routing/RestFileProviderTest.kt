package dev.dertyp.routing

import dev.dertyp.StreamInfo
import dev.dertyp.routing.rest.*
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.ISongService
import dev.dertyp.utils.withAuthorization
import io.github.smiley4.ktoropenapi.OpenApi
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.routing
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

private class FileProbeSongService(
    private val delegate: ISongService,
    private val file: File?,
    private val fallback: ByteArray,
) : ISongService by delegate, RestFileProvider {
    var lastArgs: List<Any?>? = null

    override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
        lastArgs = args
        if (methodName != "streamSong") return null
        val target = file ?: return null
        return StreamInfo(target, ContentType.Audio.MPEG, target.length(), target.name)
    }

    fun fallbackBytes(): ByteArray = fallback
}

class RestFileProviderTest {
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var service: FileProbeSongService

    private val fileBytes = ByteArray(1000) { (it % 251).toByte() }
    private val fallbackBytes = "fallback-payload".toByteArray()
    private var serveFile = true

    @BeforeEach
    fun createFile() {
        directory = Files.createTempDirectory("rest-file-provider").toFile()
        file = File(directory, "probe.bin")
        file.writeBytes(fileBytes)
        serveFile = true
    }

    @AfterEach
    fun removeFile() {
        directory.deleteRecursively()
    }

    private fun ApplicationTestBuilder.setUpProbeApplication() {
        environment { config = MapApplicationConfig() }
        application {
            install(OpenApi)
            install(ContentNegotiation) { json(AppJson) }
            routing {
                registerISongServiceRest(authenticated = false) {
                    val delegate = mockk<ISongService>(relaxed = true)
                    every { delegate.streamSong(any(), any(), any()) } returns flowOf(fallbackBytes)
                    FileProbeSongService(delegate, if (serveFile) file else null, fallbackBytes)
                        .also { service = it }
                        .withAuthorization<ISongService>(null)
                }
            }
        }
    }

    private fun probePath(id: UUID = UUID.randomUUID()) = "/song/streamSong/$id?offset=0&chunkSize=4096"

    @Test
    fun `a file provider behind the authorization proxy serves the whole file`() = testApplication {
        setUpProbeApplication()

        val response = client.get(probePath())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
        assertArrayEquals(fileBytes, response.readRawBytes())
    }

    @Test
    fun `a range request is answered with partial content`() = testApplication {
        setUpProbeApplication()

        val response = client.get(probePath()) {
            header(HttpHeaders.Range, "bytes=100-199")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 100-199/1000", response.headers[HttpHeaders.ContentRange])
        assertEquals("100", response.headers[HttpHeaders.ContentLength])
        assertArrayEquals(fileBytes.copyOfRange(100, 200), response.readRawBytes())
    }

    @Test
    fun `a head request reports the length without a body`() = testApplication {
        setUpProbeApplication()

        val response = client.head(probePath())

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("1000", response.headers[HttpHeaders.ContentLength])
        assertTrue(response.readRawBytes().isEmpty())
    }

    @Test
    fun `a null file falls through to the byte flow`() = testApplication {
        setUpProbeApplication()
        serveFile = false

        val response = client.get(probePath())

        assertEquals(HttpStatusCode.OK, response.status)
        assertArrayEquals(fallbackBytes, response.readRawBytes())

        val ranged = client.get(probePath()) {
            header(HttpHeaders.Range, "bytes=0-3")
        }
        assertEquals(HttpStatusCode.OK, ranged.status)
        assertArrayEquals(fallbackBytes, ranged.readRawBytes())
    }

    @Test
    fun `a file response without the optional query parameters is served`() = testApplication {
        setUpProbeApplication()
        val id = UUID.randomUUID()

        val response = client.get("/song/streamSong/$id")

        assertEquals(HttpStatusCode.OK, response.status)
        assertArrayEquals(fileBytes, response.readRawBytes())
        assertEquals(listOf(id, null, null), service.lastArgs)
        assertArrayEquals(fallbackBytes, service.fallbackBytes())
    }
}
