package dev.dertyp.services.ui.credentialserver

import dev.dertyp.config.ImportersConfig
import dev.dertyp.config.ProxyConfig
import dev.dertyp.config.ServerConfig
import dev.dertyp.config.TiddlAuthConfig
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.ClientInfo
import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.credentials.*
import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.IImporter
import dev.dertyp.plugins.PluginManager
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.credentials.*
import dev.dertyp.services.import.TdnService
import dev.dertyp.services.import.TiddlService
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_ADMIN_KEY
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_CLIENT_ID
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_CLIENT_SECRET
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_URL
import dev.dertyp.services.credentials.admin.CredentialServerAdminClient
import dev.dertyp.services.credentials.remote.CredentialServerClient
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import dev.dertyp.services.metadata.AcoustIdCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.ServerUiRenderScope
import dev.dertyp.services.ui.TranslationService
import dev.dertyp.services.ui.UiService
import dev.dertyp.services.ui.UiRegistry
import dev.dertyp.ui.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Instant
import java.time.Instant as JavaInstant

class CredentialServerContributionsTest {
    private val json = CredentialJson.json
    private val registry = UiRegistry()
    private val translations = TranslationService(registry)
    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))
    private val connectionSettings = InMemoryPluginSettings()
    private val localSettings = InMemoryPluginSettings()
    private val serverSettings = InMemoryPluginSettings()
    private val podcastIndexSettings = InMemoryPluginSettings()
    private val settingsService = mockk<PluginSettingsService>(relaxed = true) {
        every { forPlugin(CredentialServerConnectionSource.PLUGIN_ID) } returns connectionSettings
        every { forPlugin(LocalCredentialStore.PLUGIN_ID) } returns localSettings
        every { forPlugin(AcoustIdCredentialSource.PLUGIN_ID) } returns serverSettings
        every { forPlugin(PodcastIndexCredentialSource.PLUGIN_ID) } returns podcastIndexSettings
    }
    private val environment = MapApplicationConfig(
        "youtube.apiKey" to "env-youtube",
        "linkresolver.apiKey" to "env-linkresolver",
    )
    private val connection = CredentialServerConnectionSource(settingsService, MapApplicationConfig(), cipher)
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val clients = CopyOnWriteArrayList<ClientSummary>()
    private var healthy = true
    private var loginEvents = emptyList<TidalLoginEvent>()
    private val presets = CopyOnWriteArrayList<CredentialPreset>()
    private val credentials = CopyOnWriteArrayList(
        listOf(
            credential(CredentialNames.TIDAL_API, CredentialKind.OAUTH_CLIENT_CREDENTIALS),
            credential(CredentialNames.IMPORTER_TIDDL, CredentialKind.TIDAL_DEVICE_SESSION),
            credential(CredentialNames.plugin("demo", "token"), CredentialKind.API_KEY),
        ),
    )
    private val admin = User(UUID.randomUUID(), "root", displayName = "Root", passwordHash = "", isAdmin = true)
    private val member = User(UUID.randomUUID(), "member", displayName = "Member", passwordHash = "", isAdmin = false)

    private var consumerGrants = emptyList<GrantInfo>()
    private val factory = httpClientFactory()
    private val adminClient = CredentialServerAdminClient(connection, factory)
    private val localStore = LocalCredentialStore(
        settingsService,
        environment,
        cipher,
        AcoustIdCredentialSource(settingsService, environment, cipher)
    )
    private val local = LocalCredentialProvider(
        ClientCredentialsExchange(mockk(relaxed = true)),
        AppleDeveloperTokenSigner(),
        localStore,
        LocalPluginCredentialStore(settingsService, cipher)
    )
    private val remoteNames = mutableSetOf<String>()
    private val provider = PartlyRemoteProvider(local, remoteNames)
    private var tiddlAuth: TiddlAuthConfig? = null
    private val importersConfig = mockk<ImportersConfig> {
        every { tiddlAuth } answers { this@CredentialServerContributionsTest.tiddlAuth }
    }
    private val serverConfig = mockk<ServerConfig> {
        every { importers } returns importersConfig
        every { proxy } returns ProxyConfig(
            hostname = null,
            controlPort = null,
            ssl = false,
            id = null,
            name = "Home",
            key = null
        )
    }
    private val importers = CopyOnWriteArrayList<IImporter>()
    private val pluginManager = mockk<PluginManager> {
        every { getAllImporters() } answers { importers.toList() }
    }
    private var rejectUpsert = false
    private var virtualMillis = 1_000_000L
    private val consumerClient = CredentialServerClient(factory, connection)
    private val remote = RemoteCredentialProvider(consumerClient, connection, adminClient)
    private val ui = context(provider)

    private fun context(credentialProvider: CredentialProvider) = CredentialServerUiContext(
        admin = adminClient,
        connection = connection,
        provider = credentialProvider,
        remote = remote,
        localStore = localStore,
        serverConfig = serverConfig,
        pluginManager = pluginManager,
        translations = translations,
    )

    @TempDir
    lateinit var tempDir: Path
    private val contributions = ui.contributions()

    @AfterEach
    fun tearDown() {
        unmockkObject(Clock.System)
    }

    init {
        mockkObject(Clock.System)
        every { Clock.System.now() } answers { Instant.fromEpochMilliseconds(virtualMillis) }
        translations.forSource(CREDENTIAL_SERVER_UI_SOURCE)
            .registerBundlesFromResources(javaClass.classLoader, "i18n/credentialserver", listOf("en", "de"))
    }

    class PartlyRemoteProvider(private val local: LocalCredentialProvider, private val remote: Set<String>) :
        CredentialProvider {
        override val mode: CredentialMode get() = if (remote.isEmpty()) CredentialMode.LOCAL else CredentialMode.REMOTE

        override fun isAvailable(name: String): Boolean = name in remote || local.isAvailable(name)

        override fun isManagedRemotely(name: String): Boolean = name in remote

        override suspend fun resolve(name: String): ResolvedCredential? =
            if (name in remote) ResolvedCredential.ApiKey(name, "remote") else local.resolve(name)

        override suspend fun writeBack(
            name: String,
            expectedFingerprint: String?,
            files: List<CredentialFile>
        ): Boolean = false

        override fun changes(): Flow<Unit> = local.changes()
    }

    private fun credential(name: String, kind: CredentialKind) =
        CredentialSummary(name, kind, null, CredentialStatus.OK, null, null, 0, emptyList())

    private fun httpClientFactory(): HttpClientFactory {
        val engine = MockEngine { request ->
            requests += request
            handle(request)
        }
        val http = HttpClient(engine) { install(HttpTimeout) }
        val factory = mockk<HttpClientFactory>()
        every {
            factory.shared<HttpClientEngineConfig>(
                HttpClientFactory.CREDENTIAL_SERVER_ADMIN,
                any(),
                any(),
                any()
            )
        } returns http
        every {
            factory.shared<HttpClientEngineConfig>(
                HttpClientFactory.CREDENTIAL_SERVER,
                any(),
                any(),
                any()
            )
        } returns http
        return factory
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData) = when {
        request.url.encodedPath == CredentialProtocol.TOKEN_PATH -> {
            val body = json.decodeFromString<TokenRequest>((request.body as TextContent).text)
            if (body.clientId == CONSUMER_ID && body.clientSecret == CONSUMER_SECRET) {
                ok(
                    json.encodeToString(
                        TokenResponse(
                            "consumer-token",
                            expiresAt = System.currentTimeMillis() + 15 * 60_000L,
                            grants = consumerGrants
                        )
                    )
                )
            } else {
                respond(
                    json.encodeToString(CredentialError(CredentialErrorCode.UNAUTHORIZED, "bad client")),
                    HttpStatusCode.Unauthorized
                )
            }
        }

        request.url.encodedPath == CredentialProtocol.credentialPath(CredentialNames.PODCAST_INDEX_API) ->
            if (consumerGrants.any { it.name == CredentialNames.PODCAST_INDEX_API }) {
                ok(
                    json.encodeToString<ResolvedCredential>(
                        ResolvedCredential.ApiKeyPair(
                            CredentialNames.PODCAST_INDEX_API,
                            "remote-key",
                            "remote-secret"
                        )
                    )
                )
            } else {
                respond(
                    json.encodeToString(CredentialError(CredentialErrorCode.NOT_GRANTED, "not granted")),
                    HttpStatusCode.Forbidden
                )
            }

        request.url.encodedPath == "/health" ->
            if (healthy) ok(
                json.encodeToString(
                    CredentialServerHealth(
                        true,
                        CredentialProtocol.PROTOCOL_VERSION,
                        "1.2.3"
                    )
                )
            )
            else respond("down", HttpStatusCode.ServiceUnavailable)

        request.headers[CredentialProtocol.ADMIN_KEY_HEADER] != ADMIN_KEY ->
            respond(
                json.encodeToString(CredentialError(CredentialErrorCode.UNAUTHORIZED, "bad key")),
                HttpStatusCode.Unauthorized
            )

        request.url.encodedPath == "/admin/clients" && request.method == HttpMethod.Post -> {
            val body = json.decodeFromString<CreateClientRequest>((request.body as TextContent).text)
            val created = ClientSummary(
                id = "c${clients.size + 1}",
                clientId = "client-${clients.size + 1}",
                name = body.name,
                enabled = true,
                tokenVersion = 1,
                createdAt = 0,
                lastTokenAt = null,
                grants = body.grants.map { spec ->
                    GrantInfo(
                        spec.name,
                        credentials.first { it.name == spec.name }.kind,
                        spec.writeBack
                    )
                },
            )
            clients += created
            ok(json.encodeToString(CreatedClient(created, SECRET)))
        }

        request.url.encodedPath == "/admin/clients" -> ok(json.encodeToString(clients.toList()))
        request.url.encodedPath.endsWith("/rotate-secret") -> {
            val id = request.url.encodedPath.removePrefix("/admin/clients/").removeSuffix("/rotate-secret")
            ok(json.encodeToString(CreatedClient(clients.first { it.id == id }, ROTATED_SECRET)))
        }

        request.url.encodedPath.startsWith("/admin/clients/") -> {
            val id = request.url.encodedPath.removePrefix("/admin/clients/")
            clients.firstOrNull { it.id == id }?.let { ok(json.encodeToString(it)) }
                ?: respond(
                    json.encodeToString(CredentialError(CredentialErrorCode.NOT_FOUND, "missing")),
                    HttpStatusCode.NotFound
                )
        }

        request.url.encodedPath == "/admin/credentials/${CredentialNames.IMPORTER_TIDDL}/tidal-login" && request.method == HttpMethod.Post ->
            ok(json.encodeToString(TidalLoginSession("login1", "link.tidal.com", null, "ABCD", 0)))

        request.url.encodedPath == "/admin/tidal-logins/login1/events" ->
            respond(
                loginEvents.joinToString("\n", postfix = "\n") { json.encodeToString(it) },
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/x-ndjson"),
            )

        request.url.encodedPath == "/admin/credentials" -> ok(json.encodeToString(credentials.toList()))
        request.url.encodedPath.startsWith("/admin/credentials/") && request.method == HttpMethod.Put ->
            if (rejectUpsert) {
                respond(
                    json.encodeToString(
                        CredentialError(
                            CredentialErrorCode.INVALID,
                            "Tidal client id and secret are required"
                        )
                    ), HttpStatusCode.BadRequest
                )
            } else {
                ok(
                    json.encodeToString(
                        credential(
                            request.url.encodedPath.removePrefix("/admin/credentials/"),
                            CredentialKind.TIDAL_DEVICE_SESSION
                        )
                    )
                )
            }

        request.url.encodedPath.startsWith("/admin/credentials/") -> {
            val name = request.url.encodedPath.removePrefix("/admin/credentials/")
            credentials.firstOrNull { it.name == name }?.let { ok(json.encodeToString(it)) }
                ?: respond(
                    json.encodeToString(CredentialError(CredentialErrorCode.NOT_FOUND, "missing")),
                    HttpStatusCode.NotFound
                )
        }

        request.url.encodedPath == "/admin/presets" -> ok(json.encodeToString(presets.toList()))
        else -> respond("", HttpStatusCode.NotFound)
    }

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))

    private fun connect(adminKey: String = ADMIN_KEY) = runBlocking {
        connection.store(mapOf(KEY_URL to "https://creds.example.com", KEY_ADMIN_KEY to adminKey))
    }

    private fun scope(user: User = admin, params: Map<String, String> = emptyMap(), locale: String = "en") =
        UiRenderScope(
            user = UserInfo.fromUser(user),
            context = UiContext(params = params),
            i18n = translations.translator(CREDENTIAL_SERVER_UI_SOURCE, locale),
            settings = connectionSettings,
            clientSchemaVersion = UiSchemaVersion.CURRENT,
        )

    private fun localScope(name: String) = scope(params = mapOf(CredentialServerPages.PARAM_NAME to name))

    private fun contribution(id: String): UiContribution = contributions.single { it.id == id }

    private fun UiComponent.encoded(): String = ApplicationScope.json.encodeToString(UiComponent.serializer(), this)

    private fun UiComponent.flatten(): List<UiComponent> = listOf(this) + when (this) {
        is UiComponent.Column -> children.flatMap { it.flatten() }
        is UiComponent.Row -> children.flatMap { it.flatten() }
        is UiComponent.Section -> children.flatMap { it.flatten() }
        is UiComponent.Card -> (children + actions).flatMap { it.flatten() }
        is UiComponent.Form -> (children + actions).flatMap { it.flatten() }
        else -> emptyList()
    }

    private suspend fun overview(): UiComponent = contribution(CredentialServerPages.OVERVIEW).render(scope())

    private suspend fun localPage(name: String): UiComponent =
        contribution(CredentialServerPages.LOCAL).render(localScope(name))

    private fun UiComponent.opens(pageId: String): Boolean =
        this is UiComponent.ListItem && (action as? UiAction.OpenPage)?.pageId == pageId

    private fun UiComponent.invokes(actionId: String): Boolean =
        this is UiComponent.Button && (action as? UiAction.Invoke)?.actionId == actionId

    private suspend fun rows(): Map<String, String?> =
        overview().flatten().filter { it.opens(CredentialServerPages.LOCAL) }.filterIsInstance<UiComponent.ListItem>()
            .associate { item ->
                (item.action as UiAction.OpenPage).params.getValue(CredentialServerPages.PARAM_NAME) to item.trailing
            }

    private suspend fun invokeLocal(name: String, action: String, values: Map<String, String> = emptyMap()) =
        contribution(CredentialServerPages.LOCAL).invoke(
            localScope(name),
            action,
            values.mapValues { UiValue.of(it.value) })

    @Test
    fun `the central settings entry is an admin list item opening the credentials page`() = runBlocking {
        val entry = contribution(CredentialServerPages.ENTRY)
        assertEquals(UiContributionKind.SLOT, entry.kind)
        assertEquals(UiSlots.SETTINGS, entry.slot)
        assertEquals(UiIcon(UiIconName.KEY), entry.icon)
        assertTrue(entry.access.requiresAdmin)

        val item = entry.render(scope()) as UiComponent.ListItem
        assertEquals("Credentials", item.title)
        assertEquals(UiAction.OpenPage(CredentialServerPages.OVERVIEW), item.action)
        assertEquals("3 of 9 configured", item.trailing)
        assertEquals(UiContributionKind.PAGE, contribution(CredentialServerPages.OVERVIEW).kind)
        assertEquals(1, contributions.count { it.kind == UiContributionKind.SLOT })
    }

    @Test
    fun `every contribution is admin only and denied to other users`() = runBlocking {
        assertEquals(7, contributions.size)
        assertTrue(contributions.all { it.access.requiresAdmin })
        assertTrue(contributions.none { it.access.allows(UserInfo.fromUser(member)) })

        val registrar = registry.forSource(CREDENTIAL_SERVER_UI_SOURCE)
        contributions.forEach { registrar.register(it) }
        val service = UiService(registry, translations, settingsService, mockk(relaxed = true), mockk(relaxed = true))
        val client = ClientInfo(apiVersion = 99, uiSchemaVersion = UiSchemaVersion.CURRENT)

        assertThrows<UnauthorizedException> {
            service.render(
                member,
                client,
                CredentialServerPages.OVERVIEW,
                UiContext()
            )
        }
        assertThrows<UnauthorizedException> {
            service.invoke(
                member,
                client,
                CredentialServerPages.SERVER,
                "register",
                UiInvokePayload()
            )
        }
        assertThrows<UnauthorizedException> {
            service.render(
                member,
                client,
                CredentialServerPages.SERVER,
                UiContext()
            )
        }
        assertThrows<UnauthorizedException> {
            service.render(
                member,
                client,
                CredentialServerPages.CLIENTS,
                UiContext()
            )
        }
        assertThrows<UnauthorizedException> {
            service.invoke(
                member,
                client,
                CredentialServerPages.CLIENTS,
                "createClient",
                UiInvokePayload()
            )
        }
        assertThrows<UnauthorizedException> {
            service.invoke(member, client, CredentialServerPages.LOCAL, "save", UiInvokePayload())
        }
        assertTrue(
            service.renderSlot(
                member,
                client,
                UiSlots.SETTINGS,
                UiContext()
            ).items.none { it.contributionId == CredentialServerPages.ENTRY })
        assertTrue(
            service.renderSlot(
                admin,
                client,
                UiSlots.SETTINGS,
                UiContext()
            ).items.any { it.contributionId == CredentialServerPages.ENTRY })
    }

    @Test
    fun `every local credential is listed with its origin`() = runBlocking {
        localStore.store(CredentialNames.ACOUSTID_API, mapOf(LocalCredentialStore.FIELD_API_KEY to "stored-acoustid"))
        remoteNames += CredentialNames.TIDAL_API

        val rows = rows()

        assertEquals(
            listOf(
                CredentialNames.ACOUSTID_API,
                CredentialNames.PODCAST_INDEX_API,
                CredentialNames.THEAUDIODB_API,
                CredentialNames.YOUTUBE_API,
                CredentialNames.LINKRESOLVER_API,
                CredentialNames.IMAGE_CACHE_TOKEN,
                CredentialNames.TIDAL_API,
                CredentialNames.SPOTIFY_API,
                CredentialNames.APPLE_MUSIC_DEVELOPER,
            ),
            rows.keys.toList(),
        )
        assertEquals("Stored here", rows[CredentialNames.ACOUSTID_API])
        assertEquals("Environment", rows[CredentialNames.YOUTUBE_API])
        assertEquals("Not configured", rows[CredentialNames.PODCAST_INDEX_API])
        assertEquals("Public default key", rows[CredentialNames.THEAUDIODB_API])
        assertEquals("Managed by the credential server", rows[CredentialNames.TIDAL_API])
        assertEquals("Not configured", rows[CredentialNames.APPLE_MUSIC_DEVELOPER])
    }

    @Test
    fun `remote rows show a badge instead of a form`() = runBlocking {
        remoteNames += CredentialNames.TIDAL_API

        val page = localPage(CredentialNames.TIDAL_API).flatten()

        assertTrue(page.none { it is UiComponent.Form })
        assertTrue(page.filterIsInstance<UiComponent.Badge>().any { it.text == "Managed by the credential server" })
        val save = invokeLocal(CredentialNames.TIDAL_API, "save", mapOf("clientId" to "id", "clientSecret" to "secret"))
        assertEquals(UiInvokeStatus.ERROR, save.status)
        assertNull(localStore.stored(CredentialNames.TIDAL_API))
        assertEquals(UiInvokeStatus.OK, invokeLocal(CredentialNames.TIDAL_API, "test").status)
    }

    @Test
    fun `saving stores encrypted values that resolve right away and blank secrets keep them`() = runBlocking {
        val form = localPage(CredentialNames.LINKRESOLVER_API).flatten().filterIsInstance<UiComponent.Form>().single()
        assertEquals(listOf("apiKey"), form.children.filterIsInstance<UiComponent.TextField>().map { it.key })

        val saved = invokeLocal(CredentialNames.LINKRESOLVER_API, "save", mapOf("apiKey" to " stored-linkresolver "))

        assertEquals(UiInvokeStatus.OK, saved.status)
        assertTrue(saved.refresh)
        val raw = localSettings.getAll().getValue("linkresolver.api.apiKey")
        assertTrue(raw.startsWith(CredentialCipher.PREFIX))
        assertFalse(raw.contains("stored-linkresolver"))
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "stored-linkresolver"),
            provider.resolve(CredentialNames.LINKRESOLVER_API)
        )
        assertEquals("Stored here", rows()[CredentialNames.LINKRESOLVER_API])

        val field =
            localPage(CredentialNames.LINKRESOLVER_API).flatten().filterIsInstance<UiComponent.TextField>().single()
        assertTrue(field.secret)
        assertNull(field.value)
        assertEquals("A value is stored. Leave this blank to keep it.", field.helper)
        assertFalse(localPage(CredentialNames.LINKRESOLVER_API).encoded().contains("stored-linkresolver"))

        assertEquals(
            UiInvokeStatus.OK,
            invokeLocal(CredentialNames.LINKRESOLVER_API, "save", mapOf("apiKey" to "  ")).status
        )
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "stored-linkresolver"),
            provider.resolve(CredentialNames.LINKRESOLVER_API)
        )
    }

    @Test
    fun `a first save needs every field`() = runBlocking {
        val result = invokeLocal(CredentialNames.SPOTIFY_API, "save", mapOf("clientId" to "id"))

        assertEquals(UiInvokeStatus.VALIDATION_ERROR, result.status)
        assertEquals(setOf("clientSecret"), result.fieldErrors.keys)
        assertTrue(localSettings.getAll().isEmpty())
    }

    @Test
    fun `clearing goes back to the environment`() = runBlocking {
        invokeLocal(CredentialNames.YOUTUBE_API, "save", mapOf("apiKey" to "stored-youtube"))
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "stored-youtube"),
            provider.resolve(CredentialNames.YOUTUBE_API)
        )
        assertTrue(
            localPage(CredentialNames.YOUTUBE_API).flatten()
                .any { it.invokes(LocalCredentialContribution.ACTION_CLEAR) })

        val cleared = invokeLocal(CredentialNames.YOUTUBE_API, "clear")

        assertEquals(UiInvokeStatus.OK, cleared.status)
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "env-youtube"),
            provider.resolve(CredentialNames.YOUTUBE_API)
        )
        assertEquals("Environment", rows()[CredentialNames.YOUTUBE_API])
        assertTrue(
            localPage(CredentialNames.YOUTUBE_API).flatten()
                .none { it.invokes(LocalCredentialContribution.ACTION_CLEAR) })
    }

    @Test
    fun `acoustid and podcast index values stored under the old keys are still read and written`() = runBlocking {
        serverSettings.set(
            AcoustIdCredentialSource.KEY_API_KEY,
            cipher.encrypt(AcoustIdCredentialSource.KEY_API_KEY, "old-acoustid")
        )
        podcastIndexSettings.setAll(
            mapOf(
                PodcastIndexCredentialSource.KEY_API_KEY to cipher.encrypt(
                    PodcastIndexCredentialSource.KEY_API_KEY,
                    "old-key"
                ),
                PodcastIndexCredentialSource.KEY_API_SECRET to cipher.encrypt(
                    PodcastIndexCredentialSource.KEY_API_SECRET,
                    "old-secret"
                ),
            ),
        )

        val rows = rows()
        assertEquals("Stored here", rows[CredentialNames.ACOUSTID_API])
        assertEquals("Stored here", rows[CredentialNames.PODCAST_INDEX_API])
        assertEquals(
            ResolvedCredential.ApiKey(CredentialNames.ACOUSTID_API, "old-acoustid"),
            provider.resolve(CredentialNames.ACOUSTID_API)
        )
        assertEquals(
            ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "old-key", "old-secret"),
            provider.resolve(CredentialNames.PODCAST_INDEX_API)
        )

        invokeLocal(CredentialNames.PODCAST_INDEX_API, "save", mapOf("apiSecret" to "new-secret"))
        assertEquals(
            "new-secret",
            cipher.decrypt(
                PodcastIndexCredentialSource.KEY_API_SECRET,
                podcastIndexSettings.getAll().getValue(PodcastIndexCredentialSource.KEY_API_SECRET)
            )
        )
        assertEquals(
            ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "old-key", "new-secret"),
            provider.resolve(CredentialNames.PODCAST_INDEX_API)
        )
        assertTrue(localSettings.getAll().isEmpty())
    }

    @Test
    fun `the apple private key is validated on save and used for signing`() = runBlocking {
        val p8 = localPage(CredentialNames.APPLE_MUSIC_DEVELOPER).flatten().filterIsInstance<UiComponent.FileField>()
            .single()
        assertEquals("p8", p8.key)
        assertEquals(listOf(".p8"), p8.accept)
        assertTrue(p8.secret)
        assertFalse(p8.binary)

        val invalid = invokeLocal(
            CredentialNames.APPLE_MUSIC_DEVELOPER,
            "save",
            mapOf("teamId" to "TEAM", "keyId" to "KEY", "p8" to "not a key")
        )
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, invalid.status)
        assertEquals(setOf("p8"), invalid.fieldErrors.keys)
        assertNull(localStore.stored(CredentialNames.APPLE_MUSIC_DEVELOPER))

        val saved = invokeLocal(
            CredentialNames.APPLE_MUSIC_DEVELOPER,
            "save",
            mapOf("teamId" to "TEAM", "keyId" to "KEY", "p8" to AppleTestKeys.pem())
        )
        assertEquals(UiInvokeStatus.OK, saved.status)
        assertTrue(provider.isAvailable(CredentialNames.APPLE_MUSIC_DEVELOPER))

        val tested = invokeLocal(CredentialNames.APPLE_MUSIC_DEVELOPER, "test")
        assertEquals(UiInvokeStatus.OK, tested.status)
        assertTrue(tested.message.orEmpty().startsWith("The credential works. Expires "))
        val token = provider.resolve(CredentialNames.APPLE_MUSIC_DEVELOPER) as ResolvedCredential.DeveloperToken
        assertFalse(tested.message.orEmpty().contains(token.token))
    }

    @Test
    fun `admin sections stay hidden without an admin key the credential server accepts`() = runBlocking {
        val adminForms = listOf(
            CredentialServerServerContribution.FORM_REGISTER,
            CredentialServerServerContribution.FORM_CREATE_CREDENTIAL
        )

        fun formIds(component: UiComponent) = component.flatten().filterIsInstance<UiComponent.Form>().map { it.id }
        val server = contribution(CredentialServerPages.SERVER)
        assertTrue(formIds(server.render(scope())).none { it in adminForms })
        assertTrue(overview().flatten().any { it.opens(CredentialServerPages.SERVER) })
        assertTrue(formIds(server.render(scope())).contains(CredentialServerServerContribution.FORM_CONNECTION))

        connect(adminKey = "wrong-key")
        val rejected = server.render(scope())
        assertTrue(formIds(rejected).none { it in adminForms })
        assertTrue(overview().flatten().none { it.opens(CredentialServerPages.CLIENTS) })
        assertTrue(
            rejected.flatten().filterIsInstance<UiComponent.Text>().any {
                it.text == translations.resolve(
                    CREDENTIAL_SERVER_UI_SOURCE,
                    "en",
                    "credentialserver.adminKeyRejected"
                )
            })
        assertTrue(contribution(CredentialServerPages.CLIENTS).render(scope()) is UiComponent.EmptyState)
        assertTrue(contribution(CredentialServerPages.CLIENT).render(scope(params = mapOf("id" to "c1"))) is UiComponent.EmptyState)
        assertTrue(contribution(CredentialServerPages.CREDENTIAL).render(scope(params = mapOf("name" to CredentialNames.TIDAL_API))) is UiComponent.EmptyState)

        connect()
        val accepted = server.render(scope())
        assertTrue(formIds(accepted).containsAll(adminForms))
        assertTrue(overview().flatten().any { it.opens(CredentialServerPages.CLIENTS) })
        assertTrue(
            formIds(contribution(CredentialServerPages.CLIENTS).render(scope())).contains(
                CredentialServerClientsContribution.FORM_CREATE_CLIENT
            )
        )
        assertFalse(contribution(CredentialServerPages.CREDENTIAL).render(scope(params = mapOf("name" to CredentialNames.TIDAL_API))) is UiComponent.EmptyState)
    }

    @Test
    fun `the admin key probe is cached briefly`() = runBlocking {
        connect()
        assertTrue(ui.isAdmin())
        assertTrue(ui.isAdmin())
        assertEquals(1, requests.count { it.url.encodedPath == "/admin/clients" })
    }

    @Test
    fun `the credential page offers file pickers for key files`() = runBlocking {
        connect()
        val page =
            contribution(CredentialServerPages.CREDENTIAL).render(scope(params = mapOf("name" to CredentialNames.IMPORTER_TIDDL)))
        val authFile = page.flatten().filterIsInstance<UiComponent.FileField>().single()
        assertEquals("authFile", authFile.key)
        assertEquals(listOf(".json"), authFile.accept)
        assertFalse(authFile.binary)
        assertTrue(page.flatten().filterIsInstance<UiComponent.TextField>().none { it.multiline })
    }

    @Test
    fun `register this server stores the returned secret and never renders it`() = runBlocking {
        connect()
        val server = contribution(CredentialServerPages.SERVER)

        val result = server.invoke(scope(), "register", mapOf("serverName" to UiValue.of("Home")))

        assertEquals(UiInvokeStatus.OK, result.status)
        assertFalse(result.message.orEmpty().contains(SECRET))
        val request = requests.single { it.method == HttpMethod.Post && it.url.encodedPath == "/admin/clients" }
        val body = json.decodeFromString<CreateClientRequest>((request.body as TextContent).text)
        assertEquals("Home", body.name)
        assertEquals(
            listOf(
                GrantSpec(CredentialNames.TIDAL_API, writeBack = false),
                GrantSpec(CredentialNames.IMPORTER_TIDDL, writeBack = true)
            ),
            body.grants,
        )
        val stored = requireNotNull(connection.current())
        assertEquals("client-1", stored.clientId)
        assertEquals(SECRET, stored.clientSecret)
        assertTrue(connectionSettings.getAll().values.none { it.contains(SECRET) })

        assertFalse(server.render(scope()).encoded().contains(SECRET))
        assertFalse(overview().encoded().contains(SECRET))
        assertFalse(contribution(CredentialServerPages.CLIENTS).render(scope()).encoded().contains(SECRET))
        assertFalse(
            contribution(CredentialServerPages.CLIENT).render(scope(params = mapOf("id" to "c1"))).encoded()
                .contains(SECRET)
        )
    }

    @Test
    fun `a created client secret stays visible across renders on its page`() = runBlocking {
        connect()
        val result = contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )
        assertEquals(UiAction.OpenPage(CredentialServerPages.CLIENT, mapOf("id" to "c1")), result.next)
        assertFalse(result.message.orEmpty().contains(SECRET))

        val page = contribution(CredentialServerPages.CLIENT)
        assertTrue(page.render(scope(params = mapOf("id" to "c1"))).encoded().contains(SECRET))
        assertTrue(page.render(scope(params = mapOf("id" to "c1"))).encoded().contains(SECRET))
        assertNull(connection.current()?.clientSecret)
    }

    @Test
    fun `hiding the secret removes it from the next render and refreshes`() = runBlocking {
        connect()
        contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )
        val page = contribution(CredentialServerPages.CLIENT)
        val params = mapOf("id" to "c1")
        val hide = page.render(scope(params = params)).flatten().filterIsInstance<UiComponent.Button>()
            .single { (it.action as? UiAction.Invoke)?.actionId == CredentialServerClientContribution.ACTION_HIDE_SECRET }

        val result = page.invoke(scope(params = params), (hide.action as UiAction.Invoke).actionId, emptyMap())

        assertEquals(UiInvokeStatus.OK, result.status)
        assertTrue(result.refresh)
        assertFalse(page.render(scope(params = params)).encoded().contains(SECRET))
    }

    @Test
    fun `a revealed secret expires after the time to live`() = runBlocking {
        connect()
        contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )
        val page = contribution(CredentialServerPages.CLIENT)
        val params = mapOf("id" to "c1")

        virtualMillis += CredentialServerSecretReveal.TTL.inWholeMilliseconds - 1
        assertTrue(page.render(scope(params = params)).encoded().contains(SECRET))
        virtualMillis += 1
        assertFalse(page.render(scope(params = params)).encoded().contains(SECRET))
    }

    @Test
    fun `a rotated secret stays visible across renders`() = runBlocking {
        connect()
        contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )
        val page = contribution(CredentialServerPages.CLIENT)
        val params = mapOf("id" to "c1")

        val result = page.invoke(scope(params = params), CredentialServerClientContribution.ACTION_ROTATE, emptyMap())

        assertEquals(UiInvokeStatus.OK, result.status)
        repeat(2) {
            val encoded = page.render(scope(params = params)).encoded()
            assertTrue(encoded.contains(ROTATED_SECRET))
            assertFalse(encoded.contains(SECRET))
        }
    }

    @Test
    fun `a revealed secret never appears for another user`() = runBlocking {
        connect()
        contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )
        val page = contribution(CredentialServerPages.CLIENT)

        assertFalse(page.render(scope(user = member, params = mapOf("id" to "c1"))).encoded().contains(SECRET))
        assertTrue(page.render(scope(params = mapOf("id" to "c1"))).encoded().contains(SECRET))
    }

    @Test
    fun `a cancelled tidal login renders the neutral cancelled badge`() = runBlocking {
        connect()
        loginEvents = listOf(TidalLoginEvent(TidalLoginState.PENDING), TidalLoginEvent(TidalLoginState.CANCELLED))
        val params = mapOf(
            CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL,
            CredentialServerPages.PARAM_KIND to CredentialKind.TIDAL_DEVICE_SESSION.name,
        )
        val page = contribution(CredentialServerPages.CREDENTIAL)
        val started =
            page.invoke(scope(params = params), CredentialServerCredentialContribution.ACTION_TIDAL_LOGIN, emptyMap())
        assertEquals(UiInvokeStatus.OK, started.status)

        val updates =
            page.live(scope(params = params), "${CredentialServerCredentialContribution.LIVE_TIDAL_PREFIX}login1")!!
                .toList()

        val badge = (updates.last() as UiLiveUpdate.Replace).child as UiComponent.Badge
        assertEquals("Login cancelled", badge.text)
        assertEquals(UiTone.MUTED, badge.tone)
        assertTrue(updates.dropLast(1).all { (it as UiLiveUpdate.Replace).child is UiComponent.Card })
        assertNull(ui.tidalLogins.active(admin.id, CredentialNames.IMPORTER_TIDDL))
    }

    private fun localLogin(name: String, role: String, content: String): File {
        val file = tempDir.resolve(role).toFile().apply { writeText(content) }
        importers += when (name) {
            CredentialNames.IMPORTER_TDN -> mockk<TdnService> {
                every { credentialName } returns name
                every { credentialTargets() } returns mapOf(role to file)
            }

            else -> mockk<TiddlService> {
                every { credentialName } returns name
                every { credentialTargets() } returns mapOf(role to file)
            }
        }
        return file
    }

    private fun localLoginButtons(page: UiComponent) = page.flatten().filterIsInstance<UiComponent.Button>()
        .filter { (it.action as? UiAction.Invoke)?.actionId == CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN }

    private fun upserts() = requests.filter { it.method == HttpMethod.Put }
        .map { it.url.encodedPath to json.decodeFromString<UpsertCredentialRequest>((it.body as TextContent).text) }

    @Test
    fun `the existing login action is hidden without a local login file`() = runBlocking {
        connect()
        importers += mockk<TiddlService> {
            every { credentialName } returns CredentialNames.IMPORTER_TIDDL
            every { credentialTargets() } returns mapOf(
                CredentialFileRoles.TIDDL_AUTH to tempDir.resolve("missing.json").toFile()
            )
        }
        val params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)

        assertTrue(localLoginButtons(contribution(CredentialServerPages.CREDENTIAL).render(scope(params = params))).isEmpty())
        val result = contribution(CredentialServerPages.CREDENTIAL).invoke(
            scope(params = params),
            CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
            emptyMap()
        )
        assertEquals(UiInvokeStatus.ERROR, result.status)
        assertTrue(upserts().isEmpty())
    }

    @Test
    fun `the existing tiddl login is sent with the entered client ids and never rendered`() = runBlocking {
        connect()
        val content = """{"token":"local-tiddl-token","refresh_token":"r"}"""
        localLogin(CredentialNames.IMPORTER_TIDDL, CredentialFileRoles.TIDDL_AUTH, content)
        val params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)
        val page = contribution(CredentialServerPages.CREDENTIAL)

        val rendered = page.render(scope(params = params))
        val button = localLoginButtons(rendered).single()
        assertEquals("Use this server's existing login", button.label)
        assertEquals(CredentialServerCredentialContribution.FORM_CREDENTIAL, (button.action as UiAction.Invoke).formId)
        assertFalse(rendered.encoded().contains("local-tiddl-token"))

        val result = page.invoke(
            scope(params = params),
            CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
            mapOf("clientId" to UiValue.of("my-client"), "clientSecret" to UiValue.of("my-secret")),
        )

        assertEquals(UiInvokeStatus.OK, result.status)
        assertTrue(result.refresh)
        assertEquals("This server's existing login was uploaded.", result.message)
        assertFalse(result.message.orEmpty().contains("local-tiddl-token"))
        val (path, request) = upserts().single()
        assertEquals("/admin/credentials/${CredentialNames.IMPORTER_TIDDL}", path)
        assertEquals(CredentialKind.TIDAL_DEVICE_SESSION, request.kind)
        assertEquals(
            CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "my-client", "my-secret", content),
            request.input
        )
    }

    @Test
    fun `the existing tdn login keeps blank client ids for the credential server to merge`() = runBlocking {
        connect()
        credentials += credential(CredentialNames.IMPORTER_TDN, CredentialKind.TIDAL_DEVICE_SESSION)
        val content = """{"token_type":"Bearer","access_token":"local-tdn-token"}"""
        localLogin(CredentialNames.IMPORTER_TDN, CredentialFileRoles.TDN_TOKEN, content)
        val params = mapOf(
            CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TDN,
            CredentialServerPages.PARAM_KIND to CredentialKind.TIDAL_DEVICE_SESSION.name,
        )
        val page = contribution(CredentialServerPages.CREDENTIAL)
        assertEquals(1, localLoginButtons(page.render(scope(params = params))).size)

        val result = page.invoke(
            scope(params = params),
            CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
            emptyMap()
        )

        assertEquals(UiInvokeStatus.OK, result.status)
        val (path, request) = upserts().single()
        assertEquals("/admin/credentials/${CredentialNames.IMPORTER_TDN}", path)
        assertEquals(CredentialInput.TidalSessionInput(TidalSessionFormat.TDN, "", "", content), request.input)
    }

    @Test
    fun `a rejected existing login shows the credential server error`() = runBlocking {
        connect()
        rejectUpsert = true
        localLogin(CredentialNames.IMPORTER_TIDDL, CredentialFileRoles.TIDDL_AUTH, "{}")
        val params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)

        val result = contribution(CredentialServerPages.CREDENTIAL).invoke(
            scope(params = params),
            CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
            emptyMap()
        )

        assertEquals(UiInvokeStatus.ERROR, result.status)
        assertEquals("Tidal client id and secret are required", result.message)
    }

    private fun buttonColumn(page: UiComponent.Column, formId: String): UiComponent.Column {
        val index = page.children.indexOfFirst { it is UiComponent.Form && it.id == formId }
        val form = page.children[index] as UiComponent.Form
        assertTrue(form.actions.isEmpty())
        assertTrue(form.flatten().none { it is UiComponent.Button })
        assertEquals(UiComponent.Divider, page.children[index + 1])
        return page.children[index + 2] as UiComponent.Column
    }

    private fun actionIds(column: UiComponent.Column) =
        column.children.map { ((it as UiComponent.Button).action as UiAction.Invoke).actionId }


    @Test
    fun `the overview starts with the navigation and lists the local credentials below`() = runBlocking {
        val local = overview() as UiComponent.Column
        assertTrue(local.children[0].opens(CredentialServerPages.SERVER))
        assertEquals(
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentialserver.status.local"),
            (local.children[0] as UiComponent.ListItem).trailing
        )
        assertTrue(local.children.none { it.opens(CredentialServerPages.CLIENTS) })
        assertEquals(2, local.children.size)
        assertEquals(9, local.children[1].flatten().count { it.opens(CredentialServerPages.LOCAL) })

        connect()
        contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )
        val accepted = overview() as UiComponent.Column
        assertTrue(accepted.children[0].opens(CredentialServerPages.SERVER))
        assertEquals(
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentialserver.status.connected"),
            (accepted.children[0] as UiComponent.ListItem).trailing
        )
        assertTrue(accepted.children[1].opens(CredentialServerPages.CLIENTS))
        assertEquals("1", (accepted.children[1] as UiComponent.ListItem).trailing)
        assertEquals(3, accepted.children.size)
        assertEquals(9, accepted.children[2].flatten().count { it.opens(CredentialServerPages.LOCAL) })
    }

    @Test
    fun `the server page puts test and disconnect in their own rows after the connection form`() = runBlocking {
        val server = contribution(CredentialServerPages.SERVER)
        val local = buttonColumn(
            server.render(scope()) as UiComponent.Column,
            CredentialServerServerContribution.FORM_CONNECTION
        )
        assertEquals(UiAlign.START, local.align)
        assertEquals(listOf(CredentialServerServerContribution.ACTION_TEST), actionIds(local))
        assertEquals(UiButtonStyle.SECONDARY, (local.children[0] as UiComponent.Button).style)

        connect()
        val connected = buttonColumn(
            server.render(scope()) as UiComponent.Column,
            CredentialServerServerContribution.FORM_CONNECTION
        )
        assertEquals(
            listOf(
                CredentialServerServerContribution.ACTION_TEST,
                CredentialServerServerContribution.ACTION_DISCONNECT
            ), actionIds(connected)
        )
        val disconnect = connected.children[1] as UiComponent.Button
        assertEquals(UiButtonStyle.DESTRUCTIVE, disconnect.style)
        assertEquals(
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentialserver.disconnectConfirm"),
            (disconnect.action as UiAction.Invoke).confirmText
        )

        val result = server.invoke(scope(), CredentialServerServerContribution.ACTION_DISCONNECT, emptyMap())
        assertEquals(UiInvokeStatus.OK, result.status)
        assertNull(connection.current()?.baseUrl)
    }

    @Test
    fun `test connection refreshes the consumer grants and reports the granted count`() = runBlocking {
        connect()
        connection.store(mapOf(KEY_CLIENT_ID to CONSUMER_ID, KEY_CLIENT_SECRET to CONSUMER_SECRET))
        consumerGrants = listOf(GrantInfo(CredentialNames.YOUTUBE_API, CredentialKind.API_KEY, false))
        val server = contribution(CredentialServerPages.SERVER)

        val first = server.invoke(scope(), CredentialServerServerContribution.ACTION_TEST, emptyMap())
        assertEquals(UiInvokeStatus.OK, first.status)
        assertTrue(first.message.orEmpty().contains("Client login works with 1 granted credentials"))
        assertTrue(remote.isManagedRemotely(CredentialNames.YOUTUBE_API))
        assertFalse(remote.isManagedRemotely(CredentialNames.PODCAST_INDEX_API))

        consumerGrants =
            consumerGrants + GrantInfo(CredentialNames.PODCAST_INDEX_API, CredentialKind.API_KEY_PAIR, false)
        val second = server.invoke(scope(), CredentialServerServerContribution.ACTION_TEST, emptyMap())
        assertTrue(second.message.orEmpty().contains("Client login works with 2 granted credentials"))
        assertTrue(remote.isManagedRemotely(CredentialNames.PODCAST_INDEX_API))

        connection.store(mapOf(KEY_CLIENT_SECRET to "wrong"))
        val failed = server.invoke(scope(), CredentialServerServerContribution.ACTION_TEST, emptyMap())
        assertTrue(failed.message.orEmpty().contains("Client login failed"))
    }

    @Test
    fun `the local test of a newly granted podcast index credential succeeds without a restart`() = runBlocking {
        connect()
        connection.store(mapOf(KEY_CLIENT_ID to CONSUMER_ID, KEY_CLIENT_SECRET to CONSUMER_SECRET))
        val routed = context(RoutingCredentialProvider(local, remote))
        val page = routed.contributions().single { it.id == CredentialServerPages.LOCAL }
        val params = mapOf(CredentialServerPages.PARAM_NAME to UiValue.of(CredentialNames.PODCAST_INDEX_API))
        assertEquals(LocalCredentialState.NONE, routed.localState(CredentialNames.PODCAST_INDEX_API))

        consumerGrants = listOf(GrantInfo(CredentialNames.PODCAST_INDEX_API, CredentialKind.API_KEY_PAIR, false))
        val result =
            page.invoke(localScope(CredentialNames.PODCAST_INDEX_API), LocalCredentialContribution.ACTION_TEST, params)

        assertEquals(UiInvokeStatus.OK, result.status)
        assertEquals(translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentials.testOk"), result.message)
        assertEquals(LocalCredentialState.REMOTE, routed.localState(CredentialNames.PODCAST_INDEX_API))
    }

    @Test
    fun `the clients page lists clients and creating one opens its page with the secret`() = runBlocking {
        connect()
        val page = contribution(CredentialServerPages.CLIENTS)
        assertTrue(page.render(scope()).flatten().none { it.opens(CredentialServerPages.CLIENT) })

        val result = page.invoke(
            scope(),
            CredentialServerClientsContribution.ACTION_CREATE_CLIENT,
            mapOf(CredentialServerClientsContribution.FIELD_CLIENT_NAME to UiValue.of("Laptop"))
        )

        assertEquals(UiInvokeStatus.OK, result.status)
        assertEquals(
            UiAction.OpenPage(CredentialServerPages.CLIENT, mapOf(CredentialServerPages.PARAM_ID to "c1")),
            result.next
        )
        val item = page.render(scope()).flatten().filterIsInstance<UiComponent.ListItem>()
            .single { it.opens(CredentialServerPages.CLIENT) }
        assertEquals("Laptop", item.title)
        assertEquals(mapOf(CredentialServerPages.PARAM_ID to "c1"), (item.action as UiAction.OpenPage).params)
        assertFalse(page.render(scope()).encoded().contains(SECRET))
        assertTrue(
            contribution(CredentialServerPages.CLIENT).render(scope(params = mapOf(CredentialServerPages.PARAM_ID to "c1")))
                .encoded().contains(SECRET)
        )

        val blank = page.invoke(scope(), CredentialServerClientsContribution.ACTION_CREATE_CLIENT, emptyMap())
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, blank.status)
        assertEquals(setOf(CredentialServerClientsContribution.FIELD_CLIENT_NAME), blank.fieldErrors.keys)
    }

    @Test
    fun `deleting from a detail page goes back to its list page`() = runBlocking {
        connect()
        contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )

        val client = contribution(CredentialServerPages.CLIENT).invoke(
            scope(params = mapOf(CredentialServerPages.PARAM_ID to "c1")),
            CredentialServerClientContribution.ACTION_DELETE,
            emptyMap()
        )
        val credential = contribution(CredentialServerPages.CREDENTIAL).invoke(
            scope(params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.TIDAL_API)),
            CredentialServerCredentialContribution.ACTION_DELETE,
            emptyMap()
        )

        assertEquals(UiAction.OpenPage(CredentialServerPages.CLIENTS), client.next)
        assertEquals(UiAction.OpenPage(CredentialServerPages.SERVER), credential.next)
    }

    @Test
    fun `both tidal buttons sit inside the credential form and send its values`() = runBlocking {
        connect()
        localLogin(CredentialNames.IMPORTER_TIDDL, CredentialFileRoles.TIDDL_AUTH, "{}")
        val page =
            contribution(CredentialServerPages.CREDENTIAL).render(scope(params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)))

        val form = page.flatten().filterIsInstance<UiComponent.Form>()
            .single { it.id == CredentialServerCredentialContribution.FORM_CREDENTIAL }
        assertTrue(form.actions.isEmpty())
        val column = form.children.last() as UiComponent.Column
        assertEquals(UiAlign.START, column.align)
        assertEquals(UiSpacing.SMALL, column.spacing)
        assertEquals(
            listOf(
                CredentialServerCredentialContribution.ACTION_TIDAL_LOGIN,
                CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN
            ), actionIds(column)
        )
        assertTrue(column.children.all { ((it as UiComponent.Button).action as UiAction.Invoke).formId == CredentialServerCredentialContribution.FORM_CREDENTIAL })
    }

    @Test
    fun `a new tidal session without a preset needs the client fields before any login starts`() = runBlocking {
        connect()
        credentials.removeIf { it.name == CredentialNames.IMPORTER_TIDDL }
        localLogin(CredentialNames.IMPORTER_TIDDL, CredentialFileRoles.TIDDL_AUTH, "{}")
        val params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)
        val page = contribution(CredentialServerPages.CREDENTIAL)
        val blank = mapOf(
            CredentialServerPages.PARAM_KIND to UiValue.of(CredentialKind.TIDAL_DEVICE_SESSION.name),
            "clientSecret" to UiValue.of(" ")
        )
        val required = mapOf(
            "clientId" to translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentialserver.error.required"),
            "clientSecret" to translations.resolve(
                CREDENTIAL_SERVER_UI_SOURCE,
                "en",
                "credentialserver.error.required"
            ),
        )

        listOf(
            CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
            CredentialServerCredentialContribution.ACTION_TIDAL_LOGIN
        ).forEach { action ->
            val result = page.invoke(scope(params = params), action, blank)
            assertEquals(UiInvokeStatus.VALIDATION_ERROR, result.status)
            assertEquals(required, result.fieldErrors)
        }
        val custom = page.invoke(
            scope(params = mapOf(CredentialServerPages.PARAM_NAME to "custom.tidal")),
            CredentialServerCredentialContribution.ACTION_TIDAL_LOGIN,
            blank,
        )
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, custom.status)
        assertEquals(required, custom.fieldErrors)
        assertTrue(requests.none { it.method == HttpMethod.Put || it.method == HttpMethod.Post })

        val entered = page.invoke(
            scope(params = params),
            CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
            mapOf("clientId" to UiValue.of("my-client"), "clientSecret" to UiValue.of("my-secret")),
        )
        assertEquals(UiInvokeStatus.OK, entered.status)
        assertEquals(
            CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "my-client", "my-secret", "{}"),
            upserts().single().second.input
        )
    }

    @Test
    fun `a new preset tidal session sends blank client fields for the importer default`() = runBlocking {
        connect()
        credentials.removeIf { it.name == CredentialNames.IMPORTER_TIDDL }
        presets += CredentialPreset(
            CredentialNames.IMPORTER_TIDDL,
            CredentialKind.TIDAL_DEVICE_SESSION,
            "preset description",
            format = TidalSessionFormat.TIDDL
        )
        localLogin(CredentialNames.IMPORTER_TIDDL, CredentialFileRoles.TIDDL_AUTH, "{}")
        val params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)
        val page = contribution(CredentialServerPages.CREDENTIAL)

        val clientFields = page.render(scope(params = params)).flatten().filterIsInstance<UiComponent.TextField>()
            .filter { it.key == "clientId" || it.key == "clientSecret" }
        assertEquals(2, clientFields.size)
        val defaultHelper =
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentialserver.field.tidalClientDefaultHelper")!!
        assertTrue(clientFields.all { !it.required && it.helper.orEmpty().endsWith(defaultHelper) })

        val local = page.invoke(
            scope(params = params),
            CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
            emptyMap()
        )
        assertEquals(UiInvokeStatus.OK, local.status)
        assertTrue(local.fieldErrors.isEmpty())
        val (path, request) = upserts().single()
        assertEquals("/admin/credentials/${CredentialNames.IMPORTER_TIDDL}", path)
        assertEquals(CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "", "", "{}"), request.input)

        val login =
            page.invoke(scope(params = params), CredentialServerCredentialContribution.ACTION_TIDAL_LOGIN, emptyMap())
        assertEquals(UiInvokeStatus.OK, login.status)
        assertTrue(login.fieldErrors.isEmpty())
        val start = requests.single { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/tidal-login") }
        assertEquals(
            TidalLoginStart(TidalSessionFormat.TIDDL, null, null),
            json.decodeFromString<TidalLoginStart>((start.body as TextContent).text)
        )
    }

    @Test
    fun `a configured tiddl client is sent for blank fields on both tiddl actions and typed values win`() =
        runBlocking {
            connect()
            tiddlAuth = TiddlAuthConfig("env-id", "env-secret")
            credentials.removeIf { it.name == CredentialNames.IMPORTER_TIDDL }
            presets += CredentialPreset(
                CredentialNames.IMPORTER_TIDDL,
                CredentialKind.TIDAL_DEVICE_SESSION,
                "preset description",
                format = TidalSessionFormat.TIDDL
            )
            localLogin(CredentialNames.IMPORTER_TIDDL, CredentialFileRoles.TIDDL_AUTH, "{}")
            val params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)
            val page = contribution(CredentialServerPages.CREDENTIAL)
            val envHelper =
                translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentialserver.field.tiddlAuthHelper")!!

            val fields = page.render(scope(params = params)).flatten().filterIsInstance<UiComponent.TextField>()
                .filter { it.key == "clientId" || it.key == "clientSecret" }
            assertTrue(fields.all { it.helper.orEmpty().contains(envHelper) })

            assertEquals(
                UiInvokeStatus.OK,
                page.invoke(
                    scope(params = params),
                    CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN,
                    emptyMap()
                ).status
            )
            assertEquals(
                CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "env-id", "env-secret", "{}"),
                upserts().single().second.input
            )

            assertEquals(
                UiInvokeStatus.OK,
                page.invoke(
                    scope(params = params),
                    CredentialServerCredentialContribution.ACTION_TIDAL_LOGIN,
                    emptyMap()
                ).status
            )
            val start = requests.single { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/tidal-login") }
            assertEquals(
                TidalLoginStart(TidalSessionFormat.TIDDL, "env-id", "env-secret"),
                json.decodeFromString<TidalLoginStart>((start.body as TextContent).text)
            )

            val typed = mapOf("clientId" to UiValue.of("my-client"), "clientSecret" to UiValue.of("my-secret"))
            page.invoke(scope(params = params), CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN, typed)
            assertEquals(
                CredentialInput.TidalSessionInput(TidalSessionFormat.TIDDL, "my-client", "my-secret", "{}"),
                upserts().last().second.input
            )
        }

    @Test
    fun `a configured tiddl client is ignored for tdn sessions`() = runBlocking {
        connect()
        tiddlAuth = TiddlAuthConfig("env-id", "env-secret")
        credentials += credential(CredentialNames.IMPORTER_TDN, CredentialKind.TIDAL_DEVICE_SESSION)
        presets += CredentialPreset(
            CredentialNames.IMPORTER_TDN,
            CredentialKind.TIDAL_DEVICE_SESSION,
            "preset description",
            format = TidalSessionFormat.TDN
        )
        localLogin(CredentialNames.IMPORTER_TDN, CredentialFileRoles.TDN_TOKEN, "{}")
        val params = mapOf(
            CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TDN,
            CredentialServerPages.PARAM_KIND to CredentialKind.TIDAL_DEVICE_SESSION.name,
        )
        val page = contribution(CredentialServerPages.CREDENTIAL)

        page.invoke(scope(params = params), CredentialServerCredentialContribution.ACTION_USE_LOCAL_LOGIN, emptyMap())

        assertEquals(
            CredentialInput.TidalSessionInput(TidalSessionFormat.TDN, "", "", "{}"),
            upserts().single().second.input
        )
        val fields = page.render(scope(params = params)).flatten().filterIsInstance<UiComponent.TextField>()
            .filter { it.key == "clientId" }
        val envHelper =
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "en", "credentialserver.field.tiddlAuthHelper")!!
        assertTrue(fields.none { it.helper.orEmpty().contains(envHelper) })
    }

    @Test
    fun `an existing tidal session passes blank client fields through`() = runBlocking {
        connect()
        val params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL)

        val result = contribution(CredentialServerPages.CREDENTIAL).invoke(
            scope(params = params),
            CredentialServerCredentialContribution.ACTION_TIDAL_LOGIN,
            emptyMap()
        )

        assertEquals(UiInvokeStatus.OK, result.status)
        val request = requests.single { it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/tidal-login") }
        assertEquals(
            TidalLoginStart(TidalSessionFormat.TIDDL, null, null),
            json.decodeFromString<TidalLoginStart>((request.body as TextContent).text)
        )
    }

    @Test
    fun `core credential names and descriptions are translated`() = runBlocking {
        connect()
        val text = ui.credentialText(scope(locale = "de"), CredentialNames.IMPORTER_TIDDL)
        assertEquals(
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "de", "credentials.name.importer.tiddl"),
            text.label
        )
        assertNotNull(text.description)
        assertEquals(
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "de", "credentials.about.importer.tiddl"),
            text.description
        )

        val header = contribution(CredentialServerPages.CREDENTIAL).render(
            scope(
                params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL),
                locale = "de"
            )
        )
            .flatten().filterIsInstance<UiComponent.Text>()
        assertEquals(UiTextStyle.TITLE, header[0].style)
        assertEquals(text.label, header[0].text)
        assertEquals(UiComponent.Text(CredentialNames.IMPORTER_TIDDL, UiTextStyle.CAPTION, UiTone.MUTED), header[1])
        assertEquals(text.description, header[2].text)

        val listed = contribution(CredentialServerPages.SERVER).render(scope(locale = "de")).flatten()
            .filterIsInstance<UiComponent.ListItem>()
            .single { (it.action as? UiAction.OpenPage)?.params?.get(CredentialServerPages.PARAM_NAME) == CredentialNames.IMPORTER_TIDDL }
        assertEquals(text.label, listed.title)
        assertTrue(listed.subtitle.orEmpty().startsWith(CredentialNames.IMPORTER_TIDDL))

        contribution(CredentialServerPages.CLIENTS).invoke(
            scope(),
            "createClient",
            mapOf("clientName" to UiValue.of("Laptop"))
        )
        val grant = contribution(CredentialServerPages.CLIENT).render(
            scope(
                params = mapOf(CredentialServerPages.PARAM_ID to "c1"),
                locale = "de"
            )
        )
            .flatten().filterIsInstance<UiComponent.Switch>()
            .single { it.key == CredentialServerClientContribution.grantKey(CredentialNames.IMPORTER_TIDDL) }
        assertEquals(text.label, grant.label)
        assertTrue(grant.helper.orEmpty().startsWith(CredentialNames.IMPORTER_TIDDL))
    }

    @Test
    fun `plugin credential names come from the plugin bundle`() {
        translations.forSource("lastfm")
            .registerBundle("de", mapOf("credentials.name.apiKey" to "Last.fm-API-Schlüssel"))

        val text = ui.credentialText(scope(locale = "de"), CredentialNames.plugin("lastfm", "apiKey"))

        assertEquals(translations.resolve("lastfm", "de", "credentials.name.apiKey"), text.label)
        assertNull(text.description)
    }

    @Test
    fun `untranslatable credential names stay raw`() {
        translations.forSource("lastfm")
            .registerBundle("de", mapOf("credentials.name.apiKey" to "Last.fm-API-Schlüssel"))
        listOf(
            "custom.thing",
            CredentialNames.plugin("lastfm", "other"),
            CredentialNames.plugin("unknown", "token")
        ).forEach { name ->
            assertEquals(CredentialText(name, null), ui.credentialText(scope(locale = "de"), name))
        }
    }

    @Test
    fun `file roles and tidal formats are translated`() = runBlocking {
        connect()
        val files = contribution(CredentialServerPages.CREDENTIAL)
            .render(
                scope(
                    params = mapOf(
                        CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_GAMDL,
                        CredentialServerPages.PARAM_KIND to CredentialKind.FILE.name
                    ), locale = "de"
                )
            )
            .flatten().filterIsInstance<UiComponent.FileField>()
        assertEquals(
            listOf(
                CredentialServerCredentialContribution.fileKey(CredentialFileRoles.GAMDL_COOKIES) to translations.resolve(
                    CREDENTIAL_SERVER_UI_SOURCE,
                    "de",
                    "credentialserver.fileRole.cookies.txt"
                ),
                CredentialServerCredentialContribution.fileKey(CredentialFileRoles.GAMDL_WVD) to translations.resolve(
                    CREDENTIAL_SERVER_UI_SOURCE,
                    "de",
                    "credentialserver.fileRole.device.wvd"
                ),
            ),
            files.map { it.key to it.label },
        )

        val format = contribution(CredentialServerPages.CREDENTIAL)
            .render(
                scope(
                    params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL),
                    locale = "de"
                )
            )
            .flatten().filterIsInstance<UiComponent.Select>()
            .single { it.key == CredentialServerCredentialContribution.FIELD_FORMAT }
        assertEquals(
            listOf(
                TidalSessionFormat.TIDDL.name to translations.resolve(
                    CREDENTIAL_SERVER_UI_SOURCE,
                    "de",
                    "credentialserver.format.TIDDL"
                ),
                TidalSessionFormat.TDN.name to translations.resolve(
                    CREDENTIAL_SERVER_UI_SOURCE,
                    "de",
                    "credentialserver.format.TDN"
                ),
            ),
            format.options.map { it.value to it.label },
        )
    }

    @Test
    fun `the tidal session file field is labelled by the session format`() = runBlocking {
        connect()
        presets += CredentialPreset(
            CredentialNames.IMPORTER_TDN,
            CredentialKind.TIDAL_DEVICE_SESSION,
            "preset description",
            format = TidalSessionFormat.TDN
        )
        val page = contribution(CredentialServerPages.CREDENTIAL)

        val tiddl = page.render(
            scope(
                params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TIDDL),
                locale = "de"
            )
        )
            .flatten().filterIsInstance<UiComponent.FileField>()
            .single { it.key == CredentialServerCredentialContribution.FIELD_AUTH_FILE }
        val tdn = page.render(
            scope(
                params = mapOf(CredentialServerPages.PARAM_NAME to CredentialNames.IMPORTER_TDN),
                locale = "de"
            )
        )
            .flatten().filterIsInstance<UiComponent.FileField>()
            .single { it.key == CredentialServerCredentialContribution.FIELD_AUTH_FILE }

        assertEquals(
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "de", "credentialserver.fileRole.auth.json"),
            tiddl.label
        )
        assertEquals(
            translations.resolve(CREDENTIAL_SERVER_UI_SOURCE, "de", "credentialserver.fileRole.token.json"),
            tdn.label
        )
        assertEquals(listOf(".json"), tdn.accept)
    }

    @Test
    fun `a new entry starts with an empty description and an existing one shows its stored description`() =
        runBlocking {
            connect()
            presets += CredentialPreset(
                CredentialNames.IMPORTER_TDN,
                CredentialKind.TIDAL_DEVICE_SESSION,
                "preset description",
                format = TidalSessionFormat.TDN
            )
            credentials.replaceAll {
                if (it.name == CredentialNames.IMPORTER_TIDDL) it.copy(description = "stored description") else it
            }
            val page = contribution(CredentialServerPages.CREDENTIAL)
            suspend fun description(name: String) =
                page.render(scope(params = mapOf(CredentialServerPages.PARAM_NAME to name))).flatten()
                    .filterIsInstance<UiComponent.TextField>()
                    .single { it.key == CredentialServerCredentialContribution.FIELD_DESCRIPTION }

            assertNull(description(CredentialNames.IMPORTER_TDN).value)
            assertEquals("stored description", description(CredentialNames.IMPORTER_TIDDL).value)
        }

    @Test
    fun `times are formatted for the locale in the client time zone or in utc`() {
        val millis = 1_790_000_000_000L
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.forLanguageTag("de"))
        val berlin = ZoneId.of("Europe/Berlin")
        val zoned = ServerUiRenderScope(
            user = UserInfo.fromUser(admin),
            context = UiContext(),
            i18n = translations.translator(CREDENTIAL_SERVER_UI_SOURCE, "de"),
            settings = connectionSettings,
            clientSchemaVersion = UiSchemaVersion.CURRENT,
            account = admin,
            client = ClientInfo(
                apiVersion = 99,
                uiSchemaVersion = UiSchemaVersion.CURRENT,
                locale = "de",
                timeZone = berlin
            ),
            call = null,
        )

        assertEquals(formatter.withZone(berlin).format(JavaInstant.ofEpochMilli(millis)), formatTime(zoned, millis))
        assertEquals(
            requireNotNull(
                translations.resolve(
                    CREDENTIAL_SERVER_UI_SOURCE,
                    "de",
                    "credentialserver.time"
                )
            ).replace("{time}", formatter.withZone(ZoneOffset.UTC).format(JavaInstant.ofEpochMilli(millis))),
            formatTime(scope(locale = "de"), millis),
        )
    }

    companion object {
        private const val ADMIN_KEY = "admin-key"
        private const val SECRET = "very-secret-client-secret"
        private const val ROTATED_SECRET = "rotated-client-secret"
        private const val CONSUMER_ID = "synara-consumer"
        private const val CONSUMER_SECRET = "consumer-secret"
    }
}
