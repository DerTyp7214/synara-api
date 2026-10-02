package dev.dertyp.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CredentialKind {
    OAUTH_CLIENT_CREDENTIALS,
    APPLE_DEVELOPER_KEY,
    API_KEY,
    API_KEY_PAIR,
    TIDAL_DEVICE_SESSION,
    FILE,
}

@Serializable
enum class TidalSessionFormat { TIDDL, TDN }

@Serializable
enum class OAuthAuthStyle { BASIC, FORM }

@Serializable
data class TokenRequest(val clientId: String, val clientSecret: String)

@Serializable
data class GrantInfo(val name: String, val kind: CredentialKind, val writeBack: Boolean = false)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Long,
    val grants: List<GrantInfo>,
)

@Serializable
data class CredentialFile(val role: String, val contentBase64: String)

@Serializable
sealed class ResolvedCredential {
    abstract val name: String
    abstract val expiresAt: Long?

    @Serializable
    @SerialName("access_token")
    data class AccessToken(
        override val name: String,
        val accessToken: String,
        val tokenType: String = "Bearer",
        override val expiresAt: Long?,
    ) : ResolvedCredential()

    @Serializable
    @SerialName("developer_token")
    data class DeveloperToken(
        override val name: String,
        val token: String,
        override val expiresAt: Long?,
    ) : ResolvedCredential()

    @Serializable
    @SerialName("api_key")
    data class ApiKey(
        override val name: String,
        val key: String,
        override val expiresAt: Long? = null,
    ) : ResolvedCredential()

    @Serializable
    @SerialName("api_key_pair")
    data class ApiKeyPair(
        override val name: String,
        val key: String,
        val secret: String,
        override val expiresAt: Long? = null,
    ) : ResolvedCredential()

    @Serializable
    @SerialName("files")
    data class Files(
        override val name: String,
        val files: List<CredentialFile>,
        val fingerprint: String?,
        override val expiresAt: Long? = null,
    ) : ResolvedCredential()
}

@Serializable
data class WriteBackRequest(val expectedFingerprint: String?, val files: List<CredentialFile>)

@Serializable
data class WriteBackResult(val fingerprint: String?)

@Serializable
enum class CredentialErrorCode {
    UNAUTHORIZED,
    NOT_GRANTED,
    NOT_FOUND,
    UPSTREAM_FAILED,
    NEEDS_LOGIN,
    CONFLICT,
    INVALID,
}

@Serializable
data class CredentialError(val code: CredentialErrorCode, val message: String)

@Serializable
data class CredentialServerHealth(val ok: Boolean, val protocolVersion: Int, val version: String)
