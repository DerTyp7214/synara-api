package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationEnvironment
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.test.KoinTest
import java.time.LocalDate

class AppleMusicServiceTest : KoinTest {

    private lateinit var environment: ApplicationEnvironment
    private lateinit var appleMusicService: AppleMusicService
    private lateinit var mockEngine: MockEngine

    @BeforeEach
    fun setup() {
        environment = mockk()
        every { environment.config } returns mockk(relaxed = true)

        mockEngine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/search" -> {
                    val entity = request.url.parameters["entity"]
                    val content = when {
                        entity == "musicArtist" -> """
                            {
                              "resultCount": 1,
                              "results": [
                                {
                                  "wrapperType": "artist",
                                  "artistId": 12345,
                                  "artistName": "Test Artist",
                                  "artistLinkUrl": "https://music.apple.com/artist/test-artist/12345"
                                }
                              ]
                            }
                        """.trimIndent()
                        entity == "album" -> """
                            {
                              "resultCount": 1,
                              "results": [
                                {
                                  "wrapperType": "collection",
                                  "collectionId": 67890,
                                  "artistName": "Test Artist",
                                  "collectionName": "Test Album",
                                  "artworkUrl100": "https://example.com/100x100bb.jpg",
                                  "trackCount": 10
                                }
                              ]
                            }
                        """.trimIndent()
                        entity?.contains("song") == true -> """
                            {
                              "resultCount": 2,
                              "results": [
                                {
                                  "wrapperType": "collection",
                                  "collectionId": 67890,
                                  "artistName": "Test Artist",
                                  "collectionName": "Test Album",
                                  "artworkUrl100": "https://example.com/100x100bb.jpg",
                                  "trackCount": 1
                                },
                                {
                                  "wrapperType": "track",
                                  "collectionId": 67890,
                                  "artistName": "Test Artist",
                                  "collectionName": "Test Album",
                                  "trackName": "Test Song",
                                  "artworkUrl100": "https://example.com/100x100bb.jpg",
                                  "trackCount": 1,
                                  "trackTimeMillis": 180000
                                }
                              ]
                            }
                        """.trimIndent()
                        else -> """{"resultCount": 0, "results": []}"""
                    }
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
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

        appleMusicService = AppleMusicService(environment)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `searchArtists should return list of artists`() = runBlocking {
        val artists = appleMusicService.searchArtists("test", 10)

        assertEquals(1, artists.size)
        assertEquals("12345", artists[0].id)
        assertEquals("Test Artist", artists[0].name)
        assertEquals("https://music.apple.com/artist/test-artist/12345", artists[0].url)
    }

    @Test
    fun `search should return list of tracks`() = runBlocking {
        val tracks = appleMusicService.search("test", 10)

        assertEquals(1, tracks.size)
        assertEquals("67890", tracks[0].id)
        assertEquals("Test Song", tracks[0].title)
        assertEquals(listOf("Test Artist"), tracks[0].artists)
    }

    @Test
    fun `searchAlbums should return list of albums with 600x600 image`() = runBlocking {
        val albums = appleMusicService.searchAlbums("test", 10, includeTracks = false)

        assertEquals(1, albums.size)
        assertEquals("67890", albums[0].id)
        assertEquals("Test Album", albums[0].title)
        assertEquals(listOf("Test Artist"), albums[0].artists)
        assertEquals(10, albums[0].trackCount)
        assertEquals("https://example.com/600x600bb.jpg", albums[0].images[0].url)
    }

    @Test
    fun `searchAlbums with includeTracks should return unique albums from mixed results`() = runBlocking {
        val albums = appleMusicService.searchAlbums("test", 10, includeTracks = true)

        assertEquals(1, albums.size)
        assertEquals("67890", albums[0].id)
        assertEquals("Test Album", albums[0].title)
    }

    @Test
    fun `searchAlbums with real-world mixed response should return unique albums`() = runBlocking {
        mockEngine = MockEngine { _ ->
            respond(
                content = """
                    {
                     "resultCount":6,
                     "results": [
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1712105785, "trackId":1712106051, "artistName":"Bonez MC", "collectionName":"LOVELINE EP 💔", "trackName":"Das ist Bonez 💀", "artworkUrl100":"https://example.com/image1.jpg", "trackCount":6}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1528317784, "trackId":1528318708, "artistName":"Bonez MC", "collectionName":"Hollywood", "trackName":"Papa ist in Hollywood", "artworkUrl100":"https://example.com/image2.jpg", "trackCount":13}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1545129420, "trackId":1545129708, "artistName":"Bonez MC", "collectionName":"Hollywood Uncut", "trackName":"Angeklagt", "artworkUrl100":"https://example.com/image3.jpg", "trackCount":13}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1528317784, "trackId":1528318175, "artistName":"Bonez MC", "collectionName":"Hollywood", "trackName":"Tilidin Weg", "artworkUrl100":"https://example.com/image2.jpg", "trackCount":13}, 
                    {"wrapperType":"track", "kind":"song", "artistId":445782702, "collectionId":1525233607, "trackId":1525233615, "artistName":"Bonez MC", "collectionName":"Tilidin Weg - Single", "trackName":"Tilidin Weg", "artworkUrl100":"https://example.com/image4.jpg", "trackCount":1}, 
                    {"wrapperType":"collection", "collectionType":"Album", "artistId":445782702, "collectionId":1712105785, "artistName":"Bonez MC", "collectionName":"LOVELINE EP 💔", "artworkUrl100":"https://example.com/image1.jpg", "trackCount":8}]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
            )
        }

        mockkObject(ApiClient)
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val albums = appleMusicService.searchAlbums("Bonez MC", 10, includeTracks = true)

        assertEquals(4, albums.size)
        
        val titles = albums.map { it.title }.toSet()
        assertTrue(titles.contains("LOVELINE EP 💔"))
        assertTrue(titles.contains("Hollywood"))
        assertTrue(titles.contains("Hollywood Uncut"))
        assertTrue(titles.contains("Tilidin Weg - Single"))

        val hollywoodAlbum = albums.first { it.title == "Hollywood" }
        assertTrue(hollywoodAlbum.additionalTitles.contains("Papa ist in Hollywood"))
        assertTrue(hollywoodAlbum.additionalTitles.contains("Tilidin Weg"))
    }

    @Test
    fun `getTrackByIsrc should return track from catalog API when token is provided`() = runBlocking {
        val isrc = "USUM71900764"

        val tokenField = appleMusicService.javaClass.getDeclaredField("appleMusicToken")
        tokenField.isAccessible = true
        tokenField.set(appleMusicService, "mock-token")
        val expirationField = appleMusicService.javaClass.getDeclaredField("tokenExpiration")
        expirationField.isAccessible = true
        expirationField.set(appleMusicService, System.currentTimeMillis() + 100000)

        mockEngine = MockEngine { request ->
            if (request.url.toString().contains("api.music.apple.com")) {
                assertEquals("Bearer mock-token", request.headers[HttpHeaders.Authorization])
                assertEquals(isrc, request.url.parameters["filter[isrc]"])
                respond(
                    content = """
                        {
                          "data": [
                            {
                              "id": "1471758375",
                              "type": "songs",
                              "attributes": {
                                "name": "Lover",
                                "artistName": "Taylor Swift",
                                "durationInMillis": 221000,
                                "artwork": {
                                  "url": "https://example.com/image-{w}x{h}-{f}.jpg"
                                }
                              }
                            }
                          ]
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val track = appleMusicService.getTrackByIsrc(isrc)

        assertEquals("1471758375", track?.id)
        assertEquals("Lover", track?.title)
        assertEquals("https://example.com/image-600x600-jpg.jpg", track?.images?.firstOrNull()?.url)
    }

    @Test
    fun `getTrackByIsrc should return track for valid ISRC using iTunes fallback`() = runBlocking {
        every { environment.config.propertyOrNull("appleMusic.teamId") } returns null
        every { environment.config.propertyOrNull("appleMusic.keyId") } returns null
        every { environment.config.propertyOrNull("appleMusic.p8Path") } returns null

        val isrc = "USUM71900764"
        mockEngine = MockEngine { request ->
            assertEquals("/lookup", request.url.encodedPath)
            assertEquals(isrc, request.url.parameters["isrc"])
            respond(
                content = """
                    {
                      "resultCount": 1,
                      "results": [
                        {
                          "wrapperType": "track",
                          "kind": "song",
                          "collectionId": 1471758375,
                          "artistName": "Taylor Swift",
                          "collectionName": "Lover",
                          "trackName": "Lover",
                          "artworkUrl100": "https://example.com/100x100bb.jpg",
                          "trackCount": 18,
                          "trackTimeMillis": 221000,
                          "primaryIsrc": "$isrc"
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
            )
        }
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val track = appleMusicService.getTrackByIsrc(isrc)

        assertEquals("1471758375", track?.id)
        assertEquals("Lover", track?.title)
        assertEquals(isrc, track?.isrc)
    }

    @Test
    fun `maxArtworkUrl rewrites catalog templates and thumbnails to the native-max request`() {
        val base = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/cc/xyz"

        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/{w}x{h}bb.jpg"))
        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/{w}x{h}{c}.{f}"))
        assertEquals("$base/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/1200x630bb.jpg"))
        assertEquals("$base/source/10000x0w-999.jpg", AppleMusicService.maxArtworkUrl("$base/source/165x165bb.jpg"))

        assertEquals(
            "https://x/image/thumb/Podcasts123/v4/xx/mza_348.jpg/10000x0w-999.jpg",
            AppleMusicService.maxArtworkUrl("https://x/image/thumb/Podcasts123/v4/xx/mza_348.jpg/100x100bb.jpg")
        )
    }

    @Test
    fun `maxArtworkUrl is idempotent on an already-maximised url`() {
        val maxed = "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/bb/cc/xyz/10000x0w-999.jpg"
        assertEquals(maxed, AppleMusicService.maxArtworkUrl(maxed))
    }

    @Test
    fun `parseReleaseDate accepts catalog date-only and iTunes datetime, and rejects garbage`() {
        assertEquals(LocalDate.of(2019, 6, 21), AppleMusicService.parseReleaseDate("2019-06-21"))
        assertEquals(LocalDate.of(2019, 6, 21), AppleMusicService.parseReleaseDate("2019-06-21T12:00:00Z"))
        assertNull(AppleMusicService.parseReleaseDate(null))
        assertNull(AppleMusicService.parseReleaseDate("not-a-date"))
    }

    @Test
    fun `getAlbumsByIds maximises artwork and parses date via the iTunes fallback`() = runBlocking {
        every { environment.config.propertyOrNull("appleMusic.teamId") } returns null
        every { environment.config.propertyOrNull("appleMusic.keyId") } returns null
        every { environment.config.propertyOrNull("appleMusic.p8Path") } returns null

        mockEngine = MockEngine { request ->
            assertEquals("/lookup", request.url.encodedPath)
            assertEquals("67890", request.url.parameters["id"])
            respond(
                content = """
                    {
                      "resultCount": 2,
                      "results": [
                        {"wrapperType":"collection","collectionId":67890,"artistName":"Test Artist","collectionName":"Test Album","artworkUrl100":"https://example.com/100x100bb.jpg","trackCount":12,"releaseDate":"2019-06-21T12:00:00Z"},
                        {"wrapperType":"track","collectionId":67890,"trackId":1,"trackName":"Song","artworkUrl100":"https://example.com/100x100bb.jpg","trackCount":12}
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/javascript; charset=utf-8")
            )
        }
        every { ApiClient.instance } returns HttpClient(mockEngine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }

        val albums = appleMusicService.getAlbumsByIds(
            IMetadataService.MetadataType.appleMusic,
            listOf("appleMusic:67890")
        )

        assertEquals(1, albums.size)
        assertEquals("appleMusic:67890", albums[0].id)
        assertEquals("Test Album", albums[0].title)
        assertEquals(12, albums[0].trackCount)
        assertEquals(LocalDate.of(2019, 6, 21), albums[0].releaseDate)
        assertEquals("https://example.com/10000x0w-999.jpg", albums[0].images[0].url)
    }
}
