package dev.dertyp.services

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LrcLibServiceTest {

    private val service = LrcLibService()
    private lateinit var mockEngine: MockEngine
    private lateinit var queueService: HttpClientQueueService

    @BeforeEach
    fun setup() {
        runBlocking {
            mockEngine = MockEngine { request ->
                if (request.url.toString().startsWith("https://lrclib.net/api/get")) {
                    respond(
                        content = """
                            {
                                "id": 1,
                                "trackName": "Track",
                                "artistName": "Artist",
                                "albumName": "Album",
                                "duration": 180.0,
                                "instrumental": false,
                                "syncedLyrics": "[00:10.00] Lyrics"
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                } else {
                    respondError(HttpStatusCode.NotFound)
                }
            }

            val mockHttpClient = HttpClient(mockEngine) {
                install(ContentNegotiation) {
                    json(ApplicationScope.json)
                }
            }

            mockkObject(ApiClient)
            every { ApiClient.instance } returns mockHttpClient

            queueService = HttpClientQueueService()
            queueService.startService()
            every { ApiClient.queueInstance } returns queueService
        }
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            queueService.stopService()
        }
        unmockkAll()
    }

    @Test
    fun `getLyrics should return response on success`() = runBlocking {
        val result = service.getLyrics("Artist", "Track", "Album", 180000L)

        assertEquals("Artist", result?.artistName)
        assertEquals("[00:10.00] Lyrics", result?.syncedLyrics)
    }

    @Test
    fun `getLyrics should return null on not found`() = runBlocking {
        mockEngine = MockEngine { _ ->
            respondError(HttpStatusCode.NotFound)
        }
        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }
        every { ApiClient.instance } returns mockHttpClient

        val result = service.getLyrics("Artist", "Track", "Album", 180000L)

        assertNull(result)
    }
}
