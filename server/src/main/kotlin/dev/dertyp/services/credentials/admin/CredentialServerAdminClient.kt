package dev.dertyp.services.credentials.admin

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.timeouts
import dev.dertyp.core.userAgent
import dev.dertyp.credentials.*
import dev.dertyp.services.credentials.CredentialServerConnection
import dev.dertyp.services.credentials.CredentialServerConnectionSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.seconds

class CredentialServerAdminException(
    val status: HttpStatusCode?,
    val error: CredentialError?,
    message: String,
) : RuntimeException(message)

class CredentialServerAdminClient(
    private val connection: CredentialServerConnectionSource,
    private val httpClientFactory: HttpClientFactory,
) {
    private val json = CredentialJson.json
    private val mutations = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    private val httpClient: HttpClient
        get() = httpClientFactory.shared(HttpClientFactory.CREDENTIAL_SERVER_ADMIN, CIO) {
            userAgent("Synara/CredentialServerAdmin")
            timeouts(request = 30.seconds, connect = 10.seconds, socket = 30.seconds)
        }

    fun changes(): SharedFlow<Unit> = mutations.asSharedFlow()

    suspend fun health(): CredentialServerHealth =
        decode(send(HttpMethod.Get, CredentialProtocol.HEALTH_PATH, admin = false))

    suspend fun listClients(): List<ClientSummary> = decode(send(HttpMethod.Get, CLIENTS))

    suspend fun getClient(id: String): ClientSummary = decode(send(HttpMethod.Get, client(id)))

    suspend fun createClient(request: CreateClientRequest): CreatedClient =
        mutate { decode(send(HttpMethod.Post, CLIENTS, json.encodeToString(request))) }

    suspend fun updateClient(id: String, request: UpdateClientRequest): ClientSummary =
        mutate { decode(send(HttpMethod.Patch, client(id), json.encodeToString(request))) }

    suspend fun deleteClient(id: String) = mutate { send(HttpMethod.Delete, client(id)).discard() }

    suspend fun rotateSecret(id: String): CreatedClient =
        mutate { decode(send(HttpMethod.Post, "${client(id)}/rotate-secret")) }

    suspend fun revokeTokens(id: String) = mutate { send(HttpMethod.Post, "${client(id)}/revoke-tokens").discard() }

    suspend fun setGrants(id: String, grants: List<GrantSpec>): ClientSummary =
        mutate { decode(send(HttpMethod.Put, "${client(id)}/grants", json.encodeToString(SetGrantsRequest(grants)))) }

    suspend fun listCredentials(): List<CredentialSummary> = decode(send(HttpMethod.Get, CREDENTIALS))

    suspend fun getCredential(name: String): CredentialSummary = decode(send(HttpMethod.Get, credential(name)))

    suspend fun upsertCredential(name: String, request: UpsertCredentialRequest): CredentialSummary =
        mutate { decode(send(HttpMethod.Put, credential(name), json.encodeToString(request))) }

    suspend fun deleteCredential(name: String) = mutate { send(HttpMethod.Delete, credential(name)).discard() }

    suspend fun testCredential(name: String): CredentialTestResult =
        mutate { decode(send(HttpMethod.Post, "${credential(name)}/test")) }

    suspend fun presets(): List<CredentialPreset> = decode(send(HttpMethod.Get, "${CredentialProtocol.ADMIN_PREFIX}/presets"))

    suspend fun startTidalLogin(name: String, request: TidalLoginStart): TidalLoginSession =
        decode(send(HttpMethod.Post, "${credential(name)}/tidal-login", json.encodeToString(request)))

    suspend fun tidalLogin(loginId: String): TidalLoginEvent = decode(send(HttpMethod.Get, tidalLoginPath(loginId)))

    suspend fun cancelTidalLogin(loginId: String) = mutate { send(HttpMethod.Delete, tidalLoginPath(loginId)).discard() }

    fun tidalLoginEvents(loginId: String): Flow<TidalLoginEvent> = flow {
        val (baseUrl, adminKey) = target(admin = true)
        httpClient.prepareRequest {
            method = HttpMethod.Get
            url(baseUrl + "${tidalLoginPath(loginId)}/events")
            adminKey?.let { header(CredentialProtocol.ADMIN_KEY_HEADER, it) }
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.execute { response ->
            ensureSuccess(response)
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readLine() ?: break
                if (line.isBlank()) continue
                val event = json.decodeFromString<TidalLoginEvent>(line)
                emit(event)
                if (event.state != TidalLoginState.PENDING) {
                    mutations.tryEmit(Unit)
                    break
                }
            }
        }
    }

    private suspend fun <T> mutate(block: suspend () -> T): T = block().also { mutations.emit(Unit) }

    private suspend fun target(admin: Boolean): Pair<String, String?> {
        val current = connection.current() ?: CredentialServerConnection.NONE
        val baseUrl = current.baseUrl
            ?: throw CredentialServerAdminException(null, null, "No credential server URL is configured")
        if (!admin) return baseUrl to null
        val adminKey = current.adminKey?.takeIf { it.isNotBlank() }
            ?: throw CredentialServerAdminException(null, null, "No credential server admin key is configured")
        return baseUrl to adminKey
    }

    private suspend fun send(method: HttpMethod, path: String, body: String? = null, admin: Boolean = true): HttpResponse {
        val (baseUrl, adminKey) = target(admin)
        val response = httpClient.request {
            this.method = method
            url(baseUrl + path)
            adminKey?.let { header(CredentialProtocol.ADMIN_KEY_HEADER, it) }
            body?.let { setJson(it) }
        }
        ensureSuccess(response)
        return response
    }

    private fun HttpRequestBuilder.setJson(body: String) {
        setBody(TextContent(body, ContentType.Application.Json))
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (response.status.isSuccess()) return
        val text = response.bodyAsText()
        val error = try {
            json.decodeFromString<CredentialError>(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        throw CredentialServerAdminException(response.status, error, error?.message ?: "Credential server returned ${response.status}")
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T = json.decodeFromString(response.bodyAsText())

    private suspend fun HttpResponse.discard() {
        bodyAsText()
    }

    private fun client(id: String) = "$CLIENTS/${id.encodeURLPathPart()}"

    private fun credential(name: String) = "$CREDENTIALS/${name.encodeURLPathPart()}"

    private fun tidalLoginPath(loginId: String) = "${CredentialProtocol.ADMIN_PREFIX}/tidal-logins/${loginId.encodeURLPathPart()}"

    companion object {
        private const val CLIENTS = "${CredentialProtocol.ADMIN_PREFIX}/clients"
        private const val CREDENTIALS = "${CredentialProtocol.ADMIN_PREFIX}/credentials"
    }
}
