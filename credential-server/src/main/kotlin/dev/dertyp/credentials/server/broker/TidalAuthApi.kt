package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.json.JsonObject

data class TidalTokenGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long?,
    val userId: String?,
    val countryCode: String?,
)

data class TidalDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

sealed class TidalDevicePoll {
    data object Pending : TidalDevicePoll()
    data object SlowDown : TidalDevicePoll()
    data object Expired : TidalDevicePoll()
    data class Granted(val grant: TidalTokenGrant) : TidalDevicePoll()
    data class Failed(val message: String) : TidalDevicePoll()
}

class TidalAuthApi(private val httpClient: HttpClient) {
    suspend fun refresh(clientId: String, clientSecret: String, refreshToken: String): TidalTokenGrant {
        val form = parameters {
            append("grant_type", "refresh_token")
            append("refresh_token", refreshToken)
            append("client_id", clientId)
            append("scope", SCOPE)
        }
        val response = httpClient.postForm(TOKEN_URL, form, clientId to clientSecret)
        if (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.Unauthorized) {
            throw CredentialException(
                CredentialErrorCode.NEEDS_LOGIN,
                "Tidal rejected the refresh token (${response.status.value}), a new login is required",
            )
        }
        if (!response.status.isSuccess()) {
            throw CredentialException(
                CredentialErrorCode.UPSTREAM_FAILED,
                "Tidal token refresh returned ${response.status.value}: ${response.bodyAsText().take(200)}",
            )
        }
        val body = response.jsonBodyOrNull()
            ?: throw CredentialException(CredentialErrorCode.UPSTREAM_FAILED, "Tidal token refresh returned no JSON")
        return grantFrom(body)
            ?: throw CredentialException(CredentialErrorCode.UPSTREAM_FAILED, "Tidal token refresh returned no access_token")
    }

    suspend fun deviceAuthorization(clientId: String): TidalDeviceAuthorization {
        val form = parameters {
            append("client_id", clientId)
            append("scope", SCOPE)
        }
        val response = httpClient.postForm(DEVICE_AUTHORIZATION_URL, form)
        if (!response.status.isSuccess()) {
            throw CredentialException(
                CredentialErrorCode.UPSTREAM_FAILED,
                "Tidal device authorization returned ${response.status.value}: ${response.bodyAsText().take(200)}",
            )
        }
        val body = response.jsonBodyOrNull()
            ?: throw CredentialException(CredentialErrorCode.UPSTREAM_FAILED, "Tidal device authorization returned no JSON")
        val deviceCode = body.string("deviceCode") ?: body.string("device_code")
        val userCode = body.string("userCode") ?: body.string("user_code")
        val verificationUri = body.string("verificationUri") ?: body.string("verification_uri")
        if (deviceCode == null || userCode == null || verificationUri == null) {
            throw CredentialException(CredentialErrorCode.UPSTREAM_FAILED, "Tidal device authorization response is incomplete")
        }
        return TidalDeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = withScheme(verificationUri),
            verificationUriComplete = (body.string("verificationUriComplete") ?: body.string("verification_uri_complete"))
                ?.let(::withScheme),
            expiresInSeconds = body.long("expiresIn") ?: body.long("expires_in") ?: DEFAULT_DEVICE_EXPIRY_SECONDS,
            intervalSeconds = (body.long("interval") ?: DEFAULT_INTERVAL_SECONDS).coerceAtLeast(1),
        )
    }

    suspend fun pollDeviceToken(clientId: String, clientSecret: String, deviceCode: String): TidalDevicePoll {
        val form = parameters {
            append("client_id", clientId)
            append("device_code", deviceCode)
            append("grant_type", DEVICE_CODE_GRANT)
            append("scope", SCOPE)
        }
        val response = try {
            httpClient.postForm(TOKEN_URL, form, clientId to clientSecret)
        } catch (e: CredentialException) {
            return TidalDevicePoll.Failed(e.message ?: "Tidal token request failed")
        }
        val body = response.jsonBodyOrNull()
        if (response.status.isSuccess()) {
            return body?.let(::grantFrom)?.let { TidalDevicePoll.Granted(it) }
                ?: TidalDevicePoll.Failed("Tidal token response has no access_token")
        }
        return when (val error = body?.string("error")) {
            "authorization_pending" -> TidalDevicePoll.Pending
            "slow_down" -> TidalDevicePoll.SlowDown
            "expired_token" -> TidalDevicePoll.Expired
            else -> TidalDevicePoll.Failed(
                body?.string("error_description") ?: error ?: "Tidal token request returned ${response.status.value}",
            )
        }
    }

    private fun grantFrom(body: JsonObject): TidalTokenGrant? {
        val accessToken = body.string("access_token") ?: return null
        val user = body["user"] as? JsonObject
        return TidalTokenGrant(
            accessToken = accessToken,
            refreshToken = body.string("refresh_token")?.takeIf { it.isNotBlank() },
            expiresAt = body.long("expires_in")?.let { System.currentTimeMillis() + it * 1000 },
            userId = user?.string("userId") ?: body.string("user_id"),
            countryCode = user?.string("countryCode") ?: body.string("countryCode"),
        )
    }

    private fun withScheme(uri: String) =
        if (uri.startsWith("http://") || uri.startsWith("https://")) uri else "https://$uri"

    companion object {
        const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
        const val DEVICE_AUTHORIZATION_URL = "https://auth.tidal.com/v1/oauth2/device_authorization"
        const val SCOPE = "r_usr+w_usr+w_sub"
        const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val DEFAULT_DEVICE_EXPIRY_SECONDS = 300L
        private const val DEFAULT_INTERVAL_SECONDS = 2L
    }
}
