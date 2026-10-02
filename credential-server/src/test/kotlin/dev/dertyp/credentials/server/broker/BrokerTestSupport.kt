package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class FakeSecretRepository : SecretRepository {
    val secrets = ConcurrentHashMap<String, StoredSecret>()
    val states = ConcurrentHashMap<String, CredentialStateUpdate>()
    val saves = CopyOnWriteArrayList<Pair<String, StoredSecret>>()

    fun put(name: String, secret: StoredSecret) {
        secrets[name] = secret
    }

    override fun kind(name: String): CredentialKind? = secrets[name]?.kind

    override fun loadSecret(name: String): StoredSecret? = secrets[name]

    override fun saveSecret(name: String, secret: StoredSecret, state: CredentialStateUpdate) {
        secrets[name] = secret
        states[name] = state
        saves += name to secret
    }

    override fun updateState(name: String, state: CredentialStateUpdate) {
        states[name] = state
    }
}

data class RecordedRequest(val url: String, val authorization: String?, val form: Parameters)

class MockUpstream(
    private val handler: suspend MockRequestHandleScope.(RecordedRequest) -> HttpResponseData,
) {
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    val client = HttpClient(MockEngine { request ->
        val recorded = record(request)
        requests += recorded
        handler(recorded)
    })

    private fun record(request: HttpRequestData): RecordedRequest {
        val body = when (val content = request.body) {
            is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
            is TextContent -> content.text
            else -> ""
        }
        return RecordedRequest(
            url = request.url.toString(),
            authorization = request.headers[HttpHeaders.Authorization],
            form = parseQueryString(body),
        )
    }
}

fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
