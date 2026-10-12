package dev.dertyp.routing.rest

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.*
import dev.dertyp.rpc.rest.client.*
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.ICollectionService
import dev.dertyp.services.IHueService
import dev.dertyp.services.IImageService
import dev.dertyp.services.IPodcastService
import dev.dertyp.services.`import`.GamdlCredentials
import dev.dertyp.services.`import`.IImportService
import dev.dertyp.services.`import`.ImportBackend
import dev.dertyp.services.`import`.ImporterCredentials
import dev.dertyp.services.metadata.IMetadataService
import io.github.smiley4.ktoropenapi.OpenApi
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.routing
import io.ktor.server.sse.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlin.test.assertFailsWith

class RestClientRoundTripTest {
    private val image = mockk<IImageService>()
    private val metadata = mockk<IMetadataService>()
    private val podcast = mockk<IPodcastService>(relaxed = true)
    private val importer = mockk<IImportService>()
    private val collection = mockk<ICollectionService>()
    private val hue = mockk<IHueService>()

    private lateinit var directory: File
    private lateinit var episodeFile: File
    private val episodeBytes = ByteArray(512) { (it % 67).toByte() }
    private val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val tokenCalls: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun createEpisodeFile() {
        directory = Files.createTempDirectory("rest-client").toFile()
        episodeFile = File(directory, "episode.mp3")
        episodeFile.writeBytes(episodeBytes)
    }

    @AfterEach
    fun removeEpisodeFile() {
        directory.deleteRecursively()
    }

    private fun ApplicationTestBuilder.setUpApplication(serveEpisodeFile: Boolean = true) {
        environment { config = MapApplicationConfig() }
        application {
            install(SSE)
            install(ContentNegotiation) { json(AppJson) }
            install(OpenApi)
            install(Authentication) {
                bearer(AUTH_PROVIDER) {
                    authenticate { credential -> UserIdPrincipal(USER).takeIf { credential.token == FRESH_TOKEN } }
                }
            }
            intercept(ApplicationCallPipeline.Plugins) {
                authorizations += call.request.headers[HttpHeaders.Authorization]
            }
            routing {
                registerIImageServiceRest { image }
                authenticate(AUTH_PROVIDER) {
                    registerIMetadataServiceRest { metadata }
                    registerIPodcastServiceRest {
                        if (serveEpisodeFile) StreamingPodcastService(podcast, episodeFile) else podcast
                    }
                    registerIImportServiceRest { importer }
                    registerICollectionServiceRest { collection }
                    registerIHueServiceRest { hue }
                }
            }
        }
    }

    private fun ApplicationTestBuilder.config(
        storedToken: String = FRESH_TOKEN,
        refreshedToken: String = FRESH_TOKEN,
    ) = RestClientConfig(
        httpClient = client,
        baseUrl = { BASE_URL },
        token = { forceRefresh ->
            tokenCalls += forceRefresh
            if (forceRefresh) refreshedToken else storedToken
        },
    )

    private fun progress(positionMs: Long) = PodcastEpisodeProgress(
        episodeId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        showId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        positionMs = positionMs,
        completed = false,
        lastPlayedAt = 10,
        updatedAt = 20,
    )

    @Test
    fun `a plain call decodes the json answer and sends the token`() = testApplication {
        setUpApplication()
        val bridge = HueBridgeInfo(id = UUID.randomUUID(), bridgeId = "abc", ip = "10.0.0.2", name = "Bridge")
        coEvery { hue.listBridges() } returns listOf(bridge)

        val result = RestHueService(config()).listBridges()

        assertEquals(listOf(bridge), result)
        assertEquals(listOf<String?>("Bearer $FRESH_TOKEN"), authorizations.toList())
        assertEquals(listOf(false), tokenCalls.toList())
    }

    @Test
    fun `path parameters reach the service decoded`() = testApplication {
        setUpApplication()
        val id = UUID.randomUUID()
        val type = IMetadataService.MetadataType("tidal")
        coEvery { collection.delete(id) } returns true
        coEvery { metadata.albumExistsById(type, PATH_TEXT) } returns true

        assertEquals(true, RestCollectionService(config()).delete(id))
        assertEquals(true, RestMetadataService(config()).albumExistsById(type, PATH_TEXT))

        coVerify { collection.delete(id) }
        coVerify { metadata.albumExistsById(type, PATH_TEXT) }
    }

    @Test
    fun `a single body parameter is sent as the json body`() = testApplication {
        setUpApplication()
        val report = EpisodePlaybackReport(
            episodeId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
            positionMs = 1234,
            durationMs = 5000,
            deviceId = "phone",
        )
        coEvery { podcast.reportPlayback(report) } returns progress(1234)

        val result = RestPodcastService(config()).reportPlayback(report)

        assertEquals(progress(1234), result)
        coVerify { podcast.reportPlayback(report) }
    }

    @Test
    fun `several body parameters are sent as one json object`() = testApplication {
        setUpApplication()
        val backend = ImportBackend("gamdl")
        val credentials: ImporterCredentials = GamdlCredentials(cookiesTxt = "cookie", wvdBase64 = "wvd")
        coEvery { importer.setImportCredentials(any(), any()) } returns Unit

        RestImportService(config()).setImportCredentials(backend, credentials)

        coVerify { importer.setImportCredentials(backend, credentials) }
    }

