package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.TidalLoginStart
import dev.dertyp.credentials.TidalLoginState
import dev.dertyp.credentials.TidalSessionFormat
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TidalDeviceLoginManagerTest {
    private val name = "importer.tiddl"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun deviceResponse(expiresIn: Int = 300) =
        """{"deviceCode":"dev-code","userCode":"ABCDE","verificationUri":"link.tidal.com","verificationUriComplete":"link.tidal.com/ABCDE","expiresIn":$expiresIn,"interval":1}"""

    @Test
    fun `pending polls continue until the login completes`() = runBlocking {
        val polls = AtomicInteger()
        val upstream = MockUpstream { request ->
            if (request.url == TidalAuthApi.DEVICE_AUTHORIZATION_URL) {
                json(deviceResponse())
            } else if (polls.incrementAndGet() < 2) {
                json("""{"status":400,"error":"authorization_pending","sub_status":1002}""", HttpStatusCode.BadRequest)
            } else {
                json(
                    """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600,"user_id":11,
                       "user":{"userId":11,"countryCode":"SE"}}""",
                )
            }
        }
        val repository = FakeSecretRepository()
        val manager = TidalDeviceLoginManager(repository, upstream.client, scope)

        val session = manager.start(name, TidalLoginStart(TidalSessionFormat.TIDDL, "client-x", "secret-x"))

        assertEquals("https://link.tidal.com", session.verificationUri)
        assertEquals("https://link.tidal.com/ABCDE", session.verificationUriComplete)
        assertEquals("ABCDE", session.userCode)
        assertTrue(session.expiresAt > System.currentTimeMillis())

        val events = manager.events(session.loginId)!!
        val done = withTimeout(10_000) { events.first { it.state != TidalLoginState.PENDING } }
        assertEquals(TidalLoginState.COMPLETED, done.state)

        val stored = assertIs<TidalSessionSecret>(repository.secrets[name])
        assertEquals(TidalSessionFormat.TIDDL, stored.format)
        assertEquals("client-x", stored.clientId)
        assertEquals("secret-x", stored.clientSecret)
        assertEquals("refresh-1", stored.refreshToken)
        assertEquals("11", stored.userId)
        assertEquals("SE", stored.countryCode)
        assertEquals(CredentialStatus.OK, repository.states[name]?.status)

        val deviceRequest = upstream.requests.first()
        assertEquals("client-x", deviceRequest.form["client_id"])
        assertEquals("r_usr+w_usr+w_sub", deviceRequest.form["scope"])
        assertNull(deviceRequest.authorization)
        val poll = upstream.requests.last()
        assertEquals(TidalAuthApi.TOKEN_URL, poll.url)
        assertEquals(TidalAuthApi.DEVICE_CODE_GRANT, poll.form["grant_type"])
        assertEquals("dev-code", poll.form["device_code"])
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("client-x:secret-x".toByteArray()),
            poll.authorization
        )
    }

    @Test
    fun `the login expires when the code times out`() = runBlocking {
        val upstream = MockUpstream { request ->
            if (request.url == TidalAuthApi.DEVICE_AUTHORIZATION_URL) json(deviceResponse(expiresIn = 2))
            else json("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)
        }
        val repository = FakeSecretRepository()
        val manager = TidalDeviceLoginManager(repository, upstream.client, scope)

        val session = manager.start(name, TidalLoginStart(TidalSessionFormat.TIDDL, "client-x", "secret-x"))
        val done =
            withTimeout(10_000) { manager.events(session.loginId)!!.first { it.state != TidalLoginState.PENDING } }

        assertEquals(TidalLoginState.EXPIRED, done.state)
        assertTrue(repository.secrets.isEmpty())
    }

    @Test
    fun `expired_token from tidal ends the login`() = runBlocking {
        val upstream = MockUpstream { request ->
            if (request.url == TidalAuthApi.DEVICE_AUTHORIZATION_URL) json(deviceResponse())
            else json("""{"error":"expired_token"}""", HttpStatusCode.BadRequest)
        }
        val manager = TidalDeviceLoginManager(FakeSecretRepository(), upstream.client, scope)

        val session = manager.start(name, TidalLoginStart(TidalSessionFormat.TIDDL, "client-x", "secret-x"))
        val done =
            withTimeout(10_000) { manager.events(session.loginId)!!.first { it.state != TidalLoginState.PENDING } }

        assertEquals(TidalLoginState.EXPIRED, done.state)
    }

    @Test
    fun `cancel stops a pending login`() = runBlocking {
        val upstream = MockUpstream { request ->
            if (request.url == TidalAuthApi.DEVICE_AUTHORIZATION_URL) json(deviceResponse())
            else json("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)
        }
        val manager = TidalDeviceLoginManager(FakeSecretRepository(), upstream.client, scope)

        val session = manager.start(name, TidalLoginStart(TidalSessionFormat.TIDDL, "client-x", "secret-x"))
        manager.cancel(session.loginId)
        val done =
            withTimeout(10_000) { manager.events(session.loginId)!!.first { it.state != TidalLoginState.PENDING } }

        assertEquals(TidalLoginState.CANCELLED, done.state)
        assertNull(done.message)
    }

    @Test
    fun `client credentials fall back to the stored session`() = runBlocking {
        val upstream = MockUpstream { json(deviceResponse()) }
        val repository = FakeSecretRepository().apply {
            put(name, TidalSessionSecret(TidalSessionFormat.TIDDL, "stored-client", "stored-secret"))
        }
        val manager = TidalDeviceLoginManager(repository, upstream.client, scope)

        val session = manager.start(name, TidalLoginStart(TidalSessionFormat.TIDDL))
        manager.cancel(session.loginId)

        assertEquals("stored-client", upstream.requests.first().form["client_id"])
    }

    @Test
    fun `importer presets fall back to the default client`() = runBlocking {
        for (preset in listOf(CredentialNames.IMPORTER_TIDDL, CredentialNames.IMPORTER_TDN)) {
            val upstream = MockUpstream { json(deviceResponse()) }
            val manager = TidalDeviceLoginManager(FakeSecretRepository(), upstream.client, scope)

            val session = manager.start(preset, TidalLoginStart(TidalSessionFormat.TIDDL, "", ""))
            manager.cancel(session.loginId)

            assertEquals(CredentialPresets.TIDAL_IMPORTER_CLIENT_ID, upstream.requests.first().form["client_id"])
        }
    }

    @Test
    fun `typed and stored clients win over the preset default`() = runBlocking {
        val upstream = MockUpstream { json(deviceResponse()) }
        val repository = FakeSecretRepository().apply {
            put(name, TidalSessionSecret(TidalSessionFormat.TIDDL, "stored-client", "stored-secret"))
        }
        val manager = TidalDeviceLoginManager(repository, upstream.client, scope)

        manager.cancel(
            manager.start(
                name,
                TidalLoginStart(TidalSessionFormat.TIDDL, "typed-client", "typed-secret")
            ).loginId
        )
        manager.cancel(manager.start(name, TidalLoginStart(TidalSessionFormat.TIDDL, "", "")).loginId)

        assertEquals(listOf("typed-client", "stored-client"), upstream.requests.map { it.form["client_id"] })
    }

    @Test
    fun `missing client credentials without a preset are invalid`() = runBlocking {
        val manager = TidalDeviceLoginManager(FakeSecretRepository(), MockUpstream { json("{}") }.client, scope)

        val error = assertFailsWith<CredentialException> {
            manager.start(
                "custom.tidal",
                TidalLoginStart(TidalSessionFormat.TIDDL)
            )
        }

        assertEquals(CredentialErrorCode.INVALID, error.code)
        assertNull(manager.events("unknown"))
    }
}
