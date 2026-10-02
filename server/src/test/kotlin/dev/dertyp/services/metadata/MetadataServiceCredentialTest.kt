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
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class MetadataServiceCredentialTest {
    private lateinit var credentialProvider: FakeCredentialProvider
    private lateinit var queue: HttpClientQueueService
    private lateinit var spotify: SpotifyService
    private val authorizations = mutableListOf<String?>()

    @BeforeEach
    fun setup() {
        credentialProvider = FakeCredentialProvider()
        startKoin { modules(module { single<CredentialProvider> { credentialProvider } }) }
        val engine = MockEngine { request ->
            authorizations += request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"artists": {"href": "h", "limit": 1, "next": null, "offset": 0, "previous": null, "total": 0, "items": []}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        mockkObject(ApiClient)
        every { ApiClient.instance } returns HttpClient(engine) { install(ContentNegotiation) { json(ApplicationScope.json) } }
        queue = HttpClientQueueService()
        runBlocking { queue.startService() }
        every { ApiClient.queueInstance } returns queue
        spotify = SpotifyService(mockk<ApplicationEnvironment>(relaxed = true))
    }

    @AfterEach
    fun tearDown() {
        runBlocking { queue.stopService() }
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `the access token is resolved through the credential provider`() = runBlocking {
        credentialProvider.put(ResolvedCredential.AccessToken(CredentialNames.SPOTIFY_API, "provider-token", "Bearer", null))

        spotify.searchArtists("test", 1)

        assertEquals(listOf<String?>("Bearer provider-token"), authorizations)
        assertEquals(listOf(CredentialNames.SPOTIFY_API), credentialProvider.resolved)
    }

    @Test
    fun `an unavailable credential reports the provider as unsupported and sends no request`() = runBlocking {
        assertFalse(spotify.supported())
        assertTrue(spotify.supportedFeatures.isEmpty())
        assertTrue(spotify.getSupportedFeatures(IMetadataService.MetadataType.spotify).isEmpty())

        assertTrue(spotify.searchArtists("test", 1).isEmpty())
        assertTrue(authorizations.isEmpty())
    }

    @Test
    fun `supported features follow credential availability`() {
        assertTrue(spotify.supportedFeatures.isEmpty())

        credentialProvider.put(ResolvedCredential.AccessToken(CredentialNames.SPOTIFY_API, "provider-token", "Bearer", null))
        assertTrue(spotify.supported())
        assertTrue(IMetadataService.Feature.GET_TRACK_BY_ISRC in spotify.supportedFeatures)

        credentialProvider.remove(CredentialNames.SPOTIFY_API)
        assertTrue(spotify.supportedFeatures.isEmpty())
    }

    @Test
    fun `providers without a credential are always supported`() {
        val deezer = DeezerService(mockk<ApplicationEnvironment>(relaxed = true))

        assertTrue(deezer.supported())
        assertTrue(deezer.supportedFeatures.isNotEmpty())
        assertTrue(credentialProvider.resolved.isEmpty())
    }
}
