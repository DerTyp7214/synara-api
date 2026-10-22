package dev.dertyp.services.lyrics.providers

import dev.dertyp.ApiClient
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientPriority
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.services.LrcLibService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.CopyOnWriteArrayList

abstract class ProviderTestSupport {
    protected val requests = CopyOnWriteArrayList<HttpRequestData>()
    protected var handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        respond("", HttpStatusCode.NotFound)
    }
    protected open val configuredProviders: String? = null
    private lateinit var queue: HttpClientQueueService

    @BeforeEach
    fun setUpProviders() {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
            install(HttpTimeout)
        }
        mockkObject(ApiClient)
        every { ApiClient.instance } returns client
        queue = HttpClientQueueService()
        runBlocking { queue.startService() }
        val settings = listOfNotNull(configuredProviders?.let { "lyrics.providers" to it })
        startKoin {
            modules(
                lyricsProvidersModule,
                module {
                    single { ServerConfig(MapApplicationConfig(*settings.toTypedArray())) }
                    single { queue }
                    single { LrcLibService() }
                },
            )
        }
    }

    @AfterEach
    fun tearDownProviders() {
        runBlocking { queue.stopService() }
        stopKoin()
        unmockkAll()
    }

    protected fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    protected fun fetch(provider: LyricsProvider, req: LyricsRequest = REQUEST): ProviderResult =
        runBlocking { provider.fetch(req, HttpClientPriority.HIGH) }

    protected inline fun <reified T : Any> koin(): T = GlobalContext.get().get()

    protected fun HttpRequestData.param(name: String): String? = url.parameters[name]

    protected fun HttpRequestData.bodyText(): String = (body as TextContent).text

    companion object {
        val REQUEST = LyricsRequest(title = "Paper Lanterns", artist = "Mira Vale", album = "Night Market", durationSec = 214)
        val OVERSIZED = "x".repeat(LyricsHttp.MAX_BODY_BYTES + 1)
    }
}
