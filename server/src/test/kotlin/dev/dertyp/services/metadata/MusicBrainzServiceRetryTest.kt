package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
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
import java.util.UUID

class MusicBrainzServiceRetryTest {

    private lateinit var service: MusicBrainzService
    private var queueService: HttpClientQueueService? = null
    private val requests = mutableListOf<Url>()

    @BeforeEach
    fun setup() {
        requests.clear()
        mockkObject(ApiClient)
        service = MusicBrainzService()
    }

    @AfterEach
    fun tearDown() {
        runBlocking { queueService?.stopService() }
        queueService = null
        unmockkAll()
    }

    private suspend fun useEngine(handler: MockRequestHandler) {
        val engine = MockEngine { request ->
            requests += request.url
            handler(this, request)
        }
        every { ApiClient.instance } returns HttpClient(engine) {
            install(ContentNegotiation) { json(ApplicationScope.json) }
        }
        val queue = HttpClientQueueService()
        queue.startService()
        queueService = queue
        every { ApiClient.queueInstance } returns queue
    }

    @Test
    fun `a not found lookup returns null without retrying`() = runBlocking {
        useEngine {
            respond(
                content = """{"error": "Not Found"}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        assertNull(service.fetchArtistById(UUID.randomUUID()))
        assertEquals(1, requests.size)
    }

    @Test
    fun `a client error returns an empty list without retrying`() = runBlocking {
        useEngine { respondError(HttpStatusCode.BadRequest) }

        assertEquals(emptyList<Any>(), service.fetchReleaseGroups(UUID.randomUUID()))
        assertEquals(1, requests.size)
    }

    @Test
    fun `service unavailable is retried and then gives up`() = runBlocking {
        useEngine { respondError(HttpStatusCode.ServiceUnavailable) }

        assertNull(service.fetchReleaseById(UUID.randomUUID()))
        assertEquals(3, requests.size)
    }

    @Test
    fun `service unavailable is retried until the lookup succeeds`() = runBlocking {
        val artistId = UUID.randomUUID()
        useEngine {
            if (requests.size == 1) respondError(HttpStatusCode.ServiceUnavailable)
            else respond(
                content = """{"id": "$artistId", "name": "Artist"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val artist = service.fetchArtistById(artistId)

        assertEquals(artistId, artist?.id)
        assertEquals("Artist", artist?.name)
        assertEquals(2, requests.size)
    }
}
