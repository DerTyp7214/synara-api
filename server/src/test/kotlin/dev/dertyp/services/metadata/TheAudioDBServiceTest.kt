package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.testing.FakeCredentialProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.UUID

class TheAudioDBServiceTest : KoinTest {

    private lateinit var environment: ApplicationEnvironment
    private lateinit var service: TheAudioDBService
    private lateinit var mockEngine: MockEngine
    private lateinit var credentialProvider: FakeCredentialProvider
    private val requestedUrls = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        environment = mockk()
        val config = mockk<ApplicationConfig>()
        every { environment.config } returns config
        credentialProvider = FakeCredentialProvider(ResolvedCredential.ApiKey(CredentialNames.THEAUDIODB_API, "test-api-key"))
        startKoin { modules(module { single<CredentialProvider> { credentialProvider } }) }
        requestedUrls.clear()

        mockEngine = MockEngine { request ->
            requestedUrls += request.url.toString()
            when {
                request.url.encodedPath.contains("search.php") -> {
                    respond(
                        content = """
                            {
                              "artists": [
                                {
                                  "idArtist": "111233",
                                  "strArtist": "Coldplay",
                                  "strBiography": "Bio here",
                                  "strGenre": "Alternative Rock",
                                  "strStyle": "Rock",
                                  "strArtistThumb": "https://example.com/thumb.jpg"
                                }
                              ]
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                request.url.encodedPath.contains("artist-mb.php") -> {
                    respond(
                        content = """
                            {
                              "artists": [
                                {
                                  "idArtist": "111233",
                                  "strArtist": "Coldplay",
                                  "strBiography": "Bio here",
                                  "strGenre": "Alternative Rock",
                                  "strStyle": "Rock",
                                  "strArtistThumb": "https://example.com/thumb.jpg"
                                }
                              ]
                            }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
                request.url.encodedPath.contains("album-mb.php") -> {
                    respond(
                        content = """
                            {
                              "album": [
                                {
                                  "idAlbum": "2115888",
                                  "idArtist": "111233",
                                  "strAlbum": "Parachutes",
                                  "strGenre": "Alternative",
                                  "strStyle": "Indie",
                                  "strAlbumThumb": "https://example.com/album.jpg"
                                }
                              ]
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
        val mockQueueService = mockk<HttpClientQueueService>()
        every { ApiClient.queueInstance } returns mockQueueService
        
        every { runBlocking { mockQueueService.enqueue(any(), any(), any()) } } answers {
            val url = firstArg<String>()
            runBlocking { mockHttpClient.get(url) }
        }

        service = TheAudioDBService(environment)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `the api key comes from the credential provider`() = runBlocking {
        service.searchArtists("Coldplay", 1)

        assertTrue(requestedUrls.single().startsWith("https://www.theaudiodb.com/api/v1/json/test-api-key/search.php"))
        assertTrue(service.supported())
    }

    @Test
    fun `an unavailable credential sends no request and reports unsupported`() = runBlocking {
        credentialProvider.remove(CredentialNames.THEAUDIODB_API)

        assertTrue(service.searchArtists("Coldplay", 1).isEmpty())
        assertTrue(requestedUrls.isEmpty())
        assertFalse(service.supported())
        assertTrue(service.supportedFeatures.isEmpty())
    }

    @Test
    fun `searchArtists should return artist with split metadata`() = runBlocking {
        val artists = service.searchArtists("Coldplay", 1)

        assertEquals(1, artists.size)
        val artist = artists[0]
        assertEquals("111233", artist.id)
        assertEquals("Coldplay", artist.name)
        assertEquals("Bio here", artist.biography)
        assertEquals(listOf("Rock"), artist.styles)
        assertEquals(listOf("Alternative Rock"), artist.genres)
        assertEquals(1, artist.images.size)
        assertEquals("https://example.com/thumb.jpg", artist.images[0].url)
        assertEquals(1000, artist.images[0].width)
    }

    @Test
    fun `getArtistByMbId should return artist`() = runBlocking {
        val artist = service.getArtistByMbId(UUID.randomUUID())

        assertNotNull(artist)
        assertEquals("Coldplay", artist?.name)
        assertEquals(listOf("Alternative Rock"), artist?.genres)
    }

    @Test
    fun `getAlbumByMbId should return album`() = runBlocking {
        val album = service.getAlbumByMbId(UUID.randomUUID())

        assertNotNull(album)
        assertEquals("Parachutes", album?.title)
        assertEquals(listOf("Alternative", "Indie"), album?.genres)
    }
}
