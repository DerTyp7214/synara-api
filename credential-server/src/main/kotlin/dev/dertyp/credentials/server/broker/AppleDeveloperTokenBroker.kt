package dev.dertyp.credentials.server.broker

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.ResolvedCredential
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

class AppleDeveloperTokenBroker : CredentialBroker<AppleSecret> {
    private data class CachedToken(val secret: AppleSecret, val token: ResolvedCredential.DeveloperToken, val validUntil: Long)

    private val cache = ConcurrentHashMap<String, CachedToken>()

    override suspend fun resolve(name: String, secret: AppleSecret): BrokerResolution<AppleSecret> {
        val now = System.currentTimeMillis()
        val cached = cache[name]?.takeIf { it.secret == secret && it.validUntil > now }
        val token = cached?.token ?: sign(name, secret, now).also {
            cache[name] = CachedToken(secret, it, (it.expiresAt ?: now) - REFRESH_MARGIN_MS)
        }
        return BrokerResolution(token, null, CredentialStateUpdate(CredentialStatus.OK, null, null, null))
    }

    override fun invalidate(name: String) {
        cache.remove(name)
    }

    private fun sign(name: String, secret: AppleSecret, now: Long): ResolvedCredential.DeveloperToken {
        val issuedAt = now / 1000 * 1000
        val expiresAt = issuedAt + secret.ttlSeconds * 1000
        val token = JWT.create()
            .withKeyId(secret.keyId)
            .withIssuer(secret.teamId)
            .withIssuedAt(Date(issuedAt))
            .withExpiresAt(Date(expiresAt))
            .sign(Algorithm.ECDSA256(null, parsePrivateKey(secret.p8Pem)))
        return ResolvedCredential.DeveloperToken(name = name, token = token, expiresAt = expiresAt)
    }

    companion object {
        private const val REFRESH_MARGIN_MS = 5 * 60_000L
        const val MAX_TTL_SECONDS = 15_777_000L

        fun parsePrivateKey(pem: String): ECPrivateKey {
            val body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            return try {
                val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(body))
                KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
            } catch (e: IllegalArgumentException) {
                throw CredentialException(CredentialErrorCode.INVALID, "The .p8 key is not valid base64")
            } catch (e: GeneralSecurityException) {
                throw CredentialException(CredentialErrorCode.INVALID, "The .p8 key is not a PKCS8 EC private key")
            } catch (e: ClassCastException) {
                throw CredentialException(CredentialErrorCode.INVALID, "The .p8 key is not an EC private key")
            }
        }
    }
}
