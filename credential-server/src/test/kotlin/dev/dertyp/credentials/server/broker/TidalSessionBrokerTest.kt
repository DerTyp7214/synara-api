package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.TidalSessionFormat
import dev.dertyp.credentials.WriteBackRequest
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TidalSessionBrokerTest {
    private val name = "importer.tiddl"

    private fun session(
        format: TidalSessionFormat = TidalSessionFormat.TIDDL,
        refreshToken: String? = "refresh-0",
        extra: JsonObject = JsonObject(emptyMap()),
    ) = TidalSessionSecret(
        format = format,
        clientId = "admin-client",
        clientSecret = "admin-secret",
        accessToken = "access-0",
        refreshToken = refreshToken,
        expiresAt = 1_000_000L,
        userId = "42",
        countryCode = "DE",
        extra = extra,
    )

    private fun decodedFile(credential: ResolvedCredential.Files): JsonObject =
        Json.parseToJsonElement(Base64.getDecoder().decode(credential.files.single().contentBase64).decodeToString()).jsonObject

    @Test
    fun `every resolve refreshes the session with the stored client`() = runTest {
        var counter = 0
        val upstream = MockUpstream {
            counter++
            json("""{"access_token":"access-$counter","expires_in":3600,"token_type":"Bearer"}""")
        }
        val repository = FakeSecretRepository().apply { put(name, session()) }
        val resolver = CredentialResolver(repository, upstream.client)

        resolver.resolve(name)
        val second = assertIs<ResolvedCredential.Files>(resolver.resolve(name))

        assertEquals(2, upstream.requests.size)
        val request = upstream.requests.first()
        assertEquals(TidalAuthApi.TOKEN_URL, request.url)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("admin-client:admin-secret".toByteArray()), request.authorization)
        assertEquals("refresh_token", request.form["grant_type"])
        assertEquals("refresh-0", request.form["refresh_token"])
        assertEquals("admin-client", request.form["client_id"])
        assertEquals("r_usr+w_usr+w_sub", request.form["scope"])
        assertEquals("access-2", decodedFile(second)["token"]?.jsonPrimitive?.content)
        assertEquals(CredentialFileRoles.TIDDL_AUTH, second.files.single().role)
        assertEquals(Fingerprints.sha256Hex("refresh-0"), second.fingerprint)
        val stored = assertIs<TidalSessionSecret>(repository.secrets[name])
        assertEquals("access-2", stored.accessToken)
        assertEquals(CredentialStatus.OK, repository.states[name]?.status)
    }

    @Test
    fun `rotated refresh token and user details are persisted`() = runTest {
        val upstream = MockUpstream {
            json(
                """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600,"user_id":7,
                   "user":{"userId":99,"countryCode":"US"}}""",
            )
        }
        val repository = FakeSecretRepository().apply { put(name, session()) }
        val resolver = CredentialResolver(repository, upstream.client)

        val credential = assertIs<ResolvedCredential.Files>(resolver.resolve(name))

        val stored = assertIs<TidalSessionSecret>(repository.secrets[name])
        assertEquals("refresh-1", stored.refreshToken)
        assertEquals("99", stored.userId)
        assertEquals("US", stored.countryCode)
        assertEquals(Fingerprints.sha256Hex("refresh-1"), credential.fingerprint)
        assertEquals(Fingerprints.sha256Hex("refresh-1"), repository.states[name]?.fingerprint)

        resolver.resolve(name)
        assertEquals("refresh-1", upstream.requests.last().form["refresh_token"])
    }

    @Test
    fun `tiddl auth json has the expected fields and keeps unknown ones`() = runTest {
        val upstream = MockUpstream { json("""{"access_token":"access-1","expires_in":3600}""") }
        val extra = JsonObject(mapOf("custom" to JsonPrimitive("kept")))
        val repository = FakeSecretRepository().apply { put(name, session(extra = extra)) }
        val resolver = CredentialResolver(repository, upstream.client)

        val before = System.currentTimeMillis() / 1000
        val file = decodedFile(assertIs<ResolvedCredential.Files>(resolver.resolve(name)))

        assertEquals("access-1", file["token"]?.jsonPrimitive?.content)
        assertEquals("refresh-0", file["refresh_token"]?.jsonPrimitive?.content)
        assertEquals("42", file["user_id"]?.jsonPrimitive?.content)
        assertEquals("DE", file["country_code"]?.jsonPrimitive?.content)
        assertEquals("kept", file["custom"]?.jsonPrimitive?.content)
        val expiresAt = file["expires_at"]!!.jsonPrimitive.long
        assertTrue(expiresAt in (before + 3590)..(before + 3610))
    }

    @Test
    fun `tiddl parse and render round trip unknown fields`() {
        val content = """{"token":"a","refresh_token":"r","expires_at":1700000000,"user_id":"5","country_code":"NL","future":{"x":1}}"""

        val data = TidalAuthFormats.parse(TidalSessionFormat.TIDDL, content)
        assertEquals("a", data.accessToken)
        assertEquals("r", data.refreshToken)
        assertEquals(1_700_000_000_000L, data.expiresAt)
        assertEquals("5", data.userId)
        assertEquals("NL", data.countryCode)

        val rendered = TidalAuthFormats.render(TidalAuthFormats.adopt(session(), data))
        assertEquals(Json.parseToJsonElement(content), Json.parseToJsonElement(rendered))
    }

    @Test
    fun `tdn token json round trips with fractional expiry`() {
        val content = """{"token_type":"Bearer","access_token":"a","refresh_token":"r","expiry_time":1700000000.5,"other":true}"""

        val data = TidalAuthFormats.parse(TidalSessionFormat.TDN, content)
        assertEquals("a", data.accessToken)
        assertEquals("r", data.refreshToken)
        assertEquals(1_700_000_000_500L, data.expiresAt)

        val rendered = Json.parseToJsonElement(
            TidalAuthFormats.render(TidalAuthFormats.adopt(session(format = TidalSessionFormat.TDN), data)),
        ).jsonObject
        assertEquals("Bearer", rendered["token_type"]?.jsonPrimitive?.content)
        assertEquals("a", rendered["access_token"]?.jsonPrimitive?.content)
        assertEquals("r", rendered["refresh_token"]?.jsonPrimitive?.content)
        assertEquals(1_700_000_000.5, rendered["expiry_time"]!!.jsonPrimitive.double)
        assertEquals("true", rendered["other"]?.jsonPrimitive?.content)
        assertNull(rendered["token"])
    }

    @Test
    fun `rejected refresh token needs a new login`() = runTest {
        val upstream = MockUpstream { json("""{"error":"invalid_grant"}""", HttpStatusCode.BadRequest) }
        val repository = FakeSecretRepository().apply { put(name, session()) }
        val resolver = CredentialResolver(repository, upstream.client)

        val error = assertFailsWith<CredentialException> { resolver.resolve(name) }

        assertEquals(CredentialErrorCode.NEEDS_LOGIN, error.code)
        assertEquals(CredentialStatus.NEEDS_LOGIN, repository.states[name]?.status)
    }

    @Test
    fun `unauthorized refresh needs a new login and server errors are upstream failures`() = runTest {
        var status = HttpStatusCode.Unauthorized
        val upstream = MockUpstream { json("{}", status) }
        val repository = FakeSecretRepository().apply { put(name, session()) }
        val resolver = CredentialResolver(repository, upstream.client)

        assertEquals(CredentialErrorCode.NEEDS_LOGIN, assertFailsWith<CredentialException> { resolver.resolve(name) }.code)

        status = HttpStatusCode.BadGateway
        assertEquals(CredentialErrorCode.UPSTREAM_FAILED, assertFailsWith<CredentialException> { resolver.resolve(name) }.code)
        assertEquals(CredentialStatus.ERROR, repository.states[name]?.status)
    }

    @Test
    fun `missing refresh token needs a login without calling upstream`() = runTest {
        val upstream = MockUpstream { json("{}") }
        val repository = FakeSecretRepository().apply { put(name, session(refreshToken = null)) }
        val resolver = CredentialResolver(repository, upstream.client)

        val error = assertFailsWith<CredentialException> { resolver.resolve(name) }

        assertEquals(CredentialErrorCode.NEEDS_LOGIN, error.code)
        assertTrue(upstream.requests.isEmpty())
    }

    @Test
    fun `write back with the current fingerprint is adopted`() = runTest {
        val repository = FakeSecretRepository().apply { put(name, session()) }
        val resolver = CredentialResolver(repository, MockUpstream { json("{}") }.client)
        val uploaded = """{"token":"cli-access","refresh_token":"cli-refresh","expires_at":1800000000,"user_id":"42","country_code":"DE","added":"x"}"""

        val result = resolver.writeBack(
            name,
            WriteBackRequest(Fingerprints.sha256Hex("refresh-0"), listOf(FileContents.encode(CredentialFileRoles.TIDDL_AUTH, uploaded))),
        )

        assertEquals(Fingerprints.sha256Hex("cli-refresh"), result.fingerprint)
        val stored = assertIs<TidalSessionSecret>(repository.secrets[name])
        assertEquals("cli-access", stored.accessToken)
        assertEquals("cli-refresh", stored.refreshToken)
        assertEquals(1_800_000_000_000L, stored.expiresAt)
        assertEquals("x", stored.extra["added"]?.jsonPrimitive?.content)
        assertEquals(Fingerprints.sha256Hex("cli-refresh"), repository.states[name]?.fingerprint)
    }

    @Test
    fun `write back with a stale fingerprint conflicts`() = runTest {
        val repository = FakeSecretRepository().apply { put(name, session()) }
        val resolver = CredentialResolver(repository, MockUpstream { json("{}") }.client)
        val uploaded = """{"token":"cli-access","refresh_token":"cli-refresh","expires_at":1800000000}"""

        val error = assertFailsWith<CredentialException> {
            resolver.writeBack(
                name,
                WriteBackRequest(Fingerprints.sha256Hex("stale"), listOf(FileContents.encode(CredentialFileRoles.TIDDL_AUTH, uploaded))),
            )
        }

        assertEquals(CredentialErrorCode.CONFLICT, error.code)
        assertEquals("refresh-0", assertIs<TidalSessionSecret>(repository.secrets[name]).refreshToken)
    }

    @Test
    fun `write back to a plain api key is rejected`() = runTest {
        val repository = FakeSecretRepository().apply { put("youtube.api", ApiKeySecret("k")) }
        val resolver = CredentialResolver(repository, MockUpstream { json("{}") }.client)

        val error = assertFailsWith<CredentialException> { resolver.writeBack("youtube.api", WriteBackRequest(null, emptyList())) }

        assertEquals(CredentialErrorCode.INVALID, error.code)
    }

    @Test
    fun `concurrent resolves are serialized per name`() = runBlocking {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val counter = AtomicInteger()
        val upstream = MockUpstream { request ->
            val current = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(current) { a, b -> maxOf(a, b) }
            delay(20)
            val next = counter.incrementAndGet()
            inFlight.decrementAndGet()
            json("""{"access_token":"access-$next","refresh_token":"refresh-$next","expires_in":3600,"echo":"${request.form["refresh_token"]}"}""")
        }
        val repository = FakeSecretRepository().apply { put(name, session()) }
        val resolver = CredentialResolver(repository, upstream.client)

        (1..5).map { async { resolver.resolve(name) } }.awaitAll()

        assertEquals(1, maxInFlight.get())
        assertEquals(
            listOf("refresh-0", "refresh-1", "refresh-2", "refresh-3", "refresh-4"),
            upstream.requests.map { it.form["refresh_token"] },
        )
        assertEquals("refresh-5", assertIs<TidalSessionSecret>(repository.secrets[name]).refreshToken)
    }
}
