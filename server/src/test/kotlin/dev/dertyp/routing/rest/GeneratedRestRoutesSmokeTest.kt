package dev.dertyp.routing.rest

import dev.dertyp.StreamInfo
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.*
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.IArtistService
import dev.dertyp.services.ICollectionService
import dev.dertyp.services.ICoverGenerationService
import dev.dertyp.services.IHueService
import dev.dertyp.services.IImageService
import dev.dertyp.services.IPodcastService
import dev.dertyp.services.IQueueService
import dev.dertyp.services.IRemoteMirrorService
import dev.dertyp.services.`import`.GamdlCredentials
import dev.dertyp.services.`import`.IImportService
import dev.dertyp.services.`import`.ImportBackend
import dev.dertyp.services.`import`.ImporterCredentials
import dev.dertyp.services.metadata.IMetadataService
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.*
import io.ktor.server.testing.*
import io.mockk.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

private class StreamingPodcastService(
    private val delegate: IPodcastService,
    private val file: File,
) : IPodcastService by delegate, RestFileProvider {
    override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
        if (methodName != "streamEpisode") return null
        return StreamInfo(file, ContentType.Audio.MPEG, file.length(), file.name)
    }
}

class GeneratedRestRoutesSmokeTest {
    private val image = mockk<IImageService>()
    private val metadata = mockk<IMetadataService>()
    private val podcast = mockk<IPodcastService>(relaxed = true)
    private val queue = mockk<IQueueService>()
    private val cover = mockk<ICoverGenerationService>()
    private val importer = mockk<IImportService>()
    private val artist = mockk<IArtistService>()
    private val collection = mockk<ICollectionService>()
    private val remoteMirror = mockk<IRemoteMirrorService>()
    private val hue = mockk<IHueService>()

    private lateinit var directory: File
    private lateinit var episodeFile: File
    private val episodeBytes = ByteArray(512) { (it % 67).toByte() }
    private var tree: Set<Pair<String, String>> = emptySet()

    @BeforeEach
    fun createEpisodeFile() {
        directory = Files.createTempDirectory("rest-smoke").toFile()
        episodeFile = File(directory, "episode.mp3")
        episodeFile.writeBytes(episodeBytes)
    }

    @AfterEach
    fun removeEpisodeFile() {
        directory.deleteRecursively()
    }

    private fun ApplicationTestBuilder.setUpApplication(imageAuthenticated: Boolean = false) {
        environment { config = MapApplicationConfig() }
        application {
            install(SSE)
            install(ContentNegotiation) { json(AppJson) }
            install(OpenApi) {
                schemas {
                    generator = SchemaGenerator.kotlinx(AppJson) {
                        overwrite(SchemaGenerator.TypeOverwrites.JavaUuid())
                        overwrite(SchemaGenerator.TypeOverwrites.KotlinUuid())
                    }
                }
            }
            routing {
                route("api.json") { openApi() }
                registerIImageServiceRest(authenticated = imageAuthenticated) { image }
                registerIMetadataServiceRest { metadata }
                registerIPodcastServiceRest { StreamingPodcastService(podcast, episodeFile) }
                registerIQueueServiceRest { queue }
                registerICoverGenerationServiceRest { cover }
                registerIImportServiceRest { importer }
                registerIArtistServiceRest { artist }
                registerICollectionServiceRest { collection }
                registerIRemoteMirrorServiceRest { remoteMirror }
                registerIHueServiceRest { hue }
            }
            tree = RestGoldenSupport.collectLeaves(plugin(RoutingRoot))
        }
    }

    private fun progress(positionMs: Long) = PodcastEpisodeProgress(
        episodeId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        showId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        positionMs = positionMs,
        completed = false,
        lastPlayedAt = 10,
        updatedAt = 20,
    )

    private fun remoteStats() = ServerStats(
        songCount = 1,
        albumCount = 1,
        artistCount = 1,
        imagesCount = 0,
        animatedImagesCount = 0,
        playlistCount = 0,
        totalFileSize = 0,
        indexedFileSize = 0,
        averageSizePerSong = 0,
        totalDuration = 0,
        transcodeStats = emptyList(),
        musicBrainzCache = ServerStats.MusicBrainzCacheStats(0, 0, 0, 0, 0, 0, 0, 0),
        version = ServerStats.Version("", "", "", "", ""),
    )

