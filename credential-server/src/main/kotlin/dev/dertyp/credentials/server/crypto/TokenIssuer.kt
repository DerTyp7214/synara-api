package dev.dertyp.credentials.server.crypto

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import dev.dertyp.credentials.GrantInfo
import dev.dertyp.credentials.server.db.ClientRecord
import dev.dertyp.credentials.server.db.SigningKeyStore
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

data class IssuedToken(val token: String, val expiresAt: Long)

class TokenIssuer(
    private val keys: SigningKeyStore,
    private val issuer: String,
    private val ttlSeconds: Long,
) {
    private class ActiveKey(val kid: String, val algorithm: Algorithm)

    @Volatile
    private var active: ActiveKey? = null
    private val verifiers = ConcurrentHashMap<String, JWTVerifier>()
    private val random = SecureRandom()

    fun issue(client: ClientRecord, grants: List<GrantInfo>): IssuedToken {
        val key = activeKey()
        val now = Instant.now()
        val expiresAt = now.plusSeconds(ttlSeconds)
        val token = JWT.create()
            .withKeyId(key.kid)
            .withIssuer(issuer)
            .withAudience(AUDIENCE)
            .withSubject(client.id.toString())
            .withClaim(CLAIM_VERSION, client.tokenVersion)
            .withClaim(CLAIM_GRANTS, grants.map { it.name })
            .withClaim(CLAIM_WRITE_BACK, grants.filter { it.writeBack }.map { it.name })
            .withIssuedAt(now)
            .withExpiresAt(expiresAt)
            .withJWTId(UUID.randomUUID().toString())
            .sign(key.algorithm)
        return IssuedToken(token, expiresAt.toEpochMilli())
    }

    fun verifierFor(kid: String): JWTVerifier? {
        verifiers[kid]?.let { return it }
        val stored = keys.find(kid) ?: return null
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(stored.publicKeyX509))) as ECPublicKey
        val verifier = JWT.require(Algorithm.ECDSA256(publicKey, null))
            .withIssuer(issuer)
            .withAudience(AUDIENCE)
            .withClaimPresence(CLAIM_VERSION)
            .build()
        return verifiers.putIfAbsent(kid, verifier) ?: verifier
    }

    fun verify(token: String): DecodedJWT? = try {
        val kid = JWT.decode(token).keyId ?: return null
        verifierFor(kid)?.verify(token)
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun rotate(): String {
        val created = generate()
        active = created
        return created.kid
    }

    @Synchronized
    private fun activeKey(): ActiveKey {
        active?.let { return it }
        val stored = keys.active()
        val key = if (stored?.privateKeyPkcs8 != null) {
            val factory = KeyFactory.getInstance("EC")
            val privateKey =
                factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(stored.privateKeyPkcs8))) as ECPrivateKey
            val publicKey =
                factory.generatePublic(X509EncodedKeySpec(Base64.decode(stored.publicKeyX509))) as ECPublicKey
            ActiveKey(stored.kid, Algorithm.ECDSA256(publicKey, privateKey))
        } else {
            generate()
        }
        active = key
        return key
    }

    private fun generate(): ActiveKey {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val kid = ByteArray(8).also { random.nextBytes(it) }.toHexString()
        keys.activate(kid, Base64.encode(pair.private.encoded), Base64.encode(pair.public.encoded))
        return ActiveKey(kid, Algorithm.ECDSA256(pair.public as ECPublicKey, pair.private as ECPrivateKey))
    }

    companion object {
        const val AUDIENCE = "synara-credential-consumer"
        const val CLAIM_VERSION = "ver"
        const val CLAIM_GRANTS = "grt"
        const val CLAIM_WRITE_BACK = "gwb"
    }
}
