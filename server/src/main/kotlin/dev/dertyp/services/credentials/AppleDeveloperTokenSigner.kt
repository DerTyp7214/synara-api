package dev.dertyp.services.credentials

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.core.sha256
import dev.dertyp.credentials.CredentialNames
import dev.dertyp.credentials.ResolvedCredential
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import kotlin.time.Duration.Companion.minutes

data class AppleDeveloperKey(val teamId: String, val keyId: String, val p8Pem: String)

class AppleDeveloperTokenSigner {
    private data class CachedToken(val fingerprint: String, val token: ResolvedCredential.DeveloperToken)

    @Volatile
    private var cached: CachedToken? = null

    @Synchronized
    fun token(key: AppleDeveloperKey): ResolvedCredential.DeveloperToken {
        val fingerprint = fingerprint(key)
        cached?.takeIf { it.fingerprint == fingerprint }?.token?.let { token ->
            val expiresAt = token.expiresAt ?: 0
            if (System.currentTimeMillis() < expiresAt - ClientCredentialsExchange.EXPIRY_MARGIN_MS) return token
        }
        val privateKey = parsePrivateKey(key.p8Pem)
        val expiration = System.currentTimeMillis() + TOKEN_TTL.inWholeMilliseconds

        val signed = JWT.create()
            .withHeader(mapOf("alg" to "ES256", "kid" to key.keyId))
            .withIssuer(key.teamId)
            .withIssuedAt(Date())
            .withExpiresAt(Date(expiration))
            .sign(Algorithm.ECDSA256(null, privateKey))

        return ResolvedCredential.DeveloperToken(CredentialNames.APPLE_MUSIC_DEVELOPER, signed, expiration)
            .also { cached = CachedToken(fingerprint, it) }
    }

    @Synchronized
    fun invalidate() {
        cached = null
    }

    companion object {
        val TOKEN_TTL = 30.minutes
        private const val PEM_BEGIN = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_END = "-----END PRIVATE KEY-----"
        private const val P256_ORDER_BITS = 256
        private val WHITESPACE = "\\s".toRegex()

        fun parsePrivateKey(pem: String): ECPrivateKey {
            val body = pem.replace(PEM_BEGIN, "").replace(PEM_END, "").replace(WHITESPACE, "")
            require(body.isNotEmpty()) { "The private key is empty" }
            val key = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))) as? ECPrivateKey
            } catch (_: GeneralSecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            requireNotNull(key) { "The private key is not an EC key in PKCS#8 PEM format" }
            require(key.params.order.bitLength() == P256_ORDER_BITS) { "The private key is not a P-256 key" }
            return key
        }

        fun isValidPrivateKey(pem: String): Boolean = try {
            parsePrivateKey(pem)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

        private fun fingerprint(key: AppleDeveloperKey): String =
            "${key.teamId}\n${key.keyId}\n${key.p8Pem}".toByteArray(Charsets.UTF_8).sha256()
    }
}
