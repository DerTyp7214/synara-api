package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.UnsynchronizedAppenderBase
import dev.dertyp.StreamInfo
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.core.anyHeader
import dev.dertyp.data.*
import dev.dertyp.proxy.HttpTunnel
import dev.dertyp.proxy.module as proxyModule
import dev.dertyp.routing.radioRouting
import dev.dertyp.routing.rest.*
import dev.dertyp.rpc.rest.client.*
import dev.dertyp.serializers.AppJson
import dev.dertyp.services.`import`.GamdlCredentials
import dev.dertyp.services.`import`.IImportService
import dev.dertyp.services.`import`.ImportBackend
import dev.dertyp.services.`import`.ImporterCredentials
import dev.dertyp.services.metadata.IMetadataService
import dev.dertyp.ui.UiSchemaVersion
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.toByteArray
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.slf4j.LoggerFactory
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Timeout(180)
class ReverseProxyRestEndToEndTest : KoinTest {

    class Registrations : UnsynchronizedAppenderBase<ILoggingEvent>() {
        val count = AtomicInteger(0)

        override fun append(event: ILoggingEvent) {
            if (event.formattedMessage.startsWith("Proxy assigned ID")) count.incrementAndGet()
        }
    }

    class EpisodeFiles(
        private val delegate: IPodcastService,
        private val files: Map<UUID, File>,
        private val names: Map<UUID, String>,
    ) : IPodcastService by delegate, RestFileProvider {
        override suspend fun getFile(methodName: String, args: List<Any?>): StreamInfo? {
            val file = files[args.first()] ?: return null
            return StreamInfo(file, ContentType.Audio.MPEG, file.length(), names[args.first()] ?: file.name)
        }
    }

    class Stack(
        scope: CoroutineScope,
        val proxyPort: Int,
        val targetPort: Int,
        val service: ReverseProxyService,
        private val registrations: Registrations,
        val http: HttpClient,
        val token: String,
        val staleToken: String,
    ) : CoroutineScope by scope {
        val proxied = "http://127.0.0.1:$proxyPort/$INSTANCE"
        val direct = "http://127.0.0.1:$targetPort"
        val tokenCalls: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

        fun config(baseUrl: String = proxied, stored: String = token, refreshed: String = token) = RestClientConfig(
            httpClient = http,
            baseUrl = { baseUrl },
            token = { forceRefresh ->
                tokenCalls += forceRefresh
                if (forceRefresh) refreshed else stored
            },
        )

        suspend fun awaitRegistration(count: Int) = withTimeout(30.seconds) {
            while (registrations.count.get() < count || service.proxyId != INSTANCE) delay(10.milliseconds)
        }
    }

    private val image = mockk<IImageService>()
    private val hue = mockk<IHueService>()
    private val collection = mockk<ICollectionService>()
    private val metadata = mockk<IMetadataService>()
    private val podcast = mockk<IPodcastService>(relaxed = true)
    private val importer = mockk<IImportService>()
    private val cover = mockk<ICoverGenerationService>()
    private val radio = mockk<IRadioService>()
    private val customAudio = mockk<ICustomAudioService>()

    private val userService = mockk<UserService>()
    private val refreshTokenService = mockk<RefreshTokenService>(relaxed = true)
    private val sessionService = mockk<SessionService>(relaxed = true)
    private val apiKeyService = mockk<ApiKeyService>()
    private val jwtService = JwtService(
        ServerConfig(
            MapApplicationConfig(
                "jwt.audience" to "test-audience",
                "jwt.issuer" to "test-issuer",
                "jwt.realm" to "test-realm",
                "jwt.secret" to "test-secret-key-must-be-long-enough-for-hmac-sha-256-verification",
            )
        ),
        userService,
        refreshTokenService,
        sessionService,
    )

    private val user = User(id = UUID.randomUUID(), username = USERNAME)
    private val activeSession = UUID.randomUUID()
    private val loginSession = UUID.randomUUID()
    private val episodeId = UUID.randomUUID()
    private val episodeBytes = pattern(EPISODE_SIZE)
    private val files = ConcurrentHashMap<UUID, File>()
    private val fileNames = ConcurrentHashMap<UUID, String>()
    private val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val notes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val bridge = HueBridgeInfo(id = UUID.randomUUID(), bridgeId = "abc", ip = "10.0.0.2", name = "Bridge")

    private lateinit var directory: File

