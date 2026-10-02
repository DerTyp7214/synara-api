package dev.dertyp.services.credentials.admin

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.credentials.*
import dev.dertyp.services.credentials.CredentialCipher
import dev.dertyp.services.credentials.CredentialServerConnectionSource
import dev.dertyp.services.ui.PluginSettingsService
import dev.dertyp.services.ui.credentialserver.InMemoryPluginSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.server.config.MapApplicationConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

class CredentialServerAdminClientTest {
    private val json = CredentialJson.json
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val cipher = CredentialCipher(MapApplicationConfig("credentials.encryptionKey" to "test-key"))
    private val settings = InMemoryPluginSettings()
    private val settingsService = mockk<PluginSettingsService> {
        every { forPlugin(CredentialServerConnectionSource.PLUGIN_ID) } returns settings
    }
    private val connection = CredentialServerConnectionSource(settingsService, MapApplicationConfig(), cipher)

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): CredentialServerAdminClient {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        val http = HttpClient(engine) { install(HttpTimeout) }
        val factory = mockk<HttpClientFactory>()
        every { factory.shared<HttpClientEngineConfig>(HttpClientFactory.CREDENTIAL_SERVER_ADMIN, any(), any(), any()) } returns http
        return CredentialServerAdminClient(connection, factory)
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf("Content-Type", ContentType.Application.Json.toString()))

    private fun configure(adminKey: String? = ADMIN_KEY) = runBlocking {
        connection.store(mapOf(CredentialServerConnectionSource.KEY_URL to "https://creds.example.com/", CredentialServerConnectionSource.KEY_ADMIN_KEY to adminKey))
    }

    private val summary = ClientSummary(
        id = "c1",
        clientId = "synara-home",
        name = "Home",
        enabled = true,
        tokenVersion = 1,
        createdAt = 1,
        lastTokenAt = null,
        grants = listOf(GrantInfo(CredentialNames.TIDAL_API, CredentialKind.OAUTH_CLIENT_CREDENTIALS)),
    )

    @Test
    fun `lists clients with the stored admin key header`() = runBlocking {
        configure()
        val admin = client { json(json.encodeToString(listOf(summary))) }

        val clients = admin.listClients()

        assertEquals(listOf(summary), clients)
        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("https://creds.example.com/admin/clients", request.url.toString())
        assertEquals(ADMIN_KEY, request.headers[CredentialProtocol.ADMIN_KEY_HEADER])
    }

    @Test
    fun `lists credentials and presets`() = runBlocking {
        configure()
        val credential = CredentialSummary(
            name = CredentialNames.IMPORTER_TIDDL,
            kind = CredentialKind.TIDAL_DEVICE_SESSION,
            description = null,
            status = CredentialStatus.NEEDS_LOGIN,
            statusMessage = "login",
            expiresAt = 5,
            updatedAt = 4,
            grantedTo = listOf("synara-home"),
        )
        val preset = CredentialPreset(CredentialNames.IMPORTER_GAMDL, CredentialKind.FILE, "gamdl", fileRoles = listOf(CredentialFileRoles.GAMDL_COOKIES))
        val admin = client { request ->
            when (request.url.encodedPath) {
                "/admin/credentials" -> json(json.encodeToString(listOf(credential)))
                "/admin/presets" -> json(json.encodeToString(listOf(preset)))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        assertEquals(listOf(credential), admin.listCredentials())
        assertEquals(listOf(preset), admin.presets())
        assertTrue(requests.all { it.headers[CredentialProtocol.ADMIN_KEY_HEADER] == ADMIN_KEY })
    }

    @Test
    fun `mutations send json bodies and emit a change`() = runBlocking {
        configure()
        val admin = client { json(json.encodeToString(summary)) }
        val change = async { admin.changes().first() }
        yield()

        admin.setGrants("c1", listOf(GrantSpec(CredentialNames.IMPORTER_TIDDL, writeBack = true)))

        change.await()
        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/admin/clients/c1/grants", request.url.encodedPath)
        val body = json.decodeFromString<SetGrantsRequest>((request.body as TextContent).text)
        assertEquals(listOf(GrantSpec(CredentialNames.IMPORTER_TIDDL, writeBack = true)), body.grants)
    }

    @Test
    fun `errors are parsed from the credential error body`() = runBlocking {
        configure()
        val admin = client { json(json.encodeToString(CredentialError(CredentialErrorCode.NOT_FOUND, "no such client")), HttpStatusCode.NotFound) }

        val error = assertThrows<CredentialServerAdminException> { admin.getClient("missing") }

        assertEquals(HttpStatusCode.NotFound, error.status)
        assertEquals(CredentialErrorCode.NOT_FOUND, error.error?.code)
        assertEquals("no such client", error.message)
    }

    @Test
    fun `admin calls fail without an admin key while health works`() = runBlocking {
        configure(adminKey = null)
        val admin = client { json(json.encodeToString(CredentialServerHealth(true, CredentialProtocol.PROTOCOL_VERSION, "1.0"))) }

        assertThrows<CredentialServerAdminException> { admin.listClients() }
        assertEquals("1.0", admin.health().version)
        val request = requests.single()
        assertEquals("/health", request.url.encodedPath)
        assertNull(request.headers[CredentialProtocol.ADMIN_KEY_HEADER])
    }

    @Test
    fun `tidal login events are read line by line until a final state`() = runBlocking {
        configure()
        val lines = listOf(
            TidalLoginEvent(TidalLoginState.PENDING),
            TidalLoginEvent(TidalLoginState.PENDING, "waiting"),
            TidalLoginEvent(TidalLoginState.COMPLETED),
        ).joinToString("\n") { json.encodeToString(it) } + "\n"
        val admin = client { respond(lines, HttpStatusCode.OK, headersOf("Content-Type", "application/x-ndjson")) }

        val events = admin.tidalLoginEvents("login1").toList()

        assertEquals(listOf(TidalLoginState.PENDING, TidalLoginState.PENDING, TidalLoginState.COMPLETED), events.map { it.state })
        assertEquals("waiting", events[1].message)
        assertEquals("/admin/tidal-logins/login1/events", requests.single().url.encodedPath)
        assertEquals(ADMIN_KEY, requests.single().headers[CredentialProtocol.ADMIN_KEY_HEADER])
    }

    companion object {
        private const val ADMIN_KEY = "admin-key-123"
    }
}
