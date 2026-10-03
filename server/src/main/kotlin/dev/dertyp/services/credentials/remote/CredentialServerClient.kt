package dev.dertyp.services.credentials.remote

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.timeouts
import dev.dertyp.core.userAgent
import dev.dertyp.credentials.CredentialError
import dev.dertyp.credentials.CredentialJson
import dev.dertyp.credentials.CredentialProtocol
import dev.dertyp.credentials.CredentialServerHealth
import dev.dertyp.credentials.GrantInfo
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.TokenRequest
import dev.dertyp.credentials.TokenResponse
import dev.dertyp.credentials.WriteBackRequest
import dev.dertyp.credentials.WriteBackResult
import dev.dertyp.services.credentials.CredentialServerConnection
import dev.dertyp.services.credentials.CredentialServerConnectionSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

class CredentialServerException(val status: HttpStatusCode, val error: CredentialError?) :
    RuntimeException("Credential server answered $status${error?.let { ": ${it.code} ${it.message}" } ?: ""}")

class CredentialServerClient(
    private val httpClientFactory: HttpClientFactory,
    connectionSource: CredentialServerConnectionSource,
) {
    private val logger = KtorSimpleLogger("CredentialServerClient")
    private val tokenMutex = Mutex()
    private val warnedUrls = ConcurrentHashMap.newKeySet<String>()

    private val _connection = MutableStateFlow(connectionSource.fromEnvironment() ?: CredentialServerConnection.NONE)
    val connection: StateFlow<CredentialServerConnection> = _connection.asStateFlow()

    private val _grants = MutableStateFlow<Map<String, GrantInfo>>(emptyMap())
    val grants: StateFlow<Map<String, GrantInfo>> = _grants.asStateFlow()

    @Volatile
    private var token: TokenResponse? = null

    private val httpClient: HttpClient
        get() = httpClientFactory.shared(HttpClientFactory.CREDENTIAL_SERVER, CIO) {
            userAgent("Synara/CredentialServer")
            timeouts(request = 30.seconds, connect = 10.seconds, socket = 30.seconds)
        }

    init {
        warnIfInsecure(_connection.value)
    }

    suspend fun updateConnection(connection: CredentialServerConnection): Boolean = tokenMutex.withLock {
        if (_connection.value == connection) return@withLock false
        _connection.value = connection
        token = null
        _grants.value = emptyMap()
        warnIfInsecure(connection)
        true
    }

    suspend fun exchangeToken(force: Boolean = false): TokenResponse? = tokenMutex.withLock {
        val connection = _connection.value
        val baseUrl = connection.baseUrl
        if (!connection.consumerConfigured || baseUrl == null) {
            token = null
            _grants.value = emptyMap()
            return@withLock null
        }
        val cached = token
        if (!force && cached != null && valid(cached)) return@withLock cached
        val response = httpClient.post(baseUrl + CredentialProtocol.TOKEN_PATH) {
            setBody(json(TokenRequest.serializer(), TokenRequest(connection.clientId!!, connection.clientSecret!!)))
        }
        if (!response.status.isSuccess()) {
            token = null
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) _grants.value =
                emptyMap()
            throw failure(response)
        }
        val parsed = CredentialJson.json.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
        token = parsed
        _grants.value = parsed.grants.associateBy { it.name }
        parsed
    }

    suspend fun fetch(name: String): ResolvedCredential {
        val response = authorized { bearer, baseUrl ->
            httpClient.get(baseUrl + CredentialProtocol.credentialPath(name)) { bearerAuth(bearer) }
        }
        if (!response.status.isSuccess()) {
            if (response.status == HttpStatusCode.Forbidden) refreshGrants()
            throw failure(response)
        }
        return CredentialJson.json.decodeFromString(ResolvedCredential.serializer(), response.bodyAsText())
    }

    suspend fun writeBack(name: String, request: WriteBackRequest): WriteBackResult? {
        val response = authorized { bearer, baseUrl ->
            httpClient.put(baseUrl + CredentialProtocol.writeBackPath(name)) {
                bearerAuth(bearer)
                setBody(json(WriteBackRequest.serializer(), request))
            }
        }
        if (response.status == HttpStatusCode.Conflict) return null
        if (!response.status.isSuccess()) throw failure(response)
        return CredentialJson.json.decodeFromString(WriteBackResult.serializer(), response.bodyAsText())
    }

    suspend fun health(): CredentialServerHealth? {
        val baseUrl = _connection.value.baseUrl ?: return null
        val response = httpClient.get(baseUrl + CredentialProtocol.HEALTH_PATH)
        if (!response.status.isSuccess()) return null
        return CredentialJson.json.decodeFromString(CredentialServerHealth.serializer(), response.bodyAsText())
    }

    private suspend fun refreshGrants() {
        try {
            exchangeToken(force = true)
        } catch (e: CredentialServerException) {
            logger.warn("Refreshing credential server grants failed: ${e.message}")
        }
    }

    private suspend fun authorized(call: suspend (String, String) -> HttpResponse): HttpResponse {
        val first = exchangeToken() ?: throw IllegalStateException("Credential server connection is not configured")
        val baseUrl =
            _connection.value.baseUrl ?: throw IllegalStateException("Credential server connection is not configured")
        val response = call(first.accessToken, baseUrl)
        if (response.status != HttpStatusCode.Unauthorized) return response
        val renewed = exchangeToken(force = true) ?: return response
        return call(renewed.accessToken, baseUrl)
    }

    private fun valid(token: TokenResponse): Boolean = System.currentTimeMillis() < token.expiresAt - EXPIRY_MARGIN_MS

    private suspend fun failure(response: HttpResponse): CredentialServerException {
        val error = try {
            CredentialJson.json.decodeFromString(CredentialError.serializer(), response.bodyAsText())
        } catch (_: Exception) {
            null
        }
        return CredentialServerException(response.status, error)
    }

    private fun <T> json(serializer: KSerializer<T>, value: T) =
        TextContent(CredentialJson.json.encodeToString(serializer, value), ContentType.Application.Json)

    private fun warnIfInsecure(connection: CredentialServerConnection) {
        val baseUrl = connection.baseUrl ?: return
        val url = try {
            Url(baseUrl)
        } catch (_: Exception) {
            return
        }
        if (url.protocol.name != "http" || url.host in LOCAL_HOSTS || url.host.startsWith("127.")) return
        if (warnedUrls.add(baseUrl)) {
            logger.warn("The credential server at $baseUrl is reached over plain http, credentials travel unencrypted. Put TLS in front of it.")
        }
    }

    companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
        private val LOCAL_HOSTS = setOf("localhost", "::1", "[::1]", "0:0:0:0:0:0:0:1")
    }
}
