package dev.dertyp.credentials.server.crypto

import com.auth0.jwt.JWT
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.GrantInfo
import dev.dertyp.credentials.server.db.ClientRecord
import dev.dertyp.credentials.server.testDeps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.encoding.Base64

class TokenIssuerTest {
    @TempDir
    lateinit var dir: Path

    private val client = ClientRecord(UUID.randomUUID(), "syn_0011223344556677", "test", "hash", 4, true)
    private val grants = listOf(
        GrantInfo("tidal.api", CredentialKind.OAUTH_CLIENT_CREDENTIALS),
        GrantInfo("importer.tiddl", CredentialKind.TIDAL_DEVICE_SESSION, writeBack = true),
    )

    @Test
    fun `signs and verifies the consumer claims`() = testDeps(dir).use { deps ->
        val issued = deps.tokenIssuer.issue(client, grants)
        val decoded = deps.tokenIssuer.verify(issued.token)!!
        assertEquals("ES256", decoded.algorithm)
        assertNotNull(decoded.keyId)
        assertEquals("synara-credentials-test", decoded.issuer)
        assertEquals(listOf(TokenIssuer.AUDIENCE), decoded.audience)
        assertEquals(client.id.toString(), decoded.subject)
        assertEquals(4, decoded.getClaim(TokenIssuer.CLAIM_VERSION).asInt())
        assertEquals(listOf("tidal.api", "importer.tiddl"), decoded.getClaim(TokenIssuer.CLAIM_GRANTS).asList(String::class.java))
        assertEquals(listOf("importer.tiddl"), decoded.getClaim(TokenIssuer.CLAIM_WRITE_BACK).asList(String::class.java))
        assertNotNull(decoded.id)
        assertEquals(issued.expiresAt / 1000, decoded.expiresAtAsInstant.epochSecond)
    }

    @Test
    fun `rejects expired tokens`() = testDeps(dir, tokenTtlSeconds = 900).use { deps ->
        val expiredIssuer = TokenIssuer(deps.signingKeys, "synara-credentials-test", -60)
        val token = expiredIssuer.issue(client, grants).token
        assertNull(deps.tokenIssuer.verify(token))
        assertNull(expiredIssuer.verify(token))
    }

    @Test
    fun `rejects tampered tokens`() = testDeps(dir).use { deps ->
        val token = deps.tokenIssuer.issue(client, grants).token
        val parts = token.split('.')
        val payload = String(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(parts[1]))
        val forged = payload.replace("\"ver\":4", "\"ver\":5")
        assertNotEquals(payload, forged)
        val forgedPart = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(forged.toByteArray())
        assertNull(deps.tokenIssuer.verify(listOf(parts[0], forgedPart, parts[2]).joinToString(".")))
        assertNull(deps.tokenIssuer.verify("not-a-token"))
    }

    @Test
    fun `rejects tokens of another issuer`() = testDeps(dir).use { deps ->
        val token = TokenIssuer(deps.signingKeys, "someone-else", 900).issue(client, grants).token
        assertNull(deps.tokenIssuer.verify(token))
    }

    @Test
    fun `rotation keeps old keys verifying`() = testDeps(dir).use { deps ->
        val before = deps.tokenIssuer.issue(client, grants).token
        val newKid = deps.tokenIssuer.rotate()
        val after = deps.tokenIssuer.issue(client, grants).token
        assertNotEquals(JWT.decode(before).keyId, JWT.decode(after).keyId)
        assertEquals(newKid, JWT.decode(after).keyId)
        assertNotNull(deps.tokenIssuer.verify(before))
        assertNotNull(deps.tokenIssuer.verify(after))

        val restarted = TokenIssuer(deps.signingKeys, "synara-credentials-test", 900)
        assertNotNull(restarted.verify(before))
        assertNotNull(restarted.verify(after))
        assertEquals(newKid, JWT.decode(restarted.issue(client, grants).token).keyId)
        assertEquals(2, deps.signingKeys.list().size)
        assertTrue(deps.signingKeys.list().single { it.active }.kid == newKid)
    }
}