    private fun queueResult() = QueueWriteResult.Ok(
        QueueInfo(
            version = 2,
            modifiedAt = 5,
            currentIndex = 0,
            shuffleMode = false,
            repeatMode = RepeatMode.OFF,
            total = 0,
        ),
    )

    @Test
    fun `a public route serves bytes without a principal`() = testApplication {
        setUpApplication(imageAuthenticated = true)
        val id = UUID.randomUUID()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        coEvery { image.getImageData(id, 64) } returns bytes

        val response = client.get("/image/imageData/$id?size=64")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.JPEG, response.contentType()?.withoutParameters())
        assertTrue(bytes.contentEquals(response.readRawBytes()))
        coVerify { image.getImageData(id, 64) }
    }

    @Test
    fun `an authenticated route without a principal is a 401`() = testApplication {
        setUpApplication(imageAuthenticated = true)

        val response = client.get("/image/byId/${UUID.randomUUID()}")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        coVerify(exactly = 0) { image.byId(any()) }
    }

    @Test
    fun `a comma separated query list is split and the map is returned as json`() = testApplication {
        setUpApplication()
        val mapped = UUID.fromString("44444444-4444-4444-4444-444444444444")
        coEvery { image.getCoverHashes(listOf("a", "b")) } returns mapOf("a" to mapped)

        val response = client.get("/image/coverHashes?hashes=a,b")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"a":"$mapped"}""", response.bodyAsText())
        coVerify { image.getCoverHashes(listOf("a", "b")) }
    }

    @Test
    fun `metadata types are unwrapped to their values`() = testApplication {
        setUpApplication()
        coEvery { metadata.getAllMetadataTypes(any()) } returns listOf(
            IMetadataService.MetadataType("tidal"),
            IMetadataService.MetadataType("spotify"),
        )

        val response = client.get("/metadata/allMetadataTypes")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""["tidal","spotify"]""", response.bodyAsText())
    }

    @Test
    fun `an omitted optional parameter takes the interface default`() = testApplication {
        setUpApplication()
        coEvery { metadata.search(any(), any(), any()) } returns emptyList()

        val response = client.get("/metadata/tidal/tracks?query=q")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
        coVerify { metadata.search(IMetadataService.MetadataType("tidal"), "q", 50) }
    }

    @Test
    fun `a type path parameter and a trailing id path parameter are bound`() = testApplication {
        setUpApplication()
        coEvery { metadata.albumExistsById(any(), any()) } returns true

        val response = client.get("/metadata/tidal/albumExistsById/x")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("true", response.bodyAsText())
        coVerify { metadata.albumExistsById(IMetadataService.MetadataType("tidal"), "x") }
    }

    @Test
    fun `a flow is served as server sent events and null items are skipped`() = testApplication {
        setUpApplication()
        val items = flowOf(progress(1), null, progress(2))

        @Suppress("UNCHECKED_CAST")
        every { podcast.observeProgress() } returns items as Flow<PodcastEpisodeProgress>

        val response = client.get("/podcast/observeProgress")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.EventStream, response.contentType()?.withoutParameters())
        assertEquals("no-cache", response.headers[HttpHeaders.CacheControl])
        val body = response.bodyAsText()
        val frames = body.split("\r\n\r\n").filter { it.isNotEmpty() }
        assertEquals(2, frames.size)
        assertTrue(frames.all { it.startsWith("data: {") })
        assertTrue(body.contains("\"positionMs\":1"))
        assertTrue(body.contains("\"positionMs\":2"))
    }

    @Test
    fun `an unparsable query parameter is a 400`() = testApplication {
        setUpApplication()

        val response = client.get("/podcast/episodes/${UUID.randomUUID()}?page=abc")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("Invalid Int value: abc"))
    }

    @Test
    fun `a null result is a 404`() = testApplication {
        setUpApplication()
        coEvery { podcast.getShow(any()) } returns null

        val response = client.get("/podcast/show/${UUID.randomUUID()}")

        assertEquals(HttpStatusCode.NotFound, response.status)
        coVerify { podcast.getShow(any()) }
    }

    @Test
    fun `an unauthorized failure is a 403 and any other failure is a 500`() = testApplication {
        setUpApplication()
        val forbiddenId = UUID.randomUUID()
        val failingId = UUID.randomUUID()
        coEvery { podcast.getShow(forbiddenId) } throws UnauthorizedException("nope")
        coEvery { podcast.getShow(failingId) } throws IllegalStateException("boom")

        val forbidden = client.get("/podcast/show/$forbiddenId")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertEquals("nope", forbidden.bodyAsText())

        val failed = client.get("/podcast/show/$failingId")
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        assertEquals("boom", failed.bodyAsText())
    }

    @Test
    fun `a file response answers head with the content length and no body`() = testApplication {
        setUpApplication()

        val response = client.head("/podcast/streamEpisode/${UUID.randomUUID()}")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
        assertEquals(episodeBytes.size.toString(), response.headers[HttpHeaders.ContentLength])
        assertTrue(response.readRawBytes().isEmpty())
    }

    @Test
    fun `a delete route binds a long list and the default flag`() = testApplication {
        setUpApplication()
        coEvery { queue.remove(any(), any(), any()) } returns queueResult()

        val response = client.delete("/queue/entries?baseVersion=1&queueIds=1,2,3")

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { queue.remove(1L, listOf(1L, 2L, 3L), false) }
    }

    @Test
    fun `an enum query parameter is matched ignoring case`() = testApplication {
        setUpApplication()
        coEvery { queue.setModes(any(), any(), any(), any()) } returns queueResult()

        val response = client.put("/queue/modes?baseVersion=1&shuffleMode=true&repeatMode=all")

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { queue.setModes(1L, true, RepeatMode.ALL, false) }
    }

    @Test
    fun `a json body is decoded into the single body parameter`() = testApplication {
        setUpApplication()
        val episodeId = UUID.fromString("55555555-5555-5555-5555-555555555555")
        val report = EpisodePlaybackReport(episodeId = episodeId, positionMs = 1234, durationMs = 5000, deviceId = "phone")
        coEvery { podcast.reportPlayback(any()) } returns progress(1234)

        val response = client.post("/podcast/reportPlayback") {
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(EpisodePlaybackReport.serializer(), report))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { podcast.reportPlayback(report) }
    }

    @Test
    fun `an omitted optional body field takes the interface default`() = testApplication {
        setUpApplication()
        val target = CoverTarget(CoverTargetType.PLAYLIST, UUID.fromString("66666666-6666-6666-6666-666666666666"))
        coEvery { cover.previewCoverImage(any(), any()) } returns byteArrayOf(7, 8, 9)

        val response = client.post("/coverGeneration/previewCoverImage") {
            contentType(ContentType.Application.Json)
            setBody(
                AppJson.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject { put("target", AppJson.encodeToJsonElement(CoverTarget.serializer(), target)) },
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.OctetStream, response.contentType()?.withoutParameters())
        assertTrue(byteArrayOf(7, 8, 9).contentEquals(response.readRawBytes()))
        coVerify { cover.previewCoverImage(target, CoverGenerationParams()) }
    }

    @Test
    fun `two body parameters are keyed by their parameter name in one json object`() = testApplication {
        setUpApplication()
        val backend = ImportBackend("gamdl")
        val credentials: ImporterCredentials = GamdlCredentials(cookiesTxt = "cookie", wvdBase64 = "wvd")
        coEvery { importer.setImportCredentials(any(), any()) } returns Unit

        val response = client.put("/import/importCredentials") {
            contentType(ContentType.Application.Json)
            setBody(
                AppJson.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("backend", AppJson.encodeToJsonElement(ImportBackend.serializer(), backend))
                        put("credentials", AppJson.encodeToJsonElement(ImporterCredentials.serializer(), credentials))
                    },
                ),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { importer.setImportCredentials(backend, credentials) }
    }

    @Test
    fun `byte responses sniff the media type and are served inline`() = testApplication {
        setUpApplication()
        val id = UUID.randomUUID()
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        coEvery { image.getImageData(id, 0) } returns png

        val response = client.get("/image/imageData/$id")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Image.PNG, response.contentType()?.withoutParameters())
        assertEquals(ContentDisposition.Inline.toString(), response.headers[HttpHeaders.ContentDisposition])
        assertTrue(png.contentEquals(response.readRawBytes()))
    }

    @Test
    fun `an optional id parameter stays a query parameter`() = testApplication {
        setUpApplication()
        val musicBrainzId = UUID.fromString("77777777-7777-7777-7777-777777777777")
        coEvery { artist.createArtist(any(), any(), any(), any()) } returns Artist(
            id = UUID.fromString("88888888-8888-8888-8888-888888888888"),
            name = "X",
            isGroup = false,
        )

        val withId = client.post("/artist/artist?name=X&musicBrainzId=$musicBrainzId")
        assertEquals(HttpStatusCode.OK, withId.status)
        coVerify { artist.createArtist("X", false, "", musicBrainzId) }

        val withoutId = client.post("/artist/artist?name=X")
        assertEquals(HttpStatusCode.OK, withoutId.status)
        coVerify { artist.createArtist("X", false, "", null) }
    }

    @Test
    fun `a renamed delete route binds its path parameter`() = testApplication {
        setUpApplication()
        val id = UUID.randomUUID()
        coEvery { collection.delete(id) } returns true

        val response = client.delete("/collection/collection/$id")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("true", response.bodyAsText())
        coVerify { collection.delete(id) }
    }

    @Test
    fun `a config parameter moved from the query into the body is decoded`() = testApplication {
        setUpApplication()
        val config = RemoteServerConfig(host = "remote", port = 8080, username = "user", password = "secret")
        coEvery { remoteMirror.getRemoteStats(any()) } returns remoteStats()

        val response = client.post("/remoteMirror/remoteStats") {
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(RemoteServerConfig.serializer(), config))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { remoteMirror.getRemoteStats(config) }
    }

    @Test
    fun `the hue bridge routes use the read and delete verbs`() = testApplication {
        setUpApplication()
        val id = UUID.randomUUID()
        val bridge = HueBridgeInfo(id = id, bridgeId = "abc", ip = "10.0.0.2", name = "Bridge")
        coEvery { hue.listBridges() } returns listOf(bridge)
        coEvery { hue.removeBridge(id) } returns true

        val listed = client.get("/hue/bridges")
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue(listed.bodyAsText().contains("\"bridgeId\":\"abc\""))

        val removed = client.delete("/hue/bridge/$id")
        assertEquals(HttpStatusCode.OK, removed.status)
        coVerify { hue.removeBridge(id) }
    }

    @Test
    fun `a repeated query list parameter is bound`() = testApplication {
        setUpApplication()
        coEvery { podcast.searchIndex("x", 25, listOf("a", "b")) } returns emptyList()

        val response = client.get("/podcast/index?query=x&indexes=a&indexes=b")

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { podcast.searchIndex("x", 25, listOf("a", "b")) }
    }

    @Test
    fun `the podcast indexes are listed`() = testApplication {
        setUpApplication()
        coEvery { podcast.getIndexes() } returns emptyList()

        val response = client.get("/podcast/indexes")

        assertEquals(HttpStatusCode.OK, response.status)
        coVerify { podcast.getIndexes() }
    }

    @Test
    fun `the documented paths are exactly the routed paths`() = testApplication {
        setUpApplication()

        val document = AppJson.parseToJsonElement(client.get("/api.json").bodyAsText()).jsonObject
        val documented = document["paths"]!!.jsonObject.entries
            .filterNot { it.key.startsWith("/api.json") }
            .flatMap { (path, methods) -> methods.jsonObject.keys.map { it.uppercase() to path } }
            .toSet()
        val routed = tree.map { (method, path) -> method to path.trimEnd('/') }.toSet()

        assertEquals(emptySet<Pair<String, String>>(), documented - routed, "documented but not routed")
        assertEquals(emptySet<Pair<String, String>>(), routed - documented, "routed but not documented")
    }
}
