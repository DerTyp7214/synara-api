package dev.dertyp.services.credentials

import dev.dertyp.core.HttpClientFactory
import dev.dertyp.core.sha256
import dev.dertyp.credentials.CredentialJson
import dev.dertyp.credentials.OAuthAuthStyle
import dev.dertyp.credentials.ResolvedCredential
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

class ClientCredentialsExchange(private val httpClientFactory: HttpClientFactory) {
    private val logger = KtorSimpleLogger("ClientCredentialsExchange")
    private val cache = ConcurrentHashMap<String, CachedToken>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private data class CachedToken(val fingerprint: String, val token: ResolvedCredential.AccessToken)

    @Serializable
    private data class OAuthTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_in") val expiresIn: Long,
    )

    suspend fun exchange(
        name: String,
        tokenUrl: String,
        clientId: String,
        clientSecret: String,
        style: OAuthAuthStyle,
    ): ResolvedCredential.AccessToken {
        val fingerprint = fingerprint(tokenUrl, clientId, clientSecret, style)
        cached(name, fingerprint)?.let { return it }
        return locks.computeIfAbsent(name) { Mutex() }.withLock {
            cached(name, fingerprint) ?: request(name, tokenUrl, clientId, clientSecret, style).also {
                cache[name] = CachedToken(fingerprint, it)
            }
        }
    }

    fun invalidate(name: String) {
        cache.remove(name)
    }

    private fun fingerprint(tokenUrl: String, clientId: String, clientSecret: String, style: OAuthAuthStyle): String =
        "$tokenUrl\n$clientId\n$clientSecret\n${style.name}".toByteArray(Charsets.UTF_8).sha256()

    private fun cached(name: String, fingerprint: String): ResolvedCredential.AccessToken? {
        val entry = cache[name] ?: return null
        if (entry.fingerprint != fingerprint) return null
        val expiresAt = entry.token.expiresAt ?: return null
        return entry.token.takeIf { System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MS }
    }

    private suspend fun request(
        name: String,
        tokenUrl: String,
        clientId: String,
        clientSecret: String,
        style: OAuthAuthStyle,
    ): ResolvedCredential.AccessToken {
        logger.info("Requesting access token for $name")
        val response = httpClientFactory.api.submitForm(
            url = tokenUrl,
            formParameters = parameters {
                append("grant_type", "client_credentials")
                if (style == OAuthAuthStyle.FORM) {
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                }
            },
        ) {
            if (style == OAuthAuthStyle.BASIC) {
                header(HttpHeaders.Authorization, "Basic ${Base64.encode("$clientId:$clientSecret".toByteArray())}")
            }
        }
        if (!response.status.isSuccess()) {
            logger.warn("Access token request for $name failed with ${response.status}: ${response.bodyAsText()}")
            throw CredentialUnavailableException(name, "Access token request for $name failed with ${response.status}")
        }
        val body = CredentialJson.json.decodeFromString<OAuthTokenResponse>(response.bodyAsText())
        logger.info("Got new access token for $name")
        return ResolvedCredential.AccessToken(
            name = name,
            accessToken = body.accessToken,
            tokenType = body.tokenType,
            expiresAt = System.currentTimeMillis() + body.expiresIn * 1000,
        )
    }

    companion object {
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
