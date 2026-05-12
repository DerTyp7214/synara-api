package dev.dertyp.services.youtube

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class YoutubeApiServiceTest {

    private lateinit var environment: ApplicationEnvironment
    private lateinit var config: ApplicationConfig
    private lateinit var service: YoutubeApiService

    @BeforeEach
    fun setup() {
        environment = mockk()
        config = mockk()
        every { environment.config } returns config
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `enabled should be true when apiKey is present`() {
        every { config.propertyOrNull("youtube.apiKey") } returns mockk { every { getString() } returns "test-key" }
        service = YoutubeApiService(environment)
        assertTrue(service.enabled)
    }

    @Test
    fun `enabled should be false when apiKey is missing`() {
        every { config.propertyOrNull("youtube.apiKey") } returns null
        service = YoutubeApiService(environment)
        assertFalse(service.enabled)
    }

    @Test
    fun `getVideoMetadata should return correct map`() = runBlocking {
        every { config.propertyOrNull("youtube.apiKey") } returns mockk { every { getString() } returns "test-key" }
        
        val mockEngine = MockEngine { _ ->
            respond(
                content = """
                    {
                      "items": [
                        {
                          "snippet": {
                            "title": "Test Video",
                            "channelTitle": "Test Channel",
                            "description": "Test Description",
                            "thumbnails": {
                              "maxres": { "url": "https://example.com/max.jpg", "width": 1280, "height": 720 }
                            }
                          }
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        service = YoutubeApiService(environment)
        val metadata = service.getVideoMetadata("test-id")

        assertNotNull(metadata)
        assertEquals("test-id", metadata?.get("id"))
        assertEquals("Test Video", metadata?.get("title"))
        assertEquals("Test Channel", metadata?.get("uploader"))
        assertEquals("https://example.com/max.jpg", metadata?.get("thumbnail"))
        assertEquals("1280", metadata?.get("width"))
        assertEquals("720", metadata?.get("height"))
    }

    @Test
    fun `getPlaylistItems should return all items with pagination`() = runBlocking {
        every { config.propertyOrNull("youtube.apiKey") } returns mockk { every { getString() } returns "test-key" }

        var callCount = 0
        val mockEngine = MockEngine { request ->
            callCount++
            val content = if (request.url.parameters["pageToken"] == null) {
                """{ "items": [{ "snippet": { "title": "Item 1" } }], "nextPageToken": "token2" }"""
            } else {
                """{ "items": [{ "snippet": { "title": "Item 2" } }] }"""
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        service = YoutubeApiService(environment)
        val items = service.getPlaylistItems("playlist-id")

        assertEquals(2, items.size)
        assertEquals(2, callCount)
        assertEquals("Item 1", items[0].snippet?.title)
        assertEquals("Item 2", items[1].snippet?.title)
    }

    @Test
    fun `getPlaylistMetadata should return metadata`() = runBlocking {
        every { config.propertyOrNull("youtube.apiKey") } returns mockk { every { getString() } returns "test-key" }

        val mockEngine = MockEngine { _ ->
            respond(
                content = """{ "items": [{ "snippet": { "title": "Playlist Title" } }] }""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        service = YoutubeApiService(environment)
        val metadata = service.getPlaylistMetadata("playlist-id")

        assertNotNull(metadata)
        assertEquals("Playlist Title", metadata?.snippet?.title)
    }
}
