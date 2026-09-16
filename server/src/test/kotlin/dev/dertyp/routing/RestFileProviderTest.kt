package dev.dertyp.routing

import dev.dertyp.StreamInfo
import dev.dertyp.rpc.annotations.RestFileResponse
import dev.dertyp.serializers.AppJson
import dev.dertyp.utils.withAuthorization
import io.github.smiley4.ktoropenapi.OpenApi
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
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

interface IFileProbeService {
    @RestFileResponse
    fun streamProbe(
        probeId: UUID,
        offset: Long = 0,
        chunkSize: Int = 4096
    ): Flow<ByteArray>?
}

private class FileProbeService(
    private val file: File?,
    private val fallback: ByteArray
) : IFileProbeService, RestFileProvider {
    override fun streamProbe(probeId: UUID, offset: Long, chunkSize: Int): Flow<ByteArray> = flowOf(fallback)

    override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
        if (methodName != "streamProbe") return null
        val target = file ?: return null
        return StreamInfo(target, ContentType.Application.OctetStream, target.length(), target.name)
    }
}

class RestFileProviderTest {
    private lateinit var directory: File
    private lateinit var file: File

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
                registerRestService(IFileProbeService::class, authenticated = false) {
                    FileProbeService(if (serveFile) file else null, fallbackBytes)
                        .withAuthorization<IFileProbeService>(null)
                }
            }
        }
    }

    private fun probePath() = "/fileProbe/streamProbe/${UUID.randomUUID()}?offset=0&chunkSize=4096"

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

        val response = client.get("/fileProbe/streamProbe/${UUID.randomUUID()}")

        assertEquals(HttpStatusCode.OK, response.status)
        assertArrayEquals(fileBytes, response.readRawBytes())
    }
}
