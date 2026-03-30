package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
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
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.ApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.KoinTest

class SpotifyServiceTest : KoinTest {

    private lateinit var environment: ApplicationEnvironment
    private lateinit var spotifyService: SpotifyService
    private lateinit var mockEngine: MockEngine

    @BeforeEach
    fun setup() {
        environment = mockk()
        val config = mockk<ApplicationConfig>()
        every { environment.config } returns config
        every { config.propertyOrNull("spotify.clientId") } returns mockk { every { getString() } returns "test-client-id" }
        every { config.propertyOrNull("spotify.clientSecret") } returns mockk { every { getString() } returns "test-client-secret" }

        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/token" -> {
                    respond(
                        content = """{"access_token": "test-token", "token_type": "Bearer", "expires_in": 3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                "/v1/search" -> {
                    respond(
                        content = """
                            {
                              "artists": {
                                "href": "https://api.spotify.com/v1/search?query=test&type=artist&offset=0&limit=10",
                                "limit": 10,
                                "next": null,
                                "offset": 0,
                                "previous": null,
                                "total": 1,
                                "items": [
                                  {
                                    "id": "artist-id-1",
                                    "name": "Test Artist",
                                    "popularity": 80,
                                    "href": "https://api.spotify.com/v1/artists/artist-id-1",
                                    "genres": ["genre1"],
                                    "uri": "spotify:artist:artist-id-1",
                                    "images": [
                                      {
                                        "url": "https://example.com/image.jpg",
                                        "width": 640,
                                        "height": 640
                                      }
                                    ]
                                  }
                                ]
                              }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val mockHttpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(ApplicationScope.json)
            }
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns mockHttpClient

        spotifyService = SpotifyService(environment)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `searchArtists should return list of artists`() = runBlocking {
        val artists = spotifyService.searchArtists("test", 10)

        assertEquals(1, artists.size)
        assertEquals("artist-id-1", artists[0].id)
        assertEquals("Test Artist", artists[0].name)
        assertEquals(80f, artists[0].popularity)
        assertEquals(1, artists[0].images.size)
        assertEquals("https://example.com/image.jpg", artists[0].images[0].url)
    }
}
