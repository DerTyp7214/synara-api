package dev.dertyp.services.credentials

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.credentials.ClientSummary
import dev.dertyp.credentials.CredentialError
import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialJson
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialProtocol
import dev.dertyp.credentials.GrantInfo
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.SetGrantsRequest
import dev.dertyp.credentials.TokenRequest
import dev.dertyp.credentials.TokenResponse
import dev.dertyp.credentials.WriteBackRequest
import dev.dertyp.credentials.WriteBackResult
import dev.dertyp.services.credentials.admin.CredentialServerAdminClient
import dev.dertyp.services.credentials.remote.CredentialServerClient
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.credentialserver.InMemoryPluginSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class FakeCredentialServer(grants: List<GrantInfo> = emptyList()) {
    val tokenCalls = AtomicInteger()
    val fetchCalls = ConcurrentHashMap<String, AtomicInteger>()
    val bearers = CopyOnWriteArrayList<String>()
    val writeBacks = CopyOnWriteArrayList<Pair<String, WriteBackRequest>>()
    val credentials = ConcurrentHashMap<String, ResolvedCredential>()

    @Volatile
    var grants: List<GrantInfo> = grants

    @Volatile
    var tokenTtlMs: Long = 15 * 60_000L

    @Volatile
    var unauthorizedFetches = 0

    @Volatile
    var unreachable = false

    @Volatile
    var writeBackConflict = false

    val engine = MockEngine { request ->
        if (unreachable) throw IOException("Connection refused")
        val path = request.url.encodedPath
        when {
            path == CredentialProtocol.TOKEN_PATH && request.method == HttpMethod.Post -> {
                val body = CredentialJson.json.decodeFromString<TokenRequest>((request.body as TextContent).text)
                if (body.clientId != CLIENT_ID || body.clientSecret != CLIENT_SECRET) {
                    return@MockEngine failure(HttpStatusCode.Unauthorized, CredentialErrorCode.UNAUTHORIZED)
                }
                val number = tokenCalls.incrementAndGet()
                json(
                    CredentialJson.json.encodeToString(
                        TokenResponse.serializer(),
                        TokenResponse("token-$number", expiresAt = System.currentTimeMillis() + tokenTtlMs, grants = this@FakeCredentialServer.grants),
                    )
                )
            }
            path.startsWith(CredentialProtocol.ADMIN_PREFIX + "/") -> {
                if (request.headers[CredentialProtocol.ADMIN_KEY_HEADER] != ADMIN_KEY) {
                    return@MockEngine failure(HttpStatusCode.Unauthorized, CredentialErrorCode.UNAUTHORIZED)
                }
                if (!path.endsWith("/grants") || request.method != HttpMethod.Put) {
                    return@MockEngine respond("not found", HttpStatusCode.NotFound)
                }
                val body = CredentialJson.json.decodeFromString<SetGrantsRequest>((request.body as TextContent).text)
                val current = this@FakeCredentialServer.grants
                this@FakeCredentialServer.grants = body.grants.map { spec ->
                    GrantInfo(spec.name, current.firstOrNull { it.name == spec.name }?.kind ?: CredentialKind.API_KEY, spec.writeBack)
                }
                val id = path.removePrefix(CredentialProtocol.ADMIN_PREFIX + "/clients/").removeSuffix("/grants")
                json(
                    CredentialJson.json.encodeToString(
                        ClientSummary.serializer(),
                        ClientSummary(id, CLIENT_ID, "Synara", true, 1, 0, null, this@FakeCredentialServer.grants),
                    )
                )
            }
            path.startsWith(CredentialProtocol.CREDENTIALS_PATH + "/") && path.endsWith("/files") && request.method == HttpMethod.Put -> {
                val name = path.removePrefix(CredentialProtocol.CREDENTIALS_PATH + "/").removeSuffix("/files")
                val body = CredentialJson.json.decodeFromString<WriteBackRequest>((request.body as TextContent).text)
                writeBacks += name to body
                if (writeBackConflict) failure(HttpStatusCode.Conflict, CredentialErrorCode.CONFLICT)
                else json(CredentialJson.json.encodeToString(WriteBackResult.serializer(), WriteBackResult("new-fingerprint")))
            }
            path.startsWith(CredentialProtocol.CREDENTIALS_PATH + "/") && request.method == HttpMethod.Get -> {
                val name = path.removePrefix(CredentialProtocol.CREDENTIALS_PATH + "/")
                bearers += request.headers[HttpHeaders.Authorization].orEmpty()
                fetchCalls.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
                if (unauthorizedFetches > 0) {
                    unauthorizedFetches--
                    return@MockEngine failure(HttpStatusCode.Unauthorized, CredentialErrorCode.UNAUTHORIZED)
                }
                if (this@FakeCredentialServer.grants.none { it.name == name }) {
                    return@MockEngine failure(HttpStatusCode.Forbidden, CredentialErrorCode.NOT_GRANTED)
                }
                val credential = credentials[name] ?: return@MockEngine failure(HttpStatusCode.NotFound, CredentialErrorCode.NOT_FOUND)
                json(CredentialJson.json.encodeToString(ResolvedCredential.serializer(), credential))
            }
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    fun fetches(name: String): Int = fetchCalls[name]?.get() ?: 0

    val httpClientFactory: HttpClientFactory = mockk<HttpClientFactory>().also {
        val client = HttpClient(engine)
        every { it.shared<HttpClientEngineConfig>(any(), any(), any(), any()) } returns client
    }

    fun connectionSource(
        url: String? = URL,
        clientId: String? = CLIENT_ID,
        clientSecret: String? = CLIENT_SECRET,
        adminKey: String? = ADMIN_KEY,
    ): CredentialServerConnectionSource {
        val config = MapApplicationConfig()
        url?.let { config.put("credentialServer.url", it) }
        clientId?.let { config.put("credentialServer.clientId", it) }
        clientSecret?.let { config.put("credentialServer.clientSecret", it) }
        adminKey?.let { config.put("credentialServer.adminKey", it) }
        val settings = InMemoryPluginSettings()
        val settingsService = mockk<PluginSettingsService> {
            every { forPlugin(CredentialServerConnectionSource.PLUGIN_ID) } returns settings
        }
        return CredentialServerConnectionSource(
            settingsService,
            config,
            CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key")),
        )
    }

    fun client(source: CredentialServerConnectionSource = connectionSource()) = CredentialServerClient(httpClientFactory, source)

    fun admin(source: CredentialServerConnectionSource = connectionSource()) = CredentialServerAdminClient(source, httpClientFactory)

    fun provider(
        client: CredentialServerClient = client(),
        source: CredentialServerConnectionSource = connectionSource(),
        admin: CredentialServerAdminClient = admin(source),
    ) = RemoteCredentialProvider(client, source, admin)

    private fun MockRequestHandleScope.json(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.failure(status: HttpStatusCode, code: CredentialErrorCode): HttpResponseData =
        respond(
            CredentialJson.json.encodeToString(CredentialError.serializer(), CredentialError(code, code.name)),
            status,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )

    companion object {
        const val URL = "http://localhost:8083"
        const val CLIENT_ID = "synara"
        const val CLIENT_SECRET = "secret"
        const val ADMIN_KEY = "admin-key"

        fun grant(name: String, kind: CredentialKind = CredentialKind.API_KEY, writeBack: Boolean = false) =
            GrantInfo(name, kind, writeBack)
    }
}
