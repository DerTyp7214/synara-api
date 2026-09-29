package dev.dertyp.services.metadata

import dev.dertyp.ApiClient
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.HttpClientQueueService
import dev.dertyp.data.Album
import dev.dertyp.data.Artist
import dev.dertyp.data.UserSong
import dev.dertyp.plugins.PluginSettings
import dev.dertyp.services.credentials.CredentialCipher
import dev.dertyp.services.credentials.CredentialOrigin
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.UiRegistry
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
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class AcoustIdServiceTest {

    private var queueService: HttpClientQueueService? = null
    private val requests = mutableListOf<Url>()
    private var storedKey: String? = null
    private lateinit var config: MapApplicationConfig
    private lateinit var credentials: AcoustIdCredentialSource
    private lateinit var service: AcoustIdService

    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))

    private val fingerprint = Fingerprint(duration = 215, fingerprint = "AQADtEmUaEkSRZEGAAAA+/=")

    @BeforeEach
    fun setup() {
        requests.clear()
        storedKey = null
        mockkObject(ApiClient)
        val settings = mockk<PluginSettings>()
        coEvery { settings.getAll() } answers { storedKey?.let { mapOf(ACOUSTID_API_KEY_SETTING to it) } ?: emptyMap() }
        val settingsService = mockk<PluginSettingsService>()
        every { settingsService.forPlugin(UiRegistry.SERVER_SOURCE) } returns settings
        config = MapApplicationConfig("acoustid.apiKey" to ENV_KEY)
        credentials = AcoustIdCredentialSource(settingsService, config, cipher)
        service = AcoustIdService(mockk(relaxed = true), credentials)
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

    private fun song(
        title: String = "Midnight City",
        artist: String = "M83",
        album: String? = "Hurry Up, We're Dreaming",
        durationMs: Long = 243_000,
    ) = UserSong(
        id = UUID.randomUUID(),
        title = title,
        artists = listOf(Artist(id = UUID.randomUUID(), name = artist, isGroup = false)),
        album = album?.let { Album(id = UUID.randomUUID(), name = it, artists = emptyList(), releaseDate = null, totalDuration = 0) },
        duration = durationMs,
        explicit = false,
        path = "",
    )

    private fun recording(
        id: UUID,
        title: String,
        artist: String,
        duration: Int = 243,
        releaseGroup: String = "Hurry Up, We're Dreaming",
    ) = """
        {"id": "$id", "title": "$title", "duration": $duration,
         "artists": [{"id": "${UUID.randomUUID()}", "name": "$artist"}],
         "releasegroups": [{"id": "${UUID.randomUUID()}", "title": "$releaseGroup", "type": "Album"}]}
    """.trimIndent()

    private fun response(vararg results: Pair<Double, List<String>>) = """
        {"status": "ok", "results": [${
            results.joinToString(",") { (score, recordings) ->
                """{"id": "${UUID.randomUUID()}", "score": $score, "recordings": [${recordings.joinToString(",")}]}"""
            }
        }]}
    """.trimIndent()

    private suspend fun resolve(song: UserSong, body: String): AcoustIdMatch? {
        useEngine { respondJson(body) }
        val lookup = service.lookup(fingerprint) ?: return null
        return AcoustIdService.selectRecording(song, lookup.results)
    }

    @Test
    fun `a single recording above the threshold is taken`() = runBlocking {
        val recordingId = UUID.randomUUID()
        val match = resolve(song(), response(0.97 to listOf(recording(recordingId, "Midnight City", "M83"))))

        assertEquals(recordingId, match?.recordingId)
        assertEquals(0.97, match?.score)

        val request = requests.single()
        assertEquals("api.acoustid.org", request.host)
        assertEquals("/v2/lookup", request.encodedPath)
        assertEquals(ENV_KEY, request.parameters["client"])
        assertEquals("215", request.parameters["duration"])
        assertEquals(fingerprint.fingerprint, request.parameters["fingerprint"])
        assertEquals("recordings releasegroups", request.parameters["meta"])
    }

    @Test
    fun `the same recording in several results counts as one`() = runBlocking {
        val recordingId = UUID.randomUUID()
        val match = resolve(
            song(title = "Something Else", artist = "Nobody"),
            response(
                0.91 to listOf(recording(recordingId, "Midnight City", "M83")),
                0.95 to listOf(recording(recordingId, "Midnight City", "M83")),
            )
        )

        assertEquals(recordingId, match?.recordingId)
        assertEquals(0.95, match?.score)
    }

    @Test
    fun `a low score is rejected`() = runBlocking {
        val match = resolve(song(), response(0.5 to listOf(recording(UUID.randomUUID(), "Midnight City", "M83"))))

        assertNull(match)
    }

    @Test
    fun `several recordings are picked by title and artist`() = runBlocking {
        val wanted = UUID.randomUUID()
        val match = resolve(
            song(title = "Midnight City (feat. Someone)", artist = "m83"),
            response(
                0.93 to listOf(
                    recording(UUID.randomUUID(), "Midnight City", "Tribute Band"),
                    recording(wanted, "Midnight City", "M83"),
                    recording(UUID.randomUUID(), "Outro", "M83"),
                )
            )
        )

        assertEquals(wanted, match?.recordingId)
    }

    @Test
    fun `release group and duration break ties after title and artist`() = runBlocking {
        val byAlbum = UUID.randomUUID()
        val albumMatch = resolve(
            song(),
            response(
                0.9 to listOf(
                    recording(UUID.randomUUID(), "Midnight City", "M83", releaseGroup = "Midnight City"),
                    recording(byAlbum, "Midnight City", "M83"),
                )
            )
        )
        assertEquals(byAlbum, albumMatch?.recordingId)

        val byDuration = UUID.randomUUID()
        val durationMatch = resolve(
            song(),
            response(
                0.9 to listOf(
                    recording(UUID.randomUUID(), "Midnight City", "M83", duration = 260),
                    recording(byDuration, "Midnight City", "M83", duration = 241),
                )
            )
        )
        assertEquals(byDuration, durationMatch?.recordingId)
    }

    @Test
    fun `an ambiguous result returns null`() = runBlocking {
        val match = resolve(
            song(),
            response(
                0.9 to listOf(
                    recording(UUID.randomUUID(), "Midnight City", "M83"),
                    recording(UUID.randomUUID(), "Midnight City", "M83"),
                )
            )
        )

        assertNull(match)
    }

    @Test
    fun `an error response yields no lookup`() = runBlocking {
        useEngine { respondJson("""{"status": "error", "error": {"code": 4, "message": "invalid API key"}}""") }
        assertNull(service.lookup(fingerprint))

        useEngine { respondError(HttpStatusCode.BadRequest) }
        assertNull(service.lookup(fingerprint))
    }

    @Test
    fun `the stored key wins over the environment`() = runBlocking {
        config.put("acoustid.apiKey", "   ")
        assertNull(credentials.current())
        assertEquals(CredentialOrigin.NONE, credentials.origin())

        config.put("acoustid.apiKey", "envKey")
        assertEquals("envKey", credentials.current())
        assertEquals(CredentialOrigin.ENVIRONMENT, credentials.origin())

        storedKey = " "
        assertEquals("envKey", credentials.current())
        assertEquals(CredentialOrigin.ENVIRONMENT, credentials.origin())

        storedKey = "plainKey"
        assertEquals("envKey", credentials.current())
        assertEquals(CredentialOrigin.UNREADABLE, credentials.origin())

        storedKey = cipher.encrypt(ACOUSTID_API_KEY_SETTING, "storedKey")
        assertEquals("storedKey", credentials.current())
        assertEquals(CredentialOrigin.STORED, credentials.origin())

        useEngine { respondJson(response()) }
        service.lookup(fingerprint)
        assertEquals("storedKey", requests.single().parameters["client"])
    }

    @Test
    fun `no key skips the lookup`() = runBlocking {
        config.put("acoustid.apiKey", "")
        useEngine { respondJson(response()) }
        assertNull(service.lookup(fingerprint))
        assertTrue(requests.isEmpty())
    }

    private companion object {
        const val ENV_KEY = "envTestKey"
    }
}