    @Test
    fun `a flow is cold and yields the server sent events in order without the keep alive`() = testApplication {
        setUpApplication()
        every { podcast.observeProgress() } returns flowOf(progress(1), progress(2), progress(3))

        val flow = RestPodcastService(config()).observeProgress()

        assertEquals(emptyList<String?>(), authorizations.toList())
        assertEquals(listOf(progress(1), progress(2), progress(3)), flow.toList())
        assertEquals(1, authorizations.size)
    }

    @Test
    fun `a file response is read in chunks and from an offset`() = testApplication {
        setUpApplication()
        val id = UUID.randomUUID()
        val client = RestPodcastService(config())

        val whole = client.streamEpisode(id, 0, 100)
        assertEquals(emptyList<String?>(), authorizations.toList())
        val chunks = whole!!.toList()
        assertEquals(listOf(100, 100, 100, 100, 100, 12), chunks.map { it.size })
        assertArrayEquals(episodeBytes, chunks.reduce { all, chunk -> all + chunk })

        val tail = client.streamEpisode(id, 200, 128)!!.toList()
        assertEquals(listOf(128, 128, 56), tail.map { it.size })
        assertArrayEquals(episodeBytes.copyOfRange(200, episodeBytes.size), tail.reduce { all, chunk -> all + chunk })
    }

    @Test
    fun `a file response without a file streams the service flow and a missing one fails at collection`() =
        testApplication {
            setUpApplication(serveEpisodeFile = false)
            val served = UUID.randomUUID()
            val missing = UUID.randomUUID()
            every { podcast.streamEpisode(served, 3, 4) } returns flowOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            every { podcast.streamEpisode(missing, any(), any()) } returns null
            val client = RestPodcastService(config())

            val chunks = client.streamEpisode(served, 3, 4)!!.toList()
            assertEquals(listOf(4, 2), chunks.map { it.size })
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), chunks.reduce { all, chunk -> all + chunk })

            val flow = client.streamEpisode(missing, 0, 4096)!!
            assertEquals(HttpStatusCode.NotFound.value, assertFailsWith<RestException> { flow.toList() }.status)
        }

    @Test
    fun `a public route is called without a token`() = testApplication {
        setUpApplication()
        val id = UUID.randomUUID()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        coEvery { image.getImageData(id, 64) } returns bytes

        val result = RestImageService(config()).getImageData(id, 64)

        assertArrayEquals(bytes, result)
        assertEquals(listOf<String?>(null), authorizations.toList())
        assertEquals(emptyList<Boolean>(), tokenCalls.toList())
    }

    @Test
    fun `metadata types are mapped back from their values`() = testApplication {
        setUpApplication()
        val features = setOf(IMetadataService.Feature.SEARCH_ALBUMS, IMetadataService.Feature.SEARCH_TRACKS)
        val types = listOf(IMetadataService.MetadataType("tidal"), IMetadataService.MetadataType("spotify"))
        coEvery { metadata.allMetadataTypes(features) } returns types

        val result = RestMetadataService(config()).allMetadataTypes(features)

        assertEquals(types, result)
    }

    @Test
    fun `a 404 is null for a nullable result`() = testApplication {
        setUpApplication()
        coEvery { podcast.getShow(any()) } returns null

        assertNull(RestPodcastService(config()).getShow(UUID.randomUUID()))
    }

    @Test
    fun `a 400 is an IllegalArgumentException and a 403 a RestException`() = testApplication {
        setUpApplication()
        val invalidId = UUID.randomUUID()
        val forbiddenId = UUID.randomUUID()
        coEvery { podcast.getShow(invalidId) } throws IllegalArgumentException("bad input")
        coEvery { podcast.getShow(forbiddenId) } throws UnauthorizedException("nope")
        val client = RestPodcastService(config())

        val invalid = assertFailsWith<IllegalArgumentException> { client.getShow(invalidId) }
        assertEquals("bad input", invalid.message)

        val forbidden = assertFailsWith<RestException> { client.getShow(forbiddenId) }
        assertEquals(HttpStatusCode.Forbidden.value, forbidden.status)
        assertEquals("nope", forbidden.message)
    }

    @Test
    fun `a 401 repeats the call once with a refreshed token`() = testApplication {
        setUpApplication()
        coEvery { hue.listBridges() } returns emptyList()

        val result = RestHueService(config(storedToken = STALE_TOKEN)).listBridges()

        assertEquals(emptyList<HueBridgeInfo>(), result)
        assertEquals(listOf(false, true), tokenCalls.toList())
        assertEquals(listOf<String?>("Bearer $STALE_TOKEN", "Bearer $FRESH_TOKEN"), authorizations.toList())
        coVerify(exactly = 1) { hue.listBridges() }
    }

    @Test
    fun `a second 401 is a RestException`() = testApplication {
        setUpApplication()

        val failure = assertFailsWith<RestException> {
            RestHueService(config(storedToken = STALE_TOKEN, refreshedToken = STALE_TOKEN)).listBridges()
        }

        assertEquals(HttpStatusCode.Unauthorized.value, failure.status)
        assertEquals(listOf(false, true), tokenCalls.toList())
        coVerify(exactly = 0) { hue.listBridges() }
    }

    private companion object {
        const val AUTH_PROVIDER = "synara-auth"
        const val USER = "user"
        const val BASE_URL = "http://localhost"
        const val FRESH_TOKEN = "fresh-token"
        const val STALE_TOKEN = "stale-token"
        const val PATH_TEXT = "a b/c?d"
    }
}
