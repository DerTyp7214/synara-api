package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.OAuthAuthStyle
import dev.dertyp.credentials.ResolvedCredential
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import java.util.concurrent.ConcurrentHashMap

class OAuthClientCredentialsBroker(private val httpClient: HttpClient) : CredentialBroker<OAuthSecret> {
    private data class CachedToken(val secret: OAuthSecret, val token: ResolvedCredential.AccessToken, val validUntil: Long)

    private val cache = ConcurrentHashMap<String, CachedToken>()

    override suspend fun resolve(name: String, secret: OAuthSecret): BrokerResolution<OAuthSecret> {
        val now = System.currentTimeMillis()
        val cached = cache[name]?.takeIf { it.secret == secret && it.validUntil > now }
        val token = cached?.token ?: fetch(name, secret).also { token ->
            val expiresAt = token.expiresAt
            if (expiresAt != null) cache[name] = CachedToken(secret, token, expiresAt - REFRESH_MARGIN_MS)
            else cache.remove(name)
        }
        return BrokerResolution(token, null, CredentialStateUpdate(CredentialStatus.OK, null, null, null))
    }

    override fun invalidate(name: String) {
        cache.remove(name)
    }

    private suspend fun fetch(name: String, secret: OAuthSecret): ResolvedCredential.AccessToken {
        val form = parameters {
            append("grant_type", "client_credentials")
            secret.scope?.takeIf { it.isNotBlank() }?.let { append("scope", it) }
            if (secret.authStyle == OAuthAuthStyle.FORM) {
                append("client_id", secret.clientId)
                append("client_secret", secret.clientSecret)
            }
        }
        val basic = if (secret.authStyle == OAuthAuthStyle.BASIC) secret.clientId to secret.clientSecret else null
        val response = httpClient.postForm(secret.tokenUrl, form, basic)
        if (!response.status.isSuccess()) {
            throw CredentialException(
                CredentialErrorCode.UPSTREAM_FAILED,
                "Token endpoint returned ${response.status.value}: ${response.bodyAsText().take(200)}",
            )
        }
        val body = response.jsonBodyOrNull()
            ?: throw CredentialException(CredentialErrorCode.UPSTREAM_FAILED, "Token endpoint returned no JSON")
        val accessToken = body.string("access_token")
            ?: throw CredentialException(CredentialErrorCode.UPSTREAM_FAILED, "Token endpoint returned no access_token")
        val expiresAt = body.long("expires_in")?.let { System.currentTimeMillis() + it * 1000 }
        return ResolvedCredential.AccessToken(
            name = name,
            accessToken = accessToken,
            tokenType = body.string("token_type") ?: "Bearer",
            expiresAt = expiresAt,
        )
    }

    private companion object {
        const val REFRESH_MARGIN_MS = 60_000L
    }
}
