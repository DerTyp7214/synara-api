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
                    val type = request.url.parameters["type"]
                    val content = when (type) {
                        "artist" -> """
                            {
                              "artists": {
                                "href": "url", "limit": 10, "next": null, "offset": 0, "previous": null, "total": 1,
                                "items": [
                                  {
                                    "id": "artist-id-1",
                                    "name": "Test Artist",
                                    "popularity": 80,
                                    "href": "https://api.spotify.com/v1/artists/artist-id-1",
                                    "uri": "spotify:artist:artist-id-1",
                                    "images": [{ "url": "https://example.com/image.jpg", "width": 640, "height": 640 }]
                                  }
                                ]
                              }
                            }
                        """.trimIndent()
                        "track" -> """
                            {
                              "tracks": {
                                "href": "url", "limit": 10, "next": null, "offset": 0, "previous": null, "total": 1,
                                "items": [
                                  {
                                    "id": "track-id-1",
                                    "name": "Test Track",
                                    "duration_ms": 180000,
                                    "href": "https://api.spotify.com/v1/tracks/track-id-1",
                                    "artists": [{ "id": "a1", "name": "Test Artist", "href": "h", "uri": "u" }],
                                    "album": { "id": "al1", "name": "Test Album", "href": "h", "total_tracks": 1, "artists": [], "images": [{ "url": "https://example.com/cover.jpg", "width": 640, "height": 640 }] }
                                  }
                                ]
                              }
                            }
                        """.trimIndent()
                        "album" -> """
                            {
                              "albums": {
                                "href": "url", "limit": 10, "next": null, "offset": 0, "previous": null, "total": 1,
                                "items": [
                                  {
                                    "id": "album-id-1",
                                    "name": "Test Album",
                                    "href": "https://api.spotify.com/v1/albums/album-id-1",
                                    "total_tracks": 1,
                                    "artists": [{ "id": "a1", "name": "Test Artist", "href": "h", "uri": "u" }],
                                    "images": [{ "url": "https://example.com/cover.jpg", "width": 640, "height": 640 }],
                                    "release_date": "2023-01-01"
                                  }
                                ]
                              }
                            }
                        """.trimIndent()
                        else -> "{}"
                    }
                    respond(
                        content = content,
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
    }

    @Test
    fun `search should return list of tracks`() = runBlocking {
        val tracks = spotifyService.search("test", 10)

        assertEquals(1, tracks.size)
        assertEquals("track-id-1", tracks[0].id)
        assertEquals("Test Track", tracks[0].title)
        assertEquals(listOf("Test Artist"), tracks[0].artists)
    }

    @Test
    fun `searchAlbums should return list of albums`() = runBlocking {
        val albums = spotifyService.searchAlbums("test", 10)

        assertEquals(1, albums.size)
        assertEquals("album-id-1", albums[0].id)
        assertEquals("Test Album", albums[0].title)
    }

    @Test
    fun `getTrackByIsrc should return track for valid ISRC`() = runBlocking {
        val isrc = "USUM71900764"
        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/token" -> respond(
                    content = """{"access_token": "test-token", "token_type": "Bearer", "expires_in": 3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
                "/v1/search" -> {
                    assertEquals("isrc:$isrc", request.url.parameters["q"])
                    assertEquals("track", request.url.parameters["type"])
                    respond(
                        content = """
                            {
                              "tracks": {
                                "href": "url", "limit": 1, "next": null, "offset": 0, "previous": null, "total": 1,
                                "items": [
                                  {
                                    "id": "track-id-1",
                                    "name": "Test Track",
                                    "duration_ms": 180000,
                                    "href": "https://api.spotify.com/v1/tracks/track-id-1",
                                    "artists": [{ "id": "a1", "name": "Test Artist", "href": "h", "uri": "u" }],
                                    "album": { "id": "al1", "name": "Test Album", "href": "h", "total_tracks": 1, "artists": [], "images": [{ "url": "https://example.com/cover.jpg", "width": 640, "height": 640 }] },
                                    "external_ids": { "isrc": "$isrc" }
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
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val track = spotifyService.getTrackByIsrc(isrc)

        assertEquals("track-id-1", track?.id)
        assertEquals("Test Track", track?.title)
        assertEquals(isrc, track?.isrc)
    }
}
