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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
                    val include = request.url.parameters["include"]
                    val included = mutableListOf<String>()
                    if (include?.contains("artists") == true) {
                        included.add("""
                            {
                              "id": "artist-1",
                              "type": "artists",
                              "attributes": { "name": "Artist 1", "popularity": 0.9 }
                            }
                        """.trimIndent())
                    }
                    if (include?.contains("albums") == true) {
                         included.add("""
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
                              }
                            }
                        """.trimIndent())
                    }
                    if (include?.contains("tracks") == true) {
                         included.add("""
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
                              }
                            }
                        """.trimIndent())
                    }

                    respond(
                        content = """
                            {
                              "data": {
                                "id": "test",
                                "type": "searchResults",
                                "attributes": { "trackingId": "track-id" }
                              },
                              "links": { "self": "/v2/searchResults/test" },
                              "included": [ ${included.joinToString(",")} ]
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
                                    "artists": { "links": { "self": "url" }, "data": [{"id": "artist-1", "type": "artists"}] },
                                    "coverArt": { "links": { "self": "url" }, "data": [{"id": "cover-1", "type": "artworks"}] }
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
                                }
                              ],
                              "links": { "self": "/v2/albums" }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                    )
                }
                "/v2/tracks/track-1" -> {
                    respond(
                        content = """
                            {
                              "data": {
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
                                  "albums": { "links": { "self": "url" }, "data": [{"id": "album-1", "type": "albums"}] },
                                  "artists": { "links": { "self": "url" }, "data": [{"id": "artist-1", "type": "artists"}] }
                                }
                              },
                              "included": [
                                { "id": "artist-1", "type": "artists", "attributes": { "name": "Artist 1", "popularity": 0.9 } },
                                { "id": "album-1", "type": "albums", "attributes": { "barcodeId": "123", "title": "Album 1", "duration": "PT40M", "explicit": false, "mediaTags": [], "numberOfItems": 10, "numberOfVolumes": 1, "popularity": 0.5, "type": "ALBUM" } }
                              ],
                              "links": { "self": "/v2/tracks/track-1" }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                    )
                }
                "/v2/artists" -> {
                     respond(
                        content = """
                            {
                              "data": [
                                { "id": "artist-1", "type": "artists", "attributes": { "name": "Artist 1", "popularity": 0.9 } }
                              ],
                              "included": [],
                              "links": { "self": "/v2/artists" }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                    )
                }
                "/v2/artists/artist-1/relationships/tracks" -> {
                    respond(
                        content = """
                            {
                              "data": [
                                { "id": "track-1", "type": "tracks" }
                              ],
                              "included": [
                                { "id": "track-1", "type": "tracks", "attributes": { "title": "Track 1", "duration": "PT3M", "explicit": false, "isrc": "123", "mediaTags": [], "popularity": 0.5 } }
                              ],
                              "links": { "self": "/v2/artists/artist-1/relationships/tracks" }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                    )
                }
                "/v2/albums/album-1/relationships/coverArt" -> {
                    respond(
                        content = """
                            {
                              "data": [ { "id": "cover-1", "type": "artworks" } ],
                              "included": [
                                {
                                  "id": "cover-1",
                                  "type": "artworks",
                                  "attributes": { 
                                    "mediaType": "IMAGE",
                                    "files": [{"href": "https://example.com/cover1.jpg", "meta": {"width": 500, "height": 500}}] 
                                  }
                                }
                              ],
                              "links": { "self": "url" }
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
    fun `searchAlbums should return albums`() = runBlocking {
        val albums = tidalService.searchAlbums("test", 10, includeTracks = true)

        assertEquals(1, albums.size)
        assertEquals("Album 1", albums[0].title)
    }

    @Test
    fun `searchArtists should return matching artists`() = runBlocking {
        val artists = tidalService.searchArtists("test", 10)
        assertEquals(1, artists.size)
        assertEquals("Artist 1", artists[0].name)
    }

    @Test
    fun `getTrackById should return enriched track`() = runBlocking {
        val track = tidalService.getTrackById("track-1")
        assertNotNull(track)
        assertEquals("Track 1", track?.title)
        assertEquals(listOf("Artist 1"), track?.artists)
        assertEquals("Album 1", track?.albumTitle)
    }

    @Test
    fun `getArtistTracks should return tracks flow`() = runBlocking {
        val tracks = tidalService.getArtistTracks("artist-1").toList()
        assertEquals(1, tracks.size)
        assertEquals("Track 1", tracks[0].title)
    }

    @Test
    fun `getTrackByIsrc should return track for valid ISRC`() = runBlocking {
        val isrc = "USUM71900764"
        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v1/oauth2/token" -> respond(
                    content = """{"access_token": "test-token", "token_type": "Bearer", "expires_in": 3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "/v2/tracks" -> {
                    assertEquals(isrc, request.url.parameters["filter[isrc]"])
                    respond(
                        content = """
                            {
                              "data": [
                                {
                                  "id": "track-isrc-1",
                                  "type": "tracks",
                                  "attributes": {
                                    "title": "Test Track ISRC",
                                    "duration": "PT3M",
                                    "isrc": "$isrc",
                                    "explicit": false,
                                    "mediaTags": [],
                                    "popularity": 0.5
                                  },
                                  "relationships": {
                                    "albums": { "data": [{"id": "album-1", "type": "albums"}], "links": { "self": "url" } },
                                    "artists": { "data": [{"id": "artist-1", "type": "artists"}], "links": { "self": "url" } }
                                  }
                                }
                              ],
                              "included": [
                                { "id": "artist-1", "type": "artists", "attributes": { "name": "Artist 1", "popularity": 0.9 } },
                                { "id": "album-1", "type": "albums", "attributes": { "barcodeId": "123", "title": "Album 1", "duration": "PT40M", "explicit": false, "mediaTags": [], "numberOfItems": 10, "numberOfVolumes": 1, "popularity": 0.5, "type": "ALBUM" } }
                              ],
                              "links": { "self": "/v2/tracks" }
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                    )
                }
                "/v2/albums/album-1/relationships/coverArt" -> respond(
                    content = """{"data": [], "included": [], "links": {"self": "url"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val track = tidalService.getTrackByIsrc(isrc)

        assertEquals("track-isrc-1", track?.id)
        assertEquals("Test Track ISRC", track?.title)
        assertEquals(isrc, track?.isrc)
    }
}
