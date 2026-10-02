package dev.dertyp.credentials.server.routes

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.CredentialServerDeps
import dev.dertyp.credentials.server.credentialServerModule
import dev.dertyp.credentials.server.jsonClient
import dev.dertyp.credentials.server.testDeps
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CredentialRoutesTest {
    @TempDir
    lateinit var dir: Path

    private fun routesTest(
        deps: CredentialServerDeps = testDeps(dir),
        block: suspend ApplicationTestBuilder.(CredentialServerDeps, HttpClient) -> Unit,
    ) = deps.use {
        testApplication {
            application { credentialServerModule(deps) }
            block(deps, jsonClient())
        }
    }

    private suspend fun HttpClient.admin(
        method: String,
        path: String,
        body: Any? = null,
        key: String? = "test-admin-key",
    ): HttpResponse {
        val url = "${CredentialProtocol.ADMIN_PREFIX}$path"
        val configure: HttpRequestBuilder.() -> Unit = {
            if (key != null) header(CredentialProtocol.ADMIN_KEY_HEADER, key)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(url, configure)
            "POST" -> post(url, configure)
            "PUT" -> put(url, configure)
            "PATCH" -> patch(url, configure)
            "DELETE" -> delete(url, configure)
            else -> error("Unsupported method $method")
        }
    }

    private suspend fun HttpClient.token(clientId: String, secret: String): HttpResponse =
        post(CredentialProtocol.TOKEN_PATH) {
            contentType(ContentType.Application.Json)
            setBody(TokenRequest(clientId, secret))
        }

    private suspend fun HttpClient.accessToken(created: CreatedClient): String =
        token(created.client.clientId, created.clientSecret).body<TokenResponse>().accessToken

    private suspend fun HttpClient.fetch(name: String, token: String): HttpResponse =
        get(CredentialProtocol.credentialPath(name)) { bearerAuth(token) }

    private suspend fun HttpClient.putApiKey(name: String, key: String) {
        val response = admin(
            "PUT",
            "/credentials/$name",
            UpsertCredentialRequest(CredentialKind.API_KEY, "desc", CredentialInput.ApiKeyInput(key)),
        )
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun HttpClient.createClient(name: String, vararg grants: GrantSpec): CreatedClient {
        val response = admin("POST", "/clients", CreateClientRequest(name, grants.toList()))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun `health answers without auth`() = routesTest { _, client ->
        val health = client.get(CredentialProtocol.HEALTH_PATH).body<CredentialServerHealth>()
        assertTrue(health.ok)
        assertEquals(CredentialProtocol.PROTOCOL_VERSION, health.protocolVersion)
    }

    @Test
    fun `token exchange accepts only valid enabled clients`() = routesTest { _, client ->
        client.putApiKey("youtube.api", "yt-key")
        val created = client.createClient("main", GrantSpec("youtube.api"))

        val ok = client.token(created.client.clientId, created.clientSecret)
        assertEquals(HttpStatusCode.OK, ok.status)
        val token = ok.body<TokenResponse>()
        assertEquals("Bearer", token.tokenType)
        assertEquals(listOf(GrantInfo("youtube.api", CredentialKind.API_KEY)), token.grants)
        assertTrue(token.expiresAt > System.currentTimeMillis())

        val wrong = client.token(created.client.clientId, created.clientSecret + "x")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals(CredentialErrorCode.UNAUTHORIZED, wrong.body<CredentialError>().code)

        assertEquals(HttpStatusCode.Unauthorized, client.token("syn_0000000000000000", created.clientSecret).status)

        client.admin("PATCH", "/clients/${created.client.id}", UpdateClientRequest(enabled = false))
        assertEquals(HttpStatusCode.Unauthorized, client.token(created.client.clientId, created.clientSecret).status)

        val summary = client.admin("GET", "/clients/${created.client.id}").body<ClientSummary>()
        assertTrue(summary.lastTokenAt != null)
    }

    @Test
    fun `consumers only fetch granted credentials`() = routesTest { _, client ->
        client.putApiKey("youtube.api", "yt-key")
        client.putApiKey("acoustid.api", "acoustid-key")
        val created = client.createClient("main", GrantSpec("youtube.api"))
        val token = client.accessToken(created)

        val granted = client.fetch("youtube.api", token)
        assertEquals(HttpStatusCode.OK, granted.status)
        assertEquals(ResolvedCredential.ApiKey("youtube.api", "yt-key"), granted.body<ResolvedCredential>())

        val denied = client.fetch("acoustid.api", token)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertEquals(CredentialErrorCode.NOT_GRANTED, denied.body<CredentialError>().code)

        val list = client.get(CredentialProtocol.CREDENTIALS_PATH) { bearerAuth(token) }.body<List<GrantInfo>>()
        assertEquals(listOf(GrantInfo("youtube.api", CredentialKind.API_KEY)), list)

        val writeBack = client.put(CredentialProtocol.writeBackPath("youtube.api")) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(WriteBackRequest(null, emptyList()))
        }
        assertEquals(HttpStatusCode.Forbidden, writeBack.status)

        assertEquals(HttpStatusCode.Unauthorized, client.get(CredentialProtocol.CREDENTIALS_PATH).status)
        assertEquals(HttpStatusCode.Unauthorized, client.fetch("youtube.api", "garbage").status)
    }

    @Test
    fun `revoke, grant change and secret rotation invalidate issued tokens immediately`() = routesTest { _, client ->
        client.putApiKey("youtube.api", "yt-key")
        client.putApiKey("acoustid.api", "acoustid-key")
        val created = client.createClient("main", GrantSpec("youtube.api"))
        val id = created.client.id

        val revokedToken = client.accessToken(created)
        assertEquals(HttpStatusCode.OK, client.fetch("youtube.api", revokedToken).status)
        assertEquals(HttpStatusCode.OK, client.admin("POST", "/clients/$id/revoke-tokens").status)
        assertEquals(HttpStatusCode.Unauthorized, client.fetch("youtube.api", revokedToken).status)

        val grantToken = client.accessToken(created)
        assertEquals(HttpStatusCode.OK, client.fetch("youtube.api", grantToken).status)
        val regranted = client.admin(
            "PUT",
            "/clients/$id/grants",
            SetGrantsRequest(listOf(GrantSpec("youtube.api"), GrantSpec("acoustid.api"))),
        )
        assertEquals(HttpStatusCode.OK, regranted.status)
        assertEquals(HttpStatusCode.Unauthorized, client.fetch("youtube.api", grantToken).status)

        val rotationToken = client.accessToken(created)
        assertEquals(HttpStatusCode.OK, client.fetch("acoustid.api", rotationToken).status)
        val rotated = client.admin("POST", "/clients/$id/rotate-secret").body<CreatedClient>()
        assertEquals(HttpStatusCode.Unauthorized, client.fetch("youtube.api", rotationToken).status)
        assertEquals(HttpStatusCode.Unauthorized, client.token(created.client.clientId, created.clientSecret).status)
        assertEquals(HttpStatusCode.OK, client.fetch("youtube.api", client.accessToken(rotated)).status)

        val disableToken = client.accessToken(rotated)
        client.admin("PATCH", "/clients/$id", UpdateClientRequest(enabled = false))
        assertEquals(HttpStatusCode.Unauthorized, client.fetch("youtube.api", disableToken).status)
    }

    @Test
    fun `admin routes require the admin key`() = routesTest { _, client ->
        val missing = client.admin("GET", "/clients", key = null)
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals(CredentialErrorCode.UNAUTHORIZED, missing.body<CredentialError>().code)
        assertEquals(HttpStatusCode.Unauthorized, client.admin("GET", "/clients", key = "wrong").status)
        assertEquals(HttpStatusCode.OK, client.admin("GET", "/clients").status)
    }

    @Test
    fun `admin routes answer 503 when no admin key is configured`() = routesTest(testDeps(dir, adminKey = "")) { _, client ->
        assertEquals(HttpStatusCode.ServiceUnavailable, client.admin("GET", "/clients", key = null).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.admin("GET", "/clients", key = "").status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.admin("GET", "/clients", key = "anything").status)
        assertEquals(HttpStatusCode.OK, client.get(CredentialProtocol.HEALTH_PATH).status)
    }

    @Test
    fun `secrets never appear in listings`() = routesTest { _, client ->
        client.putApiKey("youtube.api", "super-secret-api-key")
        val created = client.createClient("main", GrantSpec("youtube.api"))
        val rotated = client.admin("POST", "/clients/${created.client.id}/rotate-secret").body<CreatedClient>()

        val listings = listOf(
            client.admin("GET", "/clients").bodyAsText(),
            client.admin("GET", "/clients/${created.client.id}").bodyAsText(),
            client.admin("GET", "/credentials").bodyAsText(),
            client.admin("GET", "/credentials/youtube.api").bodyAsText(),
        )
        listings.forEach { body ->
            assertFalse(body.contains(created.clientSecret))
            assertFalse(body.contains(rotated.clientSecret))
            assertFalse(body.contains("super-secret-api-key"))
            assertFalse(body.contains("secretHash"))
        }
        val credentials = client.admin("GET", "/credentials").body<List<CredentialSummary>>()
        assertEquals(listOf(created.client.clientId), credentials.single().grantedTo)
    }

    @Test
    fun `admin manages credentials and clients`() = routesTest { _, client ->
        val invalid = client.admin(
            "PUT",
            "/credentials/Bad Name",
            UpsertCredentialRequest(CredentialKind.API_KEY, null, CredentialInput.ApiKeyInput("x")),
        )
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(CredentialErrorCode.INVALID, invalid.body<CredentialError>().code)

        client.putApiKey("youtube.api", "first")
        val kept = client.admin(
            "PUT",
            "/credentials/youtube.api",
            UpsertCredentialRequest(CredentialKind.API_KEY, "updated", CredentialInput.ApiKeyInput("")),
        ).body<CredentialSummary>()
        assertEquals("updated", kept.description)

        val created = client.createClient("main", GrantSpec("youtube.api"))
        val fetched = client.fetch("youtube.api", client.accessToken(created)).body<ResolvedCredential>()
        assertEquals(ResolvedCredential.ApiKey("youtube.api", "first"), fetched)

        val test = client.admin("POST", "/credentials/youtube.api/test").body<CredentialTestResult>()
        assertTrue(test.ok)
        assertEquals(HttpStatusCode.NotFound, client.admin("POST", "/credentials/missing.api/test").status)
        assertTrue(client.admin("GET", "/presets").body<List<CredentialPreset>>().isNotEmpty())

        val unknownGrant = client.admin(
            "PUT",
            "/clients/${created.client.id}/grants",
            SetGrantsRequest(listOf(GrantSpec("missing.api"))),
        )
        assertEquals(HttpStatusCode.NotFound, unknownGrant.status)

        assertEquals(HttpStatusCode.NoContent, client.admin("DELETE", "/credentials/youtube.api").status)
        assertEquals(HttpStatusCode.NotFound, client.admin("GET", "/credentials/youtube.api").status)
        assertEquals(emptyList<GrantInfo>(), client.admin("GET", "/clients/${created.client.id}").body<ClientSummary>().grants)

        assertEquals(HttpStatusCode.NoContent, client.admin("DELETE", "/clients/${created.client.clientId}").status)
        assertEquals(HttpStatusCode.NotFound, client.admin("GET", "/clients/${created.client.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.admin("GET", "/tidal-logins/unknown").status)

        val rotation = client.admin("POST", "/signing-keys/rotate")
        assertEquals(HttpStatusCode.OK, rotation.status)
        assertTrue(rotation.body<SigningKeyRotation>().kid.isNotBlank())
    }

    @Test
    fun `tidal login streams events as ndjson until completion`() {
        val upstream = HttpClient(MockEngine { request ->
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath.endsWith("device_authorization")) {
                respond(
                    """{"deviceCode":"dev","userCode":"ABCD","verificationUri":"link.tidal.com","expiresIn":60,"interval":1}""",
                    HttpStatusCode.OK,
                    json,
                )
            } else {
                respond(
                    """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"user":{"userId":"42","countryCode":"DE"}}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
        })
        routesTest(testDeps(dir, httpClient = upstream)) { deps, client ->
            val session = client.admin(
                "POST",
                "/credentials/importer.tiddl/tidal-login",
                TidalLoginStart(TidalSessionFormat.TIDDL, "client-id", "client-secret"),
            )
            assertEquals(HttpStatusCode.OK, session.status)
            val started = session.body<TidalLoginSession>()
            assertEquals("ABCD", started.userCode)
            assertEquals("https://link.tidal.com", started.verificationUri)

            val stream = client.admin("GET", "/tidal-logins/${started.loginId}/events")
            assertEquals(HttpStatusCode.OK, stream.status)
            assertTrue(stream.headers[HttpHeaders.ContentType]!!.startsWith("application/x-ndjson"))
            val events = stream.bodyAsText().lines().filter { it.isNotBlank() }
                .map { CredentialJson.json.decodeFromString(TidalLoginEvent.serializer(), it) }
            assertEquals(TidalLoginState.COMPLETED, events.last().state)
            assertTrue(events.dropLast(1).all { it.state == TidalLoginState.PENDING })

            val snapshot = client.admin("GET", "/tidal-logins/${started.loginId}").body<TidalLoginEvent>()
            assertEquals(TidalLoginState.COMPLETED, snapshot.state)
            assertEquals(CredentialKind.TIDAL_DEVICE_SESSION, deps.store.credential("importer.tiddl")!!.kind)
        }
    }
}