    @BeforeEach
    fun setUp() {
        directory = Files.createTempDirectory("rest-end-to-end").toFile()
        files[episodeId] = File(directory, "episode.mp3").apply { writeBytes(episodeBytes) }

        coEvery { userService.findUserByUsername(any()) } returns null
        coEvery { userService.findUserByUsername(USERNAME) } returns user
        coEvery { userService.passwordHashOf(any()) } returns null
        coEvery { userService.passwordHashOf(USERNAME) } returns
            BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray())
        coEvery { sessionService.isSessionActive(activeSession) } returns true
        coEvery { sessionService.isSessionActive(loginSession) } returns true
        coEvery { sessionService.createSession(user.id, any(), any()) } returns loginSession
        coEvery { apiKeyService.resolveUser(any(), any()) } returns user
        coEvery { hue.listBridges() } returns listOf(bridge)
    }

    @AfterEach
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun pattern(size: Int): ByteArray = ByteArray(size) { (it * 31 + it / 251).toByte() }

    private fun progress(positionMs: Long) = PodcastEpisodeProgress(
        episodeId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        showId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        positionMs = positionMs,
        completed = false,
        lastPlayedAt = 10,
        updatedAt = 20,
    )

    private fun Application.targetModule(routes: Route.() -> Unit) {
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
        install(Compression)
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
            }
        }
        install(CORS) {
            anyHeader(true)
            allowHeader(ApiVersion.HEADER)
            allowHeader(UiSchemaVersion.HEADER)
            anyMethod()
            anyHost()
        }
        jwtService.authenticate(this)
        intercept(ApplicationCallPipeline.Plugins) {
            authorizations += call.request.headers[HttpHeaders.Authorization]
            call.request.headers[NOTE_HEADER]?.let { notes += it }
        }
        routing {
            authenticate(JwtService.AUTH_PROVIDER, optional = true) {
                registerIImageServiceRest(authenticated = true) { image }
            }
            jwtService.authenticated(this) {
                registerIHueServiceRest(authenticated = true) { hue }
                registerICollectionServiceRest(authenticated = true) { collection }
                registerIMetadataServiceRest(authenticated = true) { metadata }
                registerIPodcastServiceRest(authenticated = true) { EpisodeFiles(podcast, files, fileNames) }
                registerIImportServiceRest(authenticated = true) { importer }
                registerICoverGenerationServiceRest(authenticated = true) { cover }
                registerIRadioServiceRest(authenticated = true) { radio }
                registerICustomAudioServiceRest(authenticated = true) { customAudio }
            }
            jwtService.authenticate(this)
            radioRouting()
            routes()
        }
    }

    private fun endToEnd(routes: Route.() -> Unit = {}, block: suspend Stack.() -> Unit): Unit = runBlocking {
        val target = embeddedServer(Netty, port = 0, host = "127.0.0.1") { targetModule(routes) }
        val proxy = embeddedServer(
            Netty,
            serverConfig(applicationEnvironment { config = MapApplicationConfig("proxy.key" to PROXY_KEY) }) {
                module { proxyModule() }
            },
        ) {
            connector {
                host = "127.0.0.1"
                port = 0
            }
        }
        val httpClientFactory = HttpClientFactory()
        startKoin {
            modules(module {
                single<Application> { target.application }
                single { httpClientFactory }
                single { ReverseProxyHttpGateway() }
                single { jwtService }
                single { userService }
                single { sessionService }
                single { apiKeyService }
            })
        }
        val serviceLogger = LoggerFactory.getLogger(ReverseProxyService::class.simpleName) as Logger
        val registrations = Registrations()
        registrations.context = serviceLogger.loggerContext
        registrations.start()
        serviceLogger.addAppender(registrations)
        val http = HttpClient(CIO)
        var job: Job? = null
        try {
            target.start(wait = false)
            proxy.start(wait = false)
            val proxyPort = proxy.engine.resolvedConnectors().first().port
            val service = ReverseProxyService(
                ServerConfig(
                    MapApplicationConfig(
                        "proxy.hostname" to "127.0.0.1",
                        "proxy.controlPort" to proxyPort.toString(),
                        "proxy.id" to INSTANCE,
                        "proxy.name" to "End to end",
                        "proxy.key" to PROXY_KEY,
                    )
                )
            )
            job = launch(Dispatchers.Default) { service.startService() }
            val token = jwtService.generateToken(user, activeSession)!!.token
            val staleToken = jwtService.generateToken(user, UUID.randomUUID())!!.token
            val targetPort = target.engine.resolvedConnectors().first().port
            withTimeout(120.seconds) {
                val stack = Stack(this, proxyPort, targetPort, service, registrations, http, token, staleToken)
                stack.awaitRegistration(1)
                stack.block()
            }
        } finally {
            job?.cancelAndJoin()
            http.close()
            httpClientFactory.stopService()
            proxy.stop(500, 500)
            target.stop(500, 500)
            serviceLogger.detachAppender(registrations)
            stopKoin()
        }
    }

    private suspend fun exchange(port: Int, request: String): String = withContext(Dispatchers.IO) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 20_000
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            String(socket.getInputStream().readBytes(), Charsets.ISO_8859_1)
        }
    }

    private fun String.statusCode(): String = substringBefore("\r\n").substringAfter(' ').substringBefore(' ')

    private fun String.headerValues(name: String): List<String> = substringBefore("\r\n\r\n").lines()
        .filter { it.startsWith("$name:", ignoreCase = true) }
        .map { it.substringAfter(':').trim() }

    @Test
    fun `json calls through the generated clients return what direct access returns`() = endToEnd {
        val collectionId = UUID.randomUUID()
        val type = IMetadataService.MetadataType("tidal")
        val report = EpisodePlaybackReport(
            episodeId = UUID.fromString("55555555-5555-5555-5555-555555555555"),
            positionMs = 1234,
            durationMs = 5000,
            deviceId = "phone",
        )
        val backend = ImportBackend("gamdl")
        val credentials: ImporterCredentials = GamdlCredentials(cookiesTxt = "cookie", wvdBase64 = "wvd")
        val coverTarget = CoverTarget(CoverTargetType.PLAYLIST, UUID.randomUUID())
        val coverParams = CoverGenerationParams(seed = 7)
        val preview = byteArrayOf(7, 8, 9)
        coEvery { collection.delete(collectionId) } returns true
        coEvery { metadata.albumExistsById(type, PATH_TEXT) } returns true
        coEvery { podcast.reportPlayback(report) } returns progress(1234)
        coEvery { importer.setImportCredentials(any(), any()) } returns Unit
        coEvery { cover.previewCoverImage(coverTarget, coverParams) } returns preview

        for (base in listOf(direct, proxied)) {
            val config = config(base)
            assertEquals(listOf(bridge), RestHueService(config).listBridges(), base)
            assertEquals(true, RestCollectionService(config).delete(collectionId), base)
            assertEquals(true, RestMetadataService(config).albumExistsById(type, PATH_TEXT), base)
            assertEquals(progress(1234), RestPodcastService(config).reportPlayback(report), base)
            RestImportService(config).setImportCredentials(backend, credentials)
            assertArrayEquals(preview, RestCoverGenerationService(config).previewCoverImage(coverTarget, coverParams), base)
        }

        coVerify(exactly = 2) { hue.listBridges() }
        coVerify(exactly = 2) { collection.delete(collectionId) }
        coVerify(exactly = 2) { metadata.albumExistsById(type, PATH_TEXT) }
        coVerify(exactly = 2) { podcast.reportPlayback(report) }
        coVerify(exactly = 2) { importer.setImportCredentials(backend, credentials) }
        coVerify(exactly = 2) { cover.previewCoverImage(coverTarget, coverParams) }
        assertEquals(List(12) { "Bearer $token" }, authorizations.toList())
    }

    @Test
    fun `server sent events arrive one by one while the stream is open`() = endToEnd {
        val emissions = Channel<PodcastEpisodeProgress>(Channel.UNLIMITED)
        every { podcast.observeProgress() } returns emissions.receiveAsFlow()
        val received = Channel<PodcastEpisodeProgress>(Channel.UNLIMITED)

        val collector = launch {
            RestPodcastService(config()).observeProgress().collect { received.send(it) }
        }
        for (position in 1L..4L) {
            assertNull(received.tryReceive().getOrNull())
            emissions.send(progress(position))
            assertEquals(progress(position), withTimeout(3.seconds) { received.receive() })
        }
        emissions.close()
        withTimeout(10.seconds) { collector.join() }
        assertNull(received.tryReceive().getOrNull())
    }

    @Test
    fun `a file larger than the window is read whole and from an offset`() = endToEnd {
        assertTrue(EPISODE_SIZE - FILE_OFFSET > HttpTunnel.INITIAL_WINDOW * 2)
        val client = RestPodcastService(config())

        val whole = client.streamEpisode(episodeId, 0, 65536)!!.toList()
        assertTrue(whole.dropLast(1).all { it.size == 65536 })
        assertArrayEquals(episodeBytes, whole.reduce { all, chunk -> all + chunk })

        val tail = client.streamEpisode(episodeId, FILE_OFFSET.toLong(), 8192)!!.toList()
        assertTrue(tail.dropLast(1).all { it.size == 8192 })
        assertArrayEquals(
            episodeBytes.copyOfRange(FILE_OFFSET, episodeBytes.size),
            tail.reduce { all, chunk -> all + chunk }
        )
    }

    @Test
    fun `a built url of a public route lies under the proxy prefix and is fetched without credentials`() = endToEnd {
        val id = UUID.randomUUID()
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        coEvery { image.getImageData(id, 64) } returns bytes

        val url = RestImageService(config()).getImageDataUrl(id, 64)
        assertEquals("http://127.0.0.1:$proxyPort/$INSTANCE/image/imageData/$id?size=64", url)

        HttpClient(CIO).use { plain ->
            val response = plain.get(url)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Image.JPEG, response.contentType()?.withoutParameters())
            assertArrayEquals(bytes, response.bodyAsBytes())
        }
        assertArrayEquals(bytes, RestImageService(config()).getImageData(id, 64))
        assertEquals(listOf<String?>(null, null), authorizations.toList())
        assertEquals(emptyList<Boolean>(), tokenCalls.toList())
    }

    @Test
    fun `a 401 of the real authentication repeats the call once with a refreshed token`() = endToEnd {
        val result = RestHueService(config(stored = staleToken)).listBridges()

        assertEquals(listOf(bridge), result)
        assertEquals(listOf(false, true), tokenCalls.toList())
        assertEquals(listOf<String?>("Bearer $staleToken", "Bearer $token"), authorizations.toList())
        coVerify(exactly = 1) { hue.listBridges() }

        val failure = assertFailsWith<RestException> {
            RestHueService(config(stored = staleToken, refreshed = staleToken)).listBridges()
        }
        assertEquals(HttpStatusCode.Unauthorized.value, failure.status)
        assertEquals(UNAUTHORIZED_BODY, failure.message)
        coVerify(exactly = 1) { hue.listBridges() }
    }

    @Test
    fun `a 404 is null and a 400 and a 403 are mapped to their exceptions`() = endToEnd {
        val missingId = UUID.randomUUID()
        val invalidId = UUID.randomUUID()
        val forbiddenId = UUID.randomUUID()
        coEvery { podcast.getShow(missingId) } returns null
        coEvery { podcast.getShow(invalidId) } throws IllegalArgumentException("bad input")
        coEvery { podcast.getShow(forbiddenId) } throws UnauthorizedException("nope")
        val client = RestPodcastService(config())

        assertNull(client.getShow(missingId))

        val invalid = assertFailsWith<IllegalArgumentException> { client.getShow(invalidId) }
        assertEquals("bad input", invalid.message)

        val forbidden = assertFailsWith<RestException> { client.getShow(forbiddenId) }
        assertEquals(HttpStatusCode.Forbidden.value, forbidden.status)
        assertEquals("nope", forbidden.message)
    }

    @Test
    fun `head and range on a file route keep their lengths`() = endToEnd {
        val url = "$proxied/podcast/streamEpisode/$episodeId"

        val head = http.head(url) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals(listOf(EPISODE_SIZE.toString()), head.headers.getAll(HttpHeaders.ContentLength))
        assertEquals("bytes", head.headers[HttpHeaders.AcceptRanges])
        assertNull(head.headers[HttpHeaders.TransferEncoding])
        assertEquals(0, head.bodyAsBytes().size)

        val wire = exchange(
            proxyPort,
            "HEAD /$INSTANCE/podcast/streamEpisode/$episodeId HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Authorization: Bearer $token\r\nConnection: close\r\n\r\n"
        )
        assertEquals("HTTP/1.1 200 OK", wire.substringBefore("\r\n"))
        assertEquals(listOf(EPISODE_SIZE.toString()), wire.headerValues(HttpHeaders.ContentLength))
        assertEquals(emptyList<String>(), wire.headerValues(HttpHeaders.TransferEncoding))
        assertEquals("", wire.substringAfter("\r\n\r\n"))

        val part = http.get(url) {
            bearerAuth(token)
            header(HttpHeaders.Range, "bytes=100-199")
        }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("bytes 100-199/$EPISODE_SIZE", part.headers[HttpHeaders.ContentRange])
        assertEquals(listOf("100"), part.headers.getAll(HttpHeaders.ContentLength))
        assertArrayEquals(episodeBytes.copyOfRange(100, 200), part.bodyAsBytes())
    }

    @Test
    fun `a login through the hand-written route yields a token that is accepted`() = endToEnd {
        val wrong = http.post("$proxied${JwtService.PATH_AUTHENTICATE}") {
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(AuthenticationRequest.serializer(), AuthenticationRequest(USERNAME, "no")))
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("Invalid username or password", wrong.bodyAsText())

        val login = http.post("$proxied${JwtService.PATH_AUTHENTICATE}") {
            contentType(ContentType.Application.Json)
            setBody(
                AppJson.encodeToString(AuthenticationRequest.serializer(), AuthenticationRequest(USERNAME, PASSWORD))
            )
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val issued = AppJson.decodeFromString(AuthenticationResponse.serializer(), login.bodyAsText())
        assertNotEquals(token, issued.token)
        coVerify(exactly = 1) { sessionService.createSession(user.id, any(), any()) }

        val info = http.get("$proxied${JwtService.PATH_USER_INFO}") { bearerAuth(issued.token) }
        assertEquals(HttpStatusCode.OK, info.status)
        assertEquals(UserInfo.fromUser(user), AppJson.decodeFromString(UserInfo.serializer(), info.bodyAsText()))

        assertEquals(listOf(bridge), RestHueService(config(stored = issued.token)).listBridges())
    }

    @Test
    fun `the auth cookie authenticates a request without an Authorization header`() = endToEnd {
        val response = http.get("$proxied/hue/bridges") {
            header(HttpHeaders.Cookie, "theme=dark; ${JwtService.AUTH_COOKIE}=$token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            listOf(bridge),
            AppJson.decodeFromString(ListSerializer(HueBridgeInfo.serializer()), response.bodyAsText())
        )
        assertEquals(listOf<String?>(null), authorizations.toList())

        val stale = http.get("$proxied/hue/bridges") {
            header(HttpHeaders.Cookie, "${JwtService.AUTH_COOKIE}=$staleToken")
        }
        assertEquals(HttpStatusCode.Unauthorized, stale.status)
    }

    @Test
    fun `an authenticated route without credentials is answered 401 with the body of the server`() = endToEnd {
        val throughProxy = http.get("$proxied/hue/bridges")
        val directly = http.get("$direct/hue/bridges")

        assertEquals(HttpStatusCode.Unauthorized, throughProxy.status)
        assertEquals(UNAUTHORIZED_BODY, throughProxy.bodyAsText())
        assertEquals(directly.status, throughProxy.status)
        assertEquals(directly.bodyAsText(), UNAUTHORIZED_BODY)
        assertEquals(directly.headers[HttpHeaders.ContentType], throughProxy.headers[HttpHeaders.ContentType])
        coVerify(exactly = 0) { hue.listBridges() }
    }

    @Test
    fun `paths that are not listed are answered 404 and never reach their handler`() {
        val hits = AtomicInteger(0)
        endToEnd({
            get("/swagger") {
                hits.incrementAndGet()
                call.respondText("docs")
            }
            get("/static/{file}") {
                hits.incrementAndGet()
                call.respondText("asset")
            }
        }) {
            for (path in listOf("/swagger", "/static/app.js")) {
                val response = http.get("$proxied$path") { bearerAuth(token) }
                assertEquals(HttpStatusCode.NotFound, response.status, path)
                assertEquals("", response.bodyAsText(), path)
            }
            assertEquals(0, hits.get())
            assertEquals(emptyList<String?>(), authorizations.toList())

            assertEquals("docs", http.get("$direct/swagger").bodyAsText())
            assertEquals("asset", http.get("$direct/static/app.js").bodyAsText())
            assertEquals(2, hits.get())
        }
    }

    @Test
    fun `the url shared by the generated and the hand-written radio route is answered by the generated one`() =
        endToEnd {
            val overlap = "/radio/observeRadio/stream?quality=256&apiKey=key"
            assertTrue(ReverseProxyRouteAllowlist.isAllowed(HttpMethod.Get, "/radio/observeRadio/stream"))

            val handWritten = http.get("$direct/radio/not-a-session/stream?quality=256&apiKey=key")
            assertEquals(HttpStatusCode.BadRequest, handWritten.status)
            assertEquals(HAND_WRITTEN_RADIO_BODY, handWritten.bodyAsText())
            coVerify(exactly = 1) { apiKeyService.resolveUser("key", any()) }

            val refused = http.get("$proxied/radio/not-a-session/stream?quality=256&apiKey=key")
            assertEquals(HttpStatusCode.NotFound, refused.status)
            coVerify(exactly = 1) { apiKeyService.resolveUser(any(), any()) }

            val withoutToken = http.get("$proxied$overlap")
            assertEquals(HttpStatusCode.Unauthorized, withoutToken.status)
            assertEquals(UNAUTHORIZED_BODY, withoutToken.bodyAsText())

            val withToken = http.get("$proxied$overlap") { bearerAuth(token) }
            assertEquals(HttpStatusCode.BadRequest, withToken.status)
            assertEquals("Invalid UUID value: stream", withToken.bodyAsText())

            coVerify(exactly = 1) { apiKeyService.resolveUser(any(), any()) }
            coVerify(exactly = 0) { radio.observeRadio(any()) }
        }

    @Test
    fun `a cors preflight and a cors request carry one allow origin header`() = endToEnd {
        val preflight = exchange(
            proxyPort,
            "OPTIONS /$INSTANCE/hue/bridges HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n" +
                "Access-Control-Request-Method: GET\r\nAccess-Control-Request-Headers: authorization\r\n" +
                "Connection: close\r\n\r\n"
        )
        assertEquals("HTTP/1.1 200 OK", preflight.substringBefore("\r\n"))
        assertEquals(listOf("*"), preflight.headerValues(HttpHeaders.AccessControlAllowOrigin))
        assertTrue(
            preflight.headerValues(HttpHeaders.AccessControlAllowHeaders).single()
                .split(',').any { it.trim().equals(HttpHeaders.Authorization, ignoreCase = true) },
            preflight
        )
        coVerify(exactly = 0) { hue.listBridges() }

        val request = exchange(
            proxyPort,
            "GET /$INSTANCE/hue/bridges HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n" +
                "Authorization: Bearer $token\r\nConnection: close\r\n\r\n"
        )
        assertEquals("HTTP/1.1 200 OK", request.substringBefore("\r\n"))
        assertEquals(listOf("*"), request.headerValues(HttpHeaders.AccessControlAllowOrigin))

        val refused = exchange(
            proxyPort,
            "GET /$INSTANCE/swagger HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n" +
                "Connection: close\r\n\r\n"
        )
        assertEquals("HTTP/1.1 404 Not Found", refused.substringBefore("\r\n"))
        assertEquals(listOf("*"), refused.headerValues(HttpHeaders.AccessControlAllowOrigin))

        val refusedPreflight = exchange(
            proxyPort,
            "OPTIONS /$INSTANCE/swagger HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n" +
                "Access-Control-Request-Method: GET\r\nConnection: close\r\n\r\n"
        )
        assertEquals("HTTP/1.1 404 Not Found", refusedPreflight.substringBefore("\r\n"))
        assertEquals(listOf("*"), refusedPreflight.headerValues(HttpHeaders.AccessControlAllowOrigin))
    }

    @Test
    fun `a body of several megabytes arrives intact`() = endToEnd {
        val payload = pattern(UPLOAD_SIZE)
        val created = UUID.randomUUID()
        val received = CompletableDeferred<ByteArray>()
        coEvery { image.createImage(any(), "custom") } answers {
            received.complete(firstArg())
            created
        }

        assertEquals(created, RestImageService(config()).createImage(payload, "custom"))
        assertArrayEquals(payload, received.await())
    }

    @Test
    fun `an upload without credentials is answered 401 while its body is still being sent`() = endToEnd {
        val response = withTimeout(30.seconds) {
            http.post("$proxied/customAudio/uploadCustomAudio?fileName=a.mp3") {
                setBody(ByteArrayContent(pattern(UPLOAD_SIZE), ContentType.Application.Json))
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(UNAUTHORIZED_BODY, response.bodyAsText())
        coVerify(exactly = 0) { customAudio.uploadCustomAudio(any(), any(), any()) }

        assertEquals(listOf(bridge), withTimeout(10.seconds) { RestHueService(config()).listBridges() })
    }

    @Test
    fun `cancelling the collector of an event stream cancels the flow of the server`() = endToEnd {
        val stopped = CompletableDeferred<Unit>()
        every { podcast.observeProgress() } returns flow {
            emit(progress(1))
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val first = CompletableDeferred<Unit>()

        val collector = launch {
            RestPodcastService(config()).observeProgress().collect { first.complete(Unit) }
        }
        withTimeout(10.seconds) { first.await() }
        assertFalse(stopped.isCompleted)

        collector.cancelAndJoin()
        withTimeout(SseKeepAlivePeriod * 3) { stopped.await() }
    }

    @Test
    fun `a tunnel that drops ends its open stream and serves requests again after reconnecting`() = endToEnd {
        val stopped = CompletableDeferred<Unit>()
        every { podcast.observeProgress() } returns flow {
            emit(progress(1))
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val first = CompletableDeferred<Unit>()

        val outcome = async {
            runCatching { RestPodcastService(config()).observeProgress().collect { first.complete(Unit) } }
        }
        withTimeout(10.seconds) { first.await() }

        service.restartService()

        withTimeout(10.seconds) { outcome.await() }
        withTimeout(SseKeepAlivePeriod * 3) { stopped.await() }
        awaitRegistration(2)
        assertEquals(listOf(bridge), withTimeout(10.seconds) { RestHueService(config()).listBridges() })
    }

    @Test
    fun `a download nobody reads does not hold up other requests on the tunnel`() = endToEnd {
        val largeId = UUID.randomUUID()
        val largeBytes = pattern(LARGE_EPISODE_SIZE)
        files[largeId] = File(directory, "large.mp3").apply { writeBytes(largeBytes) }
        val hueClient = RestHueService(config())

        http.prepareGet("$proxied/podcast/streamEpisode/$largeId") { bearerAuth(token) }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(LARGE_EPISODE_SIZE.toString(), response.headers[HttpHeaders.ContentLength])
            val channel = response.bodyAsChannel()
            val start = channel.readByteArray(1000)
            assertArrayEquals(largeBytes.copyOfRange(0, 1000), start)

            delay(2.seconds)
            repeat(5) {
                val mark = TimeSource.Monotonic.markNow()
                assertEquals(listOf(bridge), withTimeout(3.seconds) { hueClient.listBridges() })
                assertTrue(mark.elapsedNow() < 2.seconds, "a json call took ${mark.elapsedNow()}")
            }

            val rest = channel.toByteArray()
            assertEquals(LARGE_EPISODE_SIZE - 1000, rest.size)
            assertArrayEquals(largeBytes, start + rest)
        }
    }

    @Test
    fun `an instance that is not registered is answered 503`() = endToEnd {
        val plain = http.get("http://127.0.0.1:$proxyPort/missing/hue/bridges") { bearerAuth(token) }
        assertEquals(HttpStatusCode.ServiceUnavailable, plain.status)
        assertNull(plain.headers[HttpHeaders.AccessControlAllowOrigin])

        val browser = exchange(
            proxyPort,
            "GET /missing/hue/bridges HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: https://app.example\r\n" +
                "Connection: close\r\n\r\n"
        )
        assertEquals("HTTP/1.1 503 Service Unavailable", browser.substringBefore("\r\n"))
        assertEquals(listOf("*"), browser.headerValues(HttpHeaders.AccessControlAllowOrigin))
        assertEquals(emptyList<String?>(), authorizations.toList())
    }

    @Test
    fun `requests whose head cannot be carried are answered and leave the tunnel working`() = endToEnd {
        val rest = "/$INSTANCE/hue/bridges HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
            "Connection: close\r\n"
        val latin = 0xE9.toChar()
        val control = 0x01.toChar()
        val delete = 0x7F.toChar()

        val plain = exchange(proxyPort, "GET $rest$NOTE_HEADER: $ASCII_NOTE\r\n\r\n")
        assertEquals("200", plain.statusCode())
        assertEquals(listOf(ASCII_NOTE), notes.toList())

        val requests = listOf(
            "an obs-text header value" to "GET $rest$NOTE_HEADER: caf$latin\r\n\r\n",
            "a control character in a header value" to "GET $rest$NOTE_HEADER: a${control}b\r\n\r\n",
            "a delete character in a header value" to "GET $rest$NOTE_HEADER: a${delete}b\r\n\r\n",
            "a delimiter in a header name" to "GET ${rest}X-No(te: x\r\n\r\n",
            "a non-ASCII header name" to "GET ${rest}X-Not$latin: x\r\n\r\n",
            "a method longer than the frame allows" to "${"A".repeat(300)} $rest\r\n",
            "a method of the longest length the frame allows" to "${"A".repeat(255)} $rest\r\n",
            "a non-ASCII method" to "G${0xC9.toChar()}T $rest\r\n",
        )
        val statuses = requests.associate { (name, request) -> name to exchange(proxyPort, request).statusCode() }

        assertEquals(
            mapOf(
                "an obs-text header value" to "200",
                "a control character in a header value" to "400",
                "a delete character in a header value" to "400",
                "a delimiter in a header name" to "400",
                "a non-ASCII header name" to "400",
                "a method longer than the frame allows" to "400",
                "a method of the longest length the frame allows" to "404",
                "a non-ASCII method" to "400",
            ),
            statuses
        )
        assertEquals(listOf(ASCII_NOTE, "caf\u00C3\u00A9"), notes.toList())
        assertEquals(listOf(bridge), withTimeout(10.seconds) { RestHueService(config()).listBridges() })
    }

    @Test
    fun `request header values reach the server with the bytes the client sent`() = endToEnd {
        suspend fun seen(port: Int, prefix: String, value: String): List<String> {
            notes.clear()
            val answer = exchange(
                port,
                "GET $prefix/hue/bridges HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer $token\r\n" +
                    "Connection: close\r\n$NOTE_HEADER: $value\r\n\r\n"
            )
            assertEquals("200", answer.statusCode(), value)
            return notes.toList()
        }

        val exact = listOf(
            ASCII_NOTE,
            "caf\u00C3\u00A9",
            "\u00E4\u00B8\u00AD \u00C5\u0082",
            "\u00F0\u009F\u0098\u0080!",
            "\u00C2\u0080\u00DF\u00BF",
        )
        for (value in exact) {
            assertEquals(listOf(value), seen(targetPort, "", value), value)
            assertEquals(listOf(value), seen(proxyPort, "/$INSTANCE", value), value)
        }

        val mangled = mapOf(
            "caf\u00E9" to "caf\u00C3\u00A9",
            "\u00E9 \u00C3\u00A9" to "\u00C3\u00A9 \u00C3\u0083\u00C2\u00A9",
            "\u00C0\u00AF" to "\u00C3\u0080\u00C2\u00AF",
            "\u00ED\u00A0\u0080" to "\u00C3\u00AD\u00C2\u00A0\u00C2\u0080",
        )
        for ((value, arrived) in mangled) {
            assertEquals(listOf(value), seen(targetPort, "", value), value)
            assertEquals(listOf(arrived), seen(proxyPort, "/$INSTANCE", value), value)
        }
    }

    @Test
    fun `the file name header of a file route is ASCII, has the bytes of direct access and carries the exact name`() = endToEnd {
        suspend fun disposition(port: Int, prefix: String, name: String): String {
            val id = UUID.randomUUID()
            files[id] = File(directory, "$id.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            fileNames[id] = name
            val answer = exchange(
                port,
                "GET $prefix/podcast/streamEpisode/$id HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Authorization: Bearer $token\r\nConnection: close\r\n\r\n"
            )
            assertEquals("200", answer.statusCode(), name)
            assertEquals("\u0001\u0002\u0003", answer.substringAfter("\r\n\r\n"), name)
            return answer.headerValues(HttpHeaders.ContentDisposition).single()
        }

        val lines = mapOf(
            "episode.mp3" to "inline; filename=episode.mp3",
            "s\u00E4nger.mp3" to "inline; filename=s_nger.mp3; filename*=utf-8''s%C3%A4nger.mp3",
            "\u0142\u00F3d\u017A.mp3" to "inline; filename=__d_.mp3; filename*=utf-8''%C5%82%C3%B3d%C5%BA.mp3",
            "\u0142\u4E2D.mp3" to "inline; filename=__.mp3; filename*=utf-8''%C5%82%E4%B8%AD.mp3",
            "\u00C3\u00A4.mp3" to "inline; filename=__.mp3; filename*=utf-8''%C3%83%C2%A4.mp3",
            "\u00E4\u00B8\u00AD\u00F0\u009F\u0098\u0080.mp3" to
                "inline; filename=_______.mp3; filename*=utf-8''%C3%A4%C2%B8%C2%AD%C3%B0%C2%9F%C2%98%C2%80.mp3",
        )
        for ((name, line) in lines) {
            val direct = disposition(targetPort, "", name)
            val proxied = disposition(proxyPort, "/$INSTANCE", name)
            assertEquals(line, direct, name)
            assertEquals(direct, proxied, name)
            assertTrue(proxied.all { it.code < 0x80 }, name)
            val parsed = ContentDisposition.parse(proxied)
            val sent = parsed.parameter(ContentDisposition.Parameters.FileNameAsterisk)
                ?.removePrefix("utf-8''")?.decodeURLPart()
                ?: parsed.parameter(ContentDisposition.Parameters.FileName)
            assertEquals(name, sent, name)
        }
    }

    private companion object {
        const val INSTANCE = "e2e"
        const val PROXY_KEY = "end-to-end-key"
        const val USERNAME = "listener"
        const val PASSWORD = "correct horse"
        const val PATH_TEXT = "a b/c?d"
        const val NOTE_HEADER = "X-Note"
        const val ASCII_NOTE = "a!#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~ z"
        const val UNAUTHORIZED_BODY = "Token is not valid or has expired"
        const val HAND_WRITTEN_RADIO_BODY = "invalid session id"
        const val EPISODE_SIZE = 1_048_576 + 4321
        const val FILE_OFFSET = 300_001
        const val LARGE_EPISODE_SIZE = 24 * 1_048_576 + 17
        const val UPLOAD_SIZE = 3 * 1_048_576 + 5
    }
}
