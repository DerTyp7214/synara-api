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

class RpcLinkResolverServiceTest {
    private data class ResolveCall(val key: String, val value: String)

    private lateinit var credentialProvider: FakeCredentialProvider
    private lateinit var queue: HttpClientQueueService
    private lateinit var service: RpcLinkResolverService
    private val resolveCalls = mutableListOf<ResolveCall>()
    private val paths = mutableListOf<String>()

    @BeforeEach
    fun setup() {
        credentialProvider = FakeCredentialProvider()
        startKoin { modules(module { single<CredentialProvider> { credentialProvider } }) }
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            val body = if (request.url.encodedPath == "/resolve") {
                val (key, value) = listOf("isrc", "upc", "url")
                    .firstNotNullOf { key -> request.url.parameters[key]?.let { key to it } }
                resolveCalls += ResolveCall(key, value)
                if (value.contains("empty")) {
                    """{"links": {}}"""
                } else {
                    """{"links": {"tidal": "https://tidal.com/track/$key"}}"""
                }
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
        service = RpcLinkResolverService(LinkResolverService())
    }

    @AfterEach
    fun tearDown() {
        runBlocking { queue.stopService() }
        stopKoin()
        unmockkAll()
    }

    private fun enable() {
        credentialProvider.put(ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "resolver-key"))
    }

    @Test
    fun `isrc hit returns links without upc or url requests`() = runBlocking {
        enable()

        val links = service.resolvePlatformLinks(
            urls = listOf("https://tidal.com/track/9"),
            isrc = "USUM71900764",
            upc = "00602577000000"
        )

        assertEquals(listOf("https://tidal.com/track/isrc"), links)
        assertEquals(listOf(ResolveCall("isrc", "USUM71900764")), resolveCalls)
        assertEquals(listOf("/resolve"), paths)
    }

    @Test
    fun `falls back to the upc when the isrc resolves nothing`() = runBlocking {
        enable()

        val links = service.resolvePlatformLinks(
            urls = listOf("https://tidal.com/track/9"),
            isrc = "empty-isrc",
            upc = "00602577000000"
        )

        assertEquals(listOf("https://tidal.com/track/upc"), links)
        assertEquals(
            listOf(ResolveCall("isrc", "empty-isrc"), ResolveCall("upc", "00602577000000")),
            resolveCalls
        )
    }

    @Test
    fun `skips unsupported seed urls and resolves a supported one`() = runBlocking {
        enable()

        val links = service.resolvePlatformLinks(
            urls = listOf(
                "https://musicbrainz.org/recording/0b0b0b0b-0000-0000-0000-000000000000",
                "https://tidal.com/track/5"
            )
        )

        assertEquals(listOf("https://tidal.com/track/url"), links)
        assertEquals(listOf(ResolveCall("url", "https://tidal.com/track/5")), resolveCalls)
    }

    @Test
    fun `blank isrc and upc are ignored`() = runBlocking {
        enable()

        val links = service.resolvePlatformLinks(
            urls = listOf("  https://tidal.com/track/5  ", " "),
            isrc = "   ",
            upc = ""
        )

        assertEquals(listOf("https://tidal.com/track/url"), links)
        assertEquals(listOf(ResolveCall("url", "https://tidal.com/track/5")), resolveCalls)
    }

    @Test
    fun `tries at most twenty distinct urls`() = runBlocking {
        enable()
        val urls = (1..30).map { "https://tidal.com/track/empty-$it" }

        val links = service.resolvePlatformLinks(urls = listOf(urls.first()) + urls)

        assertTrue(links.isEmpty())
        assertEquals(urls.take(20).map { ResolveCall("url", it) }, resolveCalls)
    }

    @Test
    fun `returns nothing and sends no request without a credential`() = runBlocking {
        val links = service.resolvePlatformLinks(
            urls = listOf("https://tidal.com/track/5"),
            isrc = "USUM71900764",
            upc = "00602577000000"
        )

        assertTrue(links.isEmpty())
        assertTrue(paths.isEmpty())
    }
}
