package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.plugins.RedisCacheProvider
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.testing.FakeCredentialProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
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
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import java.util.concurrent.atomic.AtomicInteger

class TidalServiceRetryTest : KoinTest {

    private lateinit var environment: ApplicationEnvironment
    private lateinit var tidalService: TidalService
    private val apiRequests = AtomicInteger()

    private val albumsBody = """
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
    """.trimIndent()

    @BeforeEach
    fun setup() {
        apiRequests.set(0)
        environment = mockk()
        val config = mockk<ApplicationConfig>()
        every { environment.config } returns config

        val redisConfig = mockk<RedisCacheProvider.Config>()
        every { redisConfig.host } returns "none"

        startKoin {
            modules(module {
                single { redisConfig }
                single { HttpClientQueueService() }
                single<CredentialProvider> {
                    FakeCredentialProvider(
                        ResolvedCredential.AccessToken(
                            CredentialNames.TIDAL_API,
                            "test-token",
                            "Bearer",
                            null
                        )
                    )
                }
            })
        }

        mockkObject(ApiClient)
        tidalService = TidalService(environment)
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    private fun useEngine(handler: MockRequestHandleScope.(HttpRequestData, Int) -> HttpResponseData) {
        val engine = MockEngine { request ->
            if (request.url.encodedPath == "/v1/oauth2/token") {
                respond(
                    content = """{"access_token": "test-token", "token_type": "Bearer", "expires_in": 3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            } else {
                handler(request, apiRequests.incrementAndGet())
            }
        }
        every { ApiClient.instance } returns HttpClient(engine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
    }

    private fun MockRequestHandleScope.respondRetryLater(status: HttpStatusCode): HttpResponseData = respond(
        content = "",
        status = status,
        headers = headersOf(HttpHeaders.RetryAfter, "0")
    )

    @Test
    fun `a not found response returns the empty result without retrying`() = runBlocking {
        useEngine { _, _ -> respondError(HttpStatusCode.NotFound) }

        assertEquals(emptyList<IMetadataService.Album>(), tidalService.getAlbumsByIds(listOf("missing-album")))
        assertEquals(1, apiRequests.get())
    }

    @Test
    fun `a bad request returns an empty search without retrying`() = runBlocking {
        useEngine { _, _ -> respondError(HttpStatusCode.BadRequest) }

        assertEquals(emptyList<IMetadataService.Track>(), tidalService.search("test", 10))
        assertEquals(1, apiRequests.get())
    }

    @Test
    fun `server errors are retried a bounded number of times`() = runBlocking {
        useEngine { _, _ -> respondRetryLater(HttpStatusCode.ServiceUnavailable) }

        assertNull(tidalService.getAlbumByBarcode("123"))
        assertEquals(6, apiRequests.get())
    }

    @Test
    fun `rate limited requests are retried until they succeed`() = runBlocking {
        useEngine { request, count ->
            if (count <= 4) respondRetryLater(HttpStatusCode.TooManyRequests)
            else {
                assertEquals("123", request.url.parameters["filter[barcodeId]"])
                respond(
                    content = albumsBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/vnd.api+json")
                )
            }
        }

        val album = tidalService.getAlbumByBarcode("123")

        assertNotNull(album)
        assertEquals("album-1", album?.id)
        assertEquals("Album 1", album?.title)
        assertEquals(listOf("Artist 1"), album?.artists)
        assertEquals(5, apiRequests.get())
    }
}
