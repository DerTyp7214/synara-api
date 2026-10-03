package dev.dertyp.credentials.server.broker

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.OAuthAuthStyle
import dev.dertyp.credentials.ResolvedCredential
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthAndAppleBrokerTest {
    private val tokenUrl = "https://auth.example.com/token"

    private fun oauthSecret(style: OAuthAuthStyle, scope: String? = null) =
        OAuthSecret(clientId = "id-1", clientSecret = "secret-1", tokenUrl = tokenUrl, authStyle = style, scope = scope)

    @Test
    fun `basic style sends credentials in the authorization header`() = runTest {
        val upstream = MockUpstream { json("""{"access_token":"tok","token_type":"Bearer","expires_in":3600}""") }
        val repository = FakeSecretRepository().apply { put("tidal.api", oauthSecret(OAuthAuthStyle.BASIC, "r_usr")) }
        val resolver = CredentialResolver(repository, upstream.client)

        val credential = assertIs<ResolvedCredential.AccessToken>(resolver.resolve("tidal.api"))

        assertEquals("tok", credential.accessToken)
        val request = upstream.requests.single()
        assertEquals(tokenUrl, request.url)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("id-1:secret-1".toByteArray()),
            request.authorization
        )
        assertEquals("client_credentials", request.form["grant_type"])
        assertEquals("r_usr", request.form["scope"])
        assertNull(request.form["client_id"])
        assertNull(request.form["client_secret"])
    }

    @Test
    fun `form style sends credentials in the body`() = runTest {
        val upstream = MockUpstream { json("""{"access_token":"tok","token_type":"Bearer","expires_in":3600}""") }
        val repository = FakeSecretRepository().apply { put("spotify.api", oauthSecret(OAuthAuthStyle.FORM)) }
        val resolver = CredentialResolver(repository, upstream.client)

        resolver.resolve("spotify.api")

        val request = upstream.requests.single()
        assertNull(request.authorization)
        assertEquals("client_credentials", request.form["grant_type"])
        assertEquals("id-1", request.form["client_id"])
        assertEquals("secret-1", request.form["client_secret"])
        assertNull(request.form["scope"])
    }

    @Test
    fun `tokens are cached until a minute before expiry`() = runTest {
        var counter = 0
        val upstream = MockUpstream {
            counter++
            json("""{"access_token":"tok-$counter","expires_in":3600}""")
        }
        val repository = FakeSecretRepository().apply { put("tidal.api", oauthSecret(OAuthAuthStyle.BASIC)) }
        val resolver = CredentialResolver(repository, upstream.client)

        val first = assertIs<ResolvedCredential.AccessToken>(resolver.resolve("tidal.api"))
        val second = assertIs<ResolvedCredential.AccessToken>(resolver.resolve("tidal.api"))

        assertEquals("tok-1", first.accessToken)
        assertEquals(first, second)
        assertEquals(1, upstream.requests.size)

        resolver.invalidate("tidal.api")
        val third = assertIs<ResolvedCredential.AccessToken>(resolver.resolve("tidal.api"))
        assertEquals("tok-2", third.accessToken)
    }

    @Test
    fun `tokens expiring within the margin are not cached`() = runTest {
        var counter = 0
        val upstream = MockUpstream {
            counter++
            json("""{"access_token":"tok-$counter","expires_in":30}""")
        }
        val repository = FakeSecretRepository().apply { put("tidal.api", oauthSecret(OAuthAuthStyle.BASIC)) }
        val resolver = CredentialResolver(repository, upstream.client)

        resolver.resolve("tidal.api")
        resolver.resolve("tidal.api")

        assertEquals(2, upstream.requests.size)
    }

    @Test
    fun `a changed secret bypasses the cache`() = runTest {
        var counter = 0
        val upstream = MockUpstream {
            counter++
            json("""{"access_token":"tok-$counter","expires_in":3600}""")
        }
        val repository = FakeSecretRepository().apply { put("tidal.api", oauthSecret(OAuthAuthStyle.BASIC)) }
        val resolver = CredentialResolver(repository, upstream.client)

        resolver.resolve("tidal.api")
        repository.put("tidal.api", oauthSecret(OAuthAuthStyle.BASIC).copy(clientSecret = "secret-2"))
        val second = assertIs<ResolvedCredential.AccessToken>(resolver.resolve("tidal.api"))

        assertEquals("tok-2", second.accessToken)
    }

    @Test
    fun `upstream failure marks the credential as errored`() = runTest {
        val upstream = MockUpstream { json("""{"error":"invalid_client"}""", HttpStatusCode.Unauthorized) }
        val repository = FakeSecretRepository().apply { put("tidal.api", oauthSecret(OAuthAuthStyle.BASIC)) }
        val resolver = CredentialResolver(repository, upstream.client)

        val error = assertFailsWith<CredentialException> { resolver.resolve("tidal.api") }

        assertEquals(CredentialErrorCode.UPSTREAM_FAILED, error.code)
        assertEquals(CredentialStatus.ERROR, repository.states["tidal.api"]?.status)
        val test = resolver.test("tidal.api")
        assertEquals(false, test.ok)
    }

    @Test
    fun `unknown names are not found`() = runTest {
        val resolver = CredentialResolver(FakeSecretRepository(), MockUpstream { json("{}") }.client)

        val error = assertFailsWith<CredentialException> { resolver.resolve("missing.api") }

        assertEquals(CredentialErrorCode.NOT_FOUND, error.code)
        assertFailsWith<CredentialException> { resolver.test("missing.api") }
    }

    @Test
    fun `api keys are returned as stored`() = runTest {
        val repository = FakeSecretRepository().apply {
            put("youtube.api", ApiKeySecret("yt-key"))
            put("podcastindex.api", ApiKeyPairSecret("pi-key", "pi-secret"))
        }
        val resolver = CredentialResolver(repository, MockUpstream { json("{}") }.client)

        assertEquals("yt-key", assertIs<ResolvedCredential.ApiKey>(resolver.resolve("youtube.api")).key)
        val pair = assertIs<ResolvedCredential.ApiKeyPair>(resolver.resolve("podcastindex.api"))
        assertEquals("pi-key", pair.key)
        assertEquals("pi-secret", pair.secret)
        assertEquals(CredentialStatus.OK, repository.states["youtube.api"]?.status)
    }

    @Test
    fun `apple developer tokens carry the expected claims and are cached`() = runTest {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val keyPair = generator.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        val repository = FakeSecretRepository().apply {
            put(
                "applemusic.developer",
                AppleSecret(teamId = "TEAM123", keyId = "KEY456", p8Pem = pem, ttlSeconds = 43200)
            )
        }
        val resolver = CredentialResolver(repository, MockUpstream { json("{}") }.client)

        val before = System.currentTimeMillis() / 1000
        val credential = assertIs<ResolvedCredential.DeveloperToken>(resolver.resolve("applemusic.developer"))
        val decoded = JWT.require(Algorithm.ECDSA256(keyPair.public as ECPublicKey, null))
            .withIssuer("TEAM123")
            .build()
            .verify(credential.token)

        assertEquals("KEY456", decoded.keyId)
        assertEquals("ES256", decoded.algorithm)
        val issuedAt = decoded.issuedAt.time / 1000
        assertTrue(issuedAt >= before - 1)
        assertEquals(issuedAt + 43200, decoded.expiresAt.time / 1000)
        assertEquals(decoded.expiresAt.time, credential.expiresAt)

        val again = assertIs<ResolvedCredential.DeveloperToken>(resolver.resolve("applemusic.developer"))
        assertEquals(credential.token, again.token)

        resolver.invalidate("applemusic.developer")
        val fresh = assertIs<ResolvedCredential.DeveloperToken>(resolver.resolve("applemusic.developer"))
        assertNotEquals(credential.token, fresh.token)
    }
}
