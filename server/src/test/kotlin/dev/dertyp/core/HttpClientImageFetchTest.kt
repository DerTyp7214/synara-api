package dev.dertyp.core

import dev.dertyp.ApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val HOST = "https://example.com"

private const val NOT_FOUND_HTML =
    "<!doctype html><html lang=en><title>404 Not Found</title><h1>Not Found</h1>" +
        "<p>The requested URL was not found on the server.</p>"

private val JpegBytes = byteArrayOf(
    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01
)

private val PngBytes = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
)

private val Mp4Bytes = byteArrayOf(
    0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70,
    0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00
)

private val HtmlBytes = NOT_FOUND_HTML.toByteArray(Charsets.UTF_8)

class HttpClientImageFetchTest {

    private lateinit var mockEngine: MockEngine
    private lateinit var client: HttpClient
    private lateinit var queueService: HttpClientQueueService

    @BeforeEach
    fun setup(): Unit = runBlocking {
        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/missing" -> respond(
                    content = HtmlBytes,
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString())
                )

                "/jpeg" -> respond(
                    content = JpegBytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Image.JPEG.toString())
                )

                "/png" -> respond(
                    content = PngBytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Image.PNG.toString())
                )

                "/mp4" -> respond(
                    content = Mp4Bytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Video.MP4.toString())
                )

                else -> respond(
                    content = HtmlBytes,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString())
                )
            }
        }

        client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns client

        queueService = HttpClientQueueService()
        queueService.startService()
        every { ApiClient.queueInstance } returns queueService
    }

    @AfterEach
    fun tearDown(): Unit = runBlocking {
        queueService.stopService()
        unmockkAll()
    }

    @Test
    fun `safeGetImage returns null for a 404 html page`() = runBlocking {
        assertNull(client.safeGetImage("$HOST/missing"))
        assertNull(client.safeGet<ByteArray>("$HOST/missing"))
    }

    @Test
    fun `safeGetImage returns the bytes of a jpeg response`() = runBlocking {
        assertArrayEquals(JpegBytes, client.safeGetImage("$HOST/jpeg"))
    }

    @Test
    fun `safeGetImage returns null for an html body served with 200`() = runBlocking {
        assertNull(client.safeGetImage("$HOST/html"))
    }

    @Test
    fun `safeGetImage returns the bytes of a png response`() = runBlocking {
        assertArrayEquals(PngBytes, client.safeGetImage("$HOST/png"))
    }

    @Test
    fun `safeGetImage returns null for a video response`() = runBlocking {
        assertNull(client.safeGetImage("$HOST/mp4"))
    }

    @Test
    fun `safeQueuedGetImage returns null for an html body and bytes for a jpeg`() = runBlocking {
        assertNull(client.safeQueuedGetImage("$HOST/html"))
        assertArrayEquals(JpegBytes, client.safeQueuedGetImage("$HOST/jpeg"))
    }

    @Test
    fun `isImage accepts image magic bytes only`() {
        assertTrue(JpegBytes.isImage())
        assertTrue(PngBytes.isImage())
        assertFalse(HtmlBytes.isImage())
        assertFalse(Mp4Bytes.isImage())
        assertFalse(ByteArray(0).isImage())
    }
}
