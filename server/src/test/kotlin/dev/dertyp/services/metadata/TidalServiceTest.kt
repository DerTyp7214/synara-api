package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.plugins.RedisCacheProvider
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest

class TidalServiceTest : KoinTest {

    private lateinit var environment: ApplicationEnvironment
    private lateinit var tidalService: TidalService
    private lateinit var mockEngine: MockEngine

    @BeforeEach
    fun setup() {
        environment = mockk()
        val config = mockk<ApplicationConfig>()
        every { environment.config } returns config
        every { config.propertyOrNull("tidal.clientId") } returns mockk { every { getString() } returns "test-client-id" }
        every { config.propertyOrNull("tidal.clientSecret") } returns mockk { every { getString() } returns "test-client-secret" }

        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { redisConfig.host } returns "none"

        startKoin {
            modules(module {
                single { redisConfig }
            })
        }

        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/oauth2/token" -> {
                    respond(
                        content = """{"access_token": "test-token", "token_type": "Bearer", "expires_in": 3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                "/v2/searchResults/test" -> {
                    respond(
                        content = """
                            {
                              "data": {
                                "id": "search-results-id",
                                "type": "searchResults"
                              },
                              "included": [
                                {
                                  "id": "album-1",
                                  "type": "albums",
                                  "attributes": {
                                    "barcodeId": "123",
                                    "duration": "PT40M",
                                    "explicit": false,
                                    "mediaTags": [],
                                    "numberOfItems": 10,
                                    "numberOfVolumes": 1,
                                    "popularity": 0.5,
                                    "title": "Album 1",
                                    "type": "ALBUM"
                                  },
                                  "relationships": {}
                                },
                                {
                                  "id": "track-1",
                                  "type": "tracks",
                                  "attributes": {
                                    "duration": "PT3M",
                                    "explicit": false,
                                    "isrc": "123",
                                    "mediaTags": [],
                                    "popularity": 0.5,
                                    "title": "Track 1"
                                  },
                                  "relationships": {
                                    "albums": {
                                      "data": [{"id": "album-2", "type": "albums"}],
                                      "links": { "self": "test-url" }
                                    }
                                  }
                                }
                              ],
                              "links": { "self": "test-url" }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                    )
                }
                "/v2/albums" -> {
                    respond(
                        content = """
                            {
                              "data": [
                                {
                                  "id": "album-1",
                                  "type": "albums",
                                  "attributes": {
                                    "barcodeId": "123",
                                    "duration": "PT40M",
                                    "explicit": false,
                                    "mediaTags": [],
                                    "numberOfItems": 10,
                                    "numberOfVolumes": 1,
                                    "popularity": 0.5,
                                    "title": "Album 1",
                                    "type": "ALBUM",
                                    "releaseDate": "2023-01-01"
                                  },
                                  "relationships": {
                                    "artists": { 
                                      "data": [{"id": "artist-1", "type": "artists"}],
                                      "links": { "self": "test-url" }
                                    },
                                    "coverArt": { 
                                      "data": [{"id": "cover-1", "type": "artworks"}],
                                      "links": { "self": "test-url" }
                                    }
                                  }
                                },
                                {
                                  "id": "album-2",
                                  "type": "albums",
                                  "attributes": {
                                    "barcodeId": "456",
                                    "duration": "PT3M",
                                    "explicit": false,
                                    "mediaTags": [],
                                    "numberOfItems": 1,
                                    "numberOfVolumes": 1,
                                    "popularity": 0.5,
                                    "title": "Album 2",
                                    "type": "SINGLE",
                                    "releaseDate": "2023-02-01"
                                  },
                                  "relationships": {
                                    "artists": { 
                                      "data": [{"id": "artist-1", "type": "artists"}],
                                      "links": { "self": "test-url" }
                                    },
                                    "coverArt": { 
                                      "data": [{"id": "cover-2", "type": "artworks"}],
                                      "links": { "self": "test-url" }
                                    }
                                  }
                                }
                              ],
                              "included": [
                                {
                                  "id": "artist-1",
                                  "type": "artists",
                                  "attributes": { "name": "Artist 1", "popularity": 0.9 }
                                },
                                {
                                  "id": "cover-1",
                                  "type": "artworks",
                                  "attributes": { 
                                    "mediaType": "IMAGE",
                                    "files": [{"href": "https://example.com/cover1.jpg", "meta": {"width": 500, "height": 500}}] 
                                  }
                                },
                                {
                                  "id": "cover-2",
                                  "type": "artworks",
                                  "attributes": { 
                                    "mediaType": "IMAGE",
                                    "files": [{"href": "https://example.com/cover2.jpg", "meta": {"width": 500, "height": 500}}] 
                                  }
                                }
                              ],
                              "links": { "self": "test-url" }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
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

        tidalService = TidalService(environment)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `searchAlbums should return albums from both direct results and tracks`() = runBlocking {
        val albums = tidalService.searchAlbums("test", 10, includeTracks = true)

        assertEquals(2, albums.size)
        
        val album1 = albums.find { it.id == "album-1" }
        assertEquals("Album 1", album1?.title)
        assertEquals(listOf("Artist 1"), album1?.artists)
        assertEquals("https://example.com/cover1.jpg", album1?.images?.firstOrNull()?.url)

        val album2 = albums.find { it.id == "album-2" }
        assertEquals("Album 2", album2?.title)
        assertEquals(listOf("Artist 1"), album2?.artists)
        assertEquals("https://example.com/cover2.jpg", album2?.images?.firstOrNull()?.url)
    }
}
