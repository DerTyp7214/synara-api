package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
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

class MusicBrainzServiceTest {

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

    private fun MockRequestHandleScope.respondJson(content: String): HttpResponseData = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json")
    )

    @Test
    fun `fetchReleasesByBarcode queries every barcode variant`() = runBlocking {
        val releaseId = UUID.randomUUID()
        val groupId = UUID.randomUUID()

        useEngine { request ->
            when (request.url.encodedPath) {
                "/ws/2/release" -> respondJson(
                    """
                    {
                      "releases": [
                        {
                          "id": "$releaseId",
                          "title": "Padded Album",
                          "barcode": "602445790000",
                          "release-group": {"id": "$groupId", "title": "Padded Album"}
                        }
                      ]
                    }
                    """.trimIndent()
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val releases = service.fetchReleasesByBarcode("00602445790000")

        assertNotNull(releases)
        assertEquals(listOf(releaseId), releases!!.map { it.id })

        val query = requests.single().parameters["query"]
        assertNotNull(query)
        assertTrue(query!!.startsWith("barcode:("))
        assertTrue(query.contains("602445790000"))
        assertTrue(query.contains("00602445790000"))
    }

    @Test
    fun `fetchReleasesByBarcode returns an empty list for an unusable barcode`() = runBlocking {
        useEngine { respondError(HttpStatusCode.NotFound) }

        assertEquals(emptyList<Any>(), service.fetchReleasesByBarcode("BARCODE"))
        assertEquals(emptyList<Any>(), service.fetchReleasesByBarcode("1234567"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `fetchReleasesByUrls sends every resource and resolves the related releases`() = runBlocking {
        val releaseId = UUID.randomUUID()
        val groupId = UUID.randomUUID()

        useEngine { request ->
            when (request.url.encodedPath) {
                "/ws/2/url" -> respondJson(
                    """
                    {
                      "urls": [
                        {
                          "id": "${UUID.randomUUID()}",
                          "resource": "https://music.apple.com/us/album/1",
                          "relations": [
                            {
                              "type": "free streaming",
                              "target-type": "release",
                              "release": {"id": "$releaseId", "title": "Linked Album"}
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent()
                )
                "/ws/2/release/$releaseId" -> respondJson(
                    """
                    {
                      "id": "$releaseId",
                      "title": "Linked Album",
                      "release-group": {"id": "$groupId", "title": "Linked Album"}
                    }
                    """.trimIndent()
                )
                else -> respondError(HttpStatusCode.NotFound)
            }
        }

        val releases = service.fetchReleasesByUrls(
            listOf("https://music.apple.com/us/album/1", "https://tidal.com/album/42")
        )

        assertNotNull(releases)
        assertEquals(listOf(releaseId), releases!!.map { it.id })
        assertEquals(groupId, releases.single().releaseGroup?.id)

        val lookup = requests.first()
        assertEquals("/ws/2/url", lookup.encodedPath)
        assertEquals(
            listOf("https://music.apple.com/us/album/1", "https://tidal.com/album/42"),
            lookup.parameters.getAll("resource")
        )
        assertEquals("release-rels", lookup.parameters["inc"])
        assertTrue(requests.any { it.encodedPath == "/ws/2/release/$releaseId" })
    }

    @Test
    fun `fetchReleasesByUrls returns an empty list without urls and for an unknown url`() = runBlocking {
        useEngine {
            respondJson("""{"error": "Not Found", "help": "For usage, please see: https://musicbrainz.org/development/mmd"}""")
        }

        assertEquals(emptyList<Any>(), service.fetchReleasesByUrls(emptyList()))
        assertTrue(requests.isEmpty())

        assertEquals(emptyList<Any>(), service.fetchReleasesByUrls(listOf("https://music.apple.com/us/album/1")))
        assertEquals(1, requests.size)
    }
}
