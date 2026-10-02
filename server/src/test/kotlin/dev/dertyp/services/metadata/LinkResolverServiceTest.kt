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

class LinkResolverServiceTest {
    private lateinit var credentialProvider: FakeCredentialProvider
    private lateinit var queue: HttpClientQueueService
    private lateinit var service: LinkResolverService
    private val apiKeys = mutableListOf<String?>()

    @BeforeEach
    fun setup() {
        credentialProvider = FakeCredentialProvider()
        startKoin { modules(module { single<CredentialProvider> { credentialProvider } }) }
        val engine = MockEngine { request ->
            apiKeys += request.headers["X-API-Key"]
            val body = if (request.url.encodedPath == "/resolve") {
                """{"links": {"tidal": "https://tidal.com/track/1"}}"""
            } else {
                """{"urlHosts": ["tidal.com"]}"""
            }
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        mockkObject(ApiClient)
        every { ApiClient.instance } returns HttpClient(engine) { install(ContentNegotiation) { json(ApplicationScope.json) } }
        queue = HttpClientQueueService()
        runBlocking { queue.startService() }
        every { ApiClient.queueInstance } returns queue
        service = LinkResolverService()
    }

    @AfterEach
    fun tearDown() {
        runBlocking { queue.stopService() }
        stopKoin()
        unmockkAll()
    }

    @Test
    fun `disabled without a credential and sends no request`() = runBlocking {
        assertFalse(service.enabled)
        assertTrue(service.batchResolve(emptyList(), isrc = "USUM71900764").isEmpty())
        assertTrue(apiKeys.isEmpty())
    }

    @Test
    fun `resolves with the api key from the credential provider`() = runBlocking {
        credentialProvider.put(ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "resolver-key"))

        assertTrue(service.enabled)
        val links = service.batchResolve(emptyList(), isrc = "USUM71900764")

        assertEquals(listOf("https://tidal.com/track/1"), links)
        assertEquals(listOf<String?>("resolver-key"), apiKeys)
    }
}
