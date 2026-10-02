package dev.dertyp.services.ui.credentialserver

import dev.dertyp.config.ProxyConfig
import dev.dertyp.config.ServerConfig
import dev.dertyp.core.ApplicationScope
import dev.dertyp.core.ClientInfo
import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.UnauthorizedException
import dev.dertyp.credentials.*
import dev.dertyp.data.User
import dev.dertyp.data.UserInfo
import dev.dertyp.plugins.UiContribution
import dev.dertyp.plugins.UiRenderScope
import dev.dertyp.services.credentials.*
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_ADMIN_KEY
import dev.dertyp.services.credentials.CredentialServerConnectionSource.Companion.KEY_URL
import dev.dertyp.services.credentials.admin.CredentialServerAdminClient
import dev.dertyp.services.metadata.AcoustIdCredentialSource
import dev.dertyp.services.podcast.index.PodcastIndexCredentialSource
import dev.dertyp.services.ui.PluginSettingsService
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

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
    private val credentials = listOf(
        credential(CredentialNames.TIDAL_API, CredentialKind.OAUTH_CLIENT_CREDENTIALS),
        credential(CredentialNames.IMPORTER_TIDDL, CredentialKind.TIDAL_DEVICE_SESSION),
        credential(CredentialNames.plugin("demo", "token"), CredentialKind.API_KEY),
    )
    private val admin = User(UUID.randomUUID(), "root", displayName = "Root", passwordHash = "", isAdmin = true)
    private val member = User(UUID.randomUUID(), "member", displayName = "Member", passwordHash = "", isAdmin = false)

    private val adminClient = CredentialServerAdminClient(connection, httpClientFactory())
    private val localStore = LocalCredentialStore(settingsService, environment, cipher, AcoustIdCredentialSource(settingsService, environment, cipher))
    private val local = LocalCredentialProvider(ClientCredentialsExchange(mockk(relaxed = true)), AppleDeveloperTokenSigner(), localStore, LocalPluginCredentialStore(settingsService, cipher))
    private val remoteNames = mutableSetOf<String>()
    private val provider = PartlyRemoteProvider(local, remoteNames)
    private val serverConfig = mockk<ServerConfig> {
        every { proxy } returns ProxyConfig(hostname = null, controlPort = null, ssl = false, id = null, name = "Home", key = null)
    }
    private val ui = CredentialServerUiContext(adminClient, connection, provider, localStore, serverConfig)
    private val contributions = ui.contributions()

    init {
        translations.forSource(CREDENTIAL_SERVER_UI_SOURCE).registerBundlesFromResources(javaClass.classLoader, "i18n/credentialserver", listOf("en", "de"))
    }

    class PartlyRemoteProvider(private val local: LocalCredentialProvider, private val remote: Set<String>) : CredentialProvider {
        override val mode: CredentialMode get() = if (remote.isEmpty()) CredentialMode.LOCAL else CredentialMode.REMOTE

        override fun isAvailable(name: String): Boolean = name in remote || local.isAvailable(name)

        override fun isManagedRemotely(name: String): Boolean = name in remote

        override suspend fun resolve(name: String): ResolvedCredential? =
            if (name in remote) ResolvedCredential.ApiKey(name, "remote") else local.resolve(name)

        override suspend fun writeBack(name: String, expectedFingerprint: String?, files: List<CredentialFile>): Boolean = false

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
        every { factory.shared<HttpClientEngineConfig>(HttpClientFactory.CREDENTIAL_SERVER_ADMIN, any(), any(), any()) } returns http
        return factory
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData) = when {
        request.url.encodedPath == "/health" ->
            if (healthy) ok(json.encodeToString(CredentialServerHealth(true, CredentialProtocol.PROTOCOL_VERSION, "1.2.3")))
            else respond("down", HttpStatusCode.ServiceUnavailable)
        request.headers[CredentialProtocol.ADMIN_KEY_HEADER] != ADMIN_KEY ->
            respond(json.encodeToString(CredentialError(CredentialErrorCode.UNAUTHORIZED, "bad key")), HttpStatusCode.Unauthorized)
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
                grants = body.grants.map { spec -> GrantInfo(spec.name, credentials.first { it.name == spec.name }.kind, spec.writeBack) },
            )
            clients += created
            ok(json.encodeToString(CreatedClient(created, SECRET)))
        }
        request.url.encodedPath == "/admin/clients" -> ok(json.encodeToString(clients.toList()))
        request.url.encodedPath.startsWith("/admin/clients/") -> {
            val id = request.url.encodedPath.removePrefix("/admin/clients/")
            clients.firstOrNull { it.id == id }?.let { ok(json.encodeToString(it)) }
                ?: respond(json.encodeToString(CredentialError(CredentialErrorCode.NOT_FOUND, "missing")), HttpStatusCode.NotFound)
        }
        request.url.encodedPath == "/admin/credentials" -> ok(json.encodeToString(credentials))
        request.url.encodedPath.startsWith("/admin/credentials/") -> {
            val name = request.url.encodedPath.removePrefix("/admin/credentials/")
            credentials.firstOrNull { it.name == name }?.let { ok(json.encodeToString(it)) }
                ?: respond(json.encodeToString(CredentialError(CredentialErrorCode.NOT_FOUND, "missing")), HttpStatusCode.NotFound)
        }
        request.url.encodedPath == "/admin/presets" -> ok("[]")
        else -> respond("", HttpStatusCode.NotFound)
    }

    private fun MockRequestHandleScope.ok(body: String) =
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))

    private fun connect(adminKey: String = ADMIN_KEY) = runBlocking {
        connection.store(mapOf(KEY_URL to "https://creds.example.com", KEY_ADMIN_KEY to adminKey))
    }

    private fun scope(user: User = admin, params: Map<String, String> = emptyMap()) = UiRenderScope(
        user = UserInfo.fromUser(user),
        context = UiContext(params = params),
        i18n = translations.translator(CREDENTIAL_SERVER_UI_SOURCE, "en"),
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

    private suspend fun localPage(name: String): UiComponent = contribution(CredentialServerPages.LOCAL).render(localScope(name))

    private suspend fun rows(): Map<String, String?> {
        val section = overview().flatten().filterIsInstance<UiComponent.Section>().first { it.title == "On this server" }
        return section.children.filterIsInstance<UiComponent.ListItem>().associate { item ->
            (item.action as UiAction.OpenPage).params.getValue(CredentialServerPages.PARAM_NAME) to item.trailing
        }
    }

    private suspend fun invokeLocal(name: String, action: String, values: Map<String, String> = emptyMap()) =
        contribution(CredentialServerPages.LOCAL).invoke(localScope(name), action, values.mapValues { UiValue.of(it.value) })

    private fun sectionTitles(component: UiComponent) = component.flatten().filterIsInstance<UiComponent.Section>().map { it.title }

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
        assertEquals(5, contributions.size)
        assertTrue(contributions.all { it.access.requiresAdmin })
        assertTrue(contributions.none { it.access.allows(UserInfo.fromUser(member)) })

        val registrar = registry.forSource(CREDENTIAL_SERVER_UI_SOURCE)
        contributions.forEach { registrar.register(it) }
        val service = UiService(registry, translations, settingsService, mockk(relaxed = true), mockk(relaxed = true))
        val client = ClientInfo(apiVersion = 99, uiSchemaVersion = UiSchemaVersion.CURRENT)

        assertThrows<UnauthorizedException> { service.render(member, client, CredentialServerPages.OVERVIEW, UiContext()) }
        assertThrows<UnauthorizedException> { service.invoke(member, client, CredentialServerPages.OVERVIEW, "register", UiInvokePayload()) }
        assertThrows<UnauthorizedException> {
            service.invoke(member, client, CredentialServerPages.LOCAL, "save", UiInvokePayload())
        }
        assertTrue(service.renderSlot(member, client, UiSlots.SETTINGS, UiContext()).items.none { it.contributionId == CredentialServerPages.ENTRY })
        assertTrue(service.renderSlot(admin, client, UiSlots.SETTINGS, UiContext()).items.any { it.contributionId == CredentialServerPages.ENTRY })
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
        assertEquals(ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "stored-linkresolver"), provider.resolve(CredentialNames.LINKRESOLVER_API))
        assertEquals("Stored here", rows()[CredentialNames.LINKRESOLVER_API])

        val field = localPage(CredentialNames.LINKRESOLVER_API).flatten().filterIsInstance<UiComponent.TextField>().single()
        assertTrue(field.secret)
        assertNull(field.value)
        assertEquals("A value is stored. Leave this blank to keep it.", field.helper)
        assertFalse(localPage(CredentialNames.LINKRESOLVER_API).encoded().contains("stored-linkresolver"))

        assertEquals(UiInvokeStatus.OK, invokeLocal(CredentialNames.LINKRESOLVER_API, "save", mapOf("apiKey" to "  ")).status)
        assertEquals(ResolvedCredential.ApiKey(CredentialNames.LINKRESOLVER_API, "stored-linkresolver"), provider.resolve(CredentialNames.LINKRESOLVER_API))
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
        assertEquals(ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "stored-youtube"), provider.resolve(CredentialNames.YOUTUBE_API))
        assertTrue(localPage(CredentialNames.YOUTUBE_API).flatten().filterIsInstance<UiComponent.Button>().any { it.label == "Remove stored values" })

        val cleared = invokeLocal(CredentialNames.YOUTUBE_API, "clear")

        assertEquals(UiInvokeStatus.OK, cleared.status)
        assertEquals(ResolvedCredential.ApiKey(CredentialNames.YOUTUBE_API, "env-youtube"), provider.resolve(CredentialNames.YOUTUBE_API))
        assertEquals("Environment", rows()[CredentialNames.YOUTUBE_API])
        assertTrue(localPage(CredentialNames.YOUTUBE_API).flatten().filterIsInstance<UiComponent.Button>().none { it.label == "Remove stored values" })
    }

    @Test
    fun `acoustid and podcast index values stored under the old keys are still read and written`() = runBlocking {
        serverSettings.set(AcoustIdCredentialSource.KEY_API_KEY, cipher.encrypt(AcoustIdCredentialSource.KEY_API_KEY, "old-acoustid"))
        podcastIndexSettings.setAll(
            mapOf(
                PodcastIndexCredentialSource.KEY_API_KEY to cipher.encrypt(PodcastIndexCredentialSource.KEY_API_KEY, "old-key"),
                PodcastIndexCredentialSource.KEY_API_SECRET to cipher.encrypt(PodcastIndexCredentialSource.KEY_API_SECRET, "old-secret"),
            ),
        )

        val rows = rows()
        assertEquals("Stored here", rows[CredentialNames.ACOUSTID_API])
        assertEquals("Stored here", rows[CredentialNames.PODCAST_INDEX_API])
        assertEquals(ResolvedCredential.ApiKey(CredentialNames.ACOUSTID_API, "old-acoustid"), provider.resolve(CredentialNames.ACOUSTID_API))
        assertEquals(ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "old-key", "old-secret"), provider.resolve(CredentialNames.PODCAST_INDEX_API))

        invokeLocal(CredentialNames.PODCAST_INDEX_API, "save", mapOf("apiSecret" to "new-secret"))
        assertEquals("new-secret", cipher.decrypt(PodcastIndexCredentialSource.KEY_API_SECRET, podcastIndexSettings.getAll().getValue(PodcastIndexCredentialSource.KEY_API_SECRET)))
        assertEquals(ResolvedCredential.ApiKeyPair(CredentialNames.PODCAST_INDEX_API, "old-key", "new-secret"), provider.resolve(CredentialNames.PODCAST_INDEX_API))
        assertTrue(localSettings.getAll().isEmpty())
    }

    @Test
    fun `the apple private key is validated on save and used for signing`() = runBlocking {
        val p8 = localPage(CredentialNames.APPLE_MUSIC_DEVELOPER).flatten().filterIsInstance<UiComponent.FileField>().single()
        assertEquals("p8", p8.key)
        assertEquals(listOf(".p8"), p8.accept)
        assertTrue(p8.secret)
        assertFalse(p8.binary)

        val invalid = invokeLocal(CredentialNames.APPLE_MUSIC_DEVELOPER, "save", mapOf("teamId" to "TEAM", "keyId" to "KEY", "p8" to "not a key"))
        assertEquals(UiInvokeStatus.VALIDATION_ERROR, invalid.status)
        assertEquals(setOf("p8"), invalid.fieldErrors.keys)
        assertNull(localStore.stored(CredentialNames.APPLE_MUSIC_DEVELOPER))

        val saved = invokeLocal(CredentialNames.APPLE_MUSIC_DEVELOPER, "save", mapOf("teamId" to "TEAM", "keyId" to "KEY", "p8" to AppleTestKeys.pem()))
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
        val register = "Register this server"
        assertFalse(sectionTitles(overview()).contains(register))
        assertTrue(sectionTitles(overview()).contains("Credential server"))

        connect(adminKey = "wrong-key")
        val rejected = overview()
        assertFalse(sectionTitles(rejected).contains(register))
        assertFalse(sectionTitles(rejected).contains("Clients"))
        assertTrue(rejected.flatten().filterIsInstance<UiComponent.Text>().any { it.text.startsWith("The credential server does not accept the admin key") })
        assertTrue(contribution(CredentialServerPages.CLIENT).render(scope(params = mapOf("id" to "c1"))) is UiComponent.EmptyState)
        assertTrue(contribution(CredentialServerPages.CREDENTIAL).render(scope(params = mapOf("name" to CredentialNames.TIDAL_API))) is UiComponent.EmptyState)

        connect()
        val accepted = overview()
        assertTrue(sectionTitles(accepted).containsAll(listOf(register, "Credentials", "Clients")))
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
        val page = contribution(CredentialServerPages.CREDENTIAL).render(scope(params = mapOf("name" to CredentialNames.IMPORTER_TIDDL)))
        val authFile = page.flatten().filterIsInstance<UiComponent.FileField>().single()
        assertEquals("authFile", authFile.key)
        assertEquals(listOf(".json"), authFile.accept)
        assertFalse(authFile.binary)
        assertTrue(page.flatten().filterIsInstance<UiComponent.TextField>().none { it.multiline })
    }

    @Test
    fun `register this server stores the returned secret and never renders it`() = runBlocking {
        connect()
        val overview = contribution(CredentialServerPages.OVERVIEW)

        val result = overview.invoke(scope(), "register", mapOf("serverName" to UiValue.of("Home")))

        assertEquals(UiInvokeStatus.OK, result.status)
        assertFalse(result.message.orEmpty().contains(SECRET))
        val request = requests.single { it.method == HttpMethod.Post && it.url.encodedPath == "/admin/clients" }
        val body = json.decodeFromString<CreateClientRequest>((request.body as TextContent).text)
        assertEquals("Home", body.name)
        assertEquals(
            listOf(GrantSpec(CredentialNames.TIDAL_API, writeBack = false), GrantSpec(CredentialNames.IMPORTER_TIDDL, writeBack = true)),
            body.grants,
        )
        val stored = requireNotNull(connection.current())
        assertEquals("client-1", stored.clientId)
        assertEquals(SECRET, stored.clientSecret)
        assertTrue(connectionSettings.getAll().values.none { it.contains(SECRET) })

        assertFalse(overview.render(scope()).encoded().contains(SECRET))
        assertFalse(contribution(CredentialServerPages.CLIENT).render(scope(params = mapOf("id" to "c1"))).encoded().contains(SECRET))
    }

    @Test
    fun `a created client secret is revealed once on its page`() = runBlocking {
        connect()
        val result = contribution(CredentialServerPages.OVERVIEW).invoke(scope(), "createClient", mapOf("clientName" to UiValue.of("Laptop")))
        assertEquals(UiAction.OpenPage(CredentialServerPages.CLIENT, mapOf("id" to "c1")), result.next)
        assertFalse(result.message.orEmpty().contains(SECRET))

        val page = contribution(CredentialServerPages.CLIENT)
        assertTrue(page.render(scope(params = mapOf("id" to "c1"))).encoded().contains(SECRET))
        assertFalse(page.render(scope(params = mapOf("id" to "c1"))).encoded().contains(SECRET))
        assertNull(connection.current()?.clientSecret)
    }

    companion object {
        private const val ADMIN_KEY = "admin-key"
        private const val SECRET = "very-secret-client-secret"
    }
}
