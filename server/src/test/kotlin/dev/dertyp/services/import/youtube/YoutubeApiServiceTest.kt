package dev.dertyp.services.import.youtube

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.youtube.YoutubeApiService
import dev.dertyp.testing.FakeCredentialProvider
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class YoutubeApiServiceTest {

    private lateinit var credentialProvider: FakeCredentialProvider
    private lateinit var service: YoutubeApiService

    @BeforeEach
    fun setup() {
        credentialProvider = FakeCredentialProvider()
        startKoin {
            modules(module {
                single { HttpClientQueueService() }
                single<CredentialProvider> { credentialProvider }
            })
        }
    }

    private fun provideApiKey() {
        credentialProvider.put(ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "test-key"))
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `enabled should be true when apiKey is present`() {
        provideApiKey()
        service = YoutubeApiService()
        assertTrue(service.enabled)
    }

    @Test
    fun `enabled should be false when apiKey is missing`() {
        service = YoutubeApiService()
        assertFalse(service.enabled)
    }

    @Test
    fun `getVideoMetadata should return correct map`() = runBlocking {
        provideApiKey()
        val apiKeys = mutableListOf<String?>()

        val mockEngine = MockEngine { request ->
            if (request.url.host == "www.googleapis.com") apiKeys += request.url.parameters["key"]
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

        service = YoutubeApiService()
        val metadata = service.getVideoMetadata("test-id")

        assertNotNull(metadata)
        assertEquals("test-id", metadata?.get("id"))
        assertEquals("Test Video", metadata?.get("title"))
        assertEquals("Test Channel", metadata?.get("uploader"))
        assertEquals("https://example.com/max.jpg", metadata?.get("thumbnail"))
        assertEquals("1280", metadata?.get("width"))
        assertEquals("720", metadata?.get("height"))
        assertEquals(listOf<String?>("test-key"), apiKeys)
    }

    @Test
    fun `getPlaylistItems should return all items with pagination`() = runBlocking {
        provideApiKey()

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

        service = YoutubeApiService()
        val items = service.getPlaylistItems("playlist-id")

        assertEquals(2, items.size)
        assertEquals(2, callCount)
        assertEquals("Item 1", items[0].snippet?.title)
        assertEquals("Item 2", items[1].snippet?.title)
    }

    @Test
    fun `getPlaylistMetadata should return metadata`() = runBlocking {
        provideApiKey()

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

        service = YoutubeApiService()
        val metadata = service.getPlaylistMetadata("playlist-id")

        assertNotNull(metadata)
        assertEquals("Playlist Title", metadata?.snippet?.title)
    }
}
