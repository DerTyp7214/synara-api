package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialKind
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.OAuthAuthStyle
import dev.dertyp.credentials.TidalSessionFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

class CredentialException(val code: CredentialErrorCode, message: String) : RuntimeException(message)

@Serializable
sealed class StoredSecret {
    abstract val kind: CredentialKind

    @Serializable
    @SerialName("oauth")
    data class OAuthSecret(
        val clientId: String,
        val clientSecret: String,
        val tokenUrl: String,
        val authStyle: OAuthAuthStyle,
        val scope: String? = null,
    ) : StoredSecret() {
        override val kind get() = CredentialKind.OAUTH_CLIENT_CREDENTIALS
    }

    @Serializable
    @SerialName("apple")
    data class AppleSecret(
        val teamId: String,
        val keyId: String,
        val p8Pem: String,
        val ttlSeconds: Long,
    ) : StoredSecret() {
        override val kind get() = CredentialKind.APPLE_DEVELOPER_KEY
    }

    @Serializable
    @SerialName("api_key")
    data class ApiKeySecret(val key: String) : StoredSecret() {
        override val kind get() = CredentialKind.API_KEY
    }

    @Serializable
    @SerialName("api_key_pair")
    data class ApiKeyPairSecret(val key: String, val secret: String) : StoredSecret() {
        override val kind get() = CredentialKind.API_KEY_PAIR
    }

    @Serializable
    @SerialName("tidal_session")
    data class TidalSessionSecret(
        val format: TidalSessionFormat,
        val clientId: String,
        val clientSecret: String,
        val accessToken: String? = null,
        val refreshToken: String? = null,
        val expiresAt: Long? = null,
        val userId: String? = null,
        val countryCode: String? = null,
        val extra: JsonObject = JsonObject(emptyMap()),
    ) : StoredSecret() {
        override val kind get() = CredentialKind.TIDAL_DEVICE_SESSION
    }

    @Serializable
    @SerialName("file")
    data class FileSecret(val files: List<CredentialFile>) : StoredSecret() {
        override val kind get() = CredentialKind.FILE
    }
}

typealias OAuthSecret = StoredSecret.OAuthSecret
typealias AppleSecret = StoredSecret.AppleSecret
typealias ApiKeySecret = StoredSecret.ApiKeySecret
typealias ApiKeyPairSecret = StoredSecret.ApiKeyPairSecret
typealias TidalSessionSecret = StoredSecret.TidalSessionSecret
typealias FileSecret = StoredSecret.FileSecret

data class CredentialStateUpdate(
    val status: CredentialStatus,
    val statusMessage: String?,
    val expiresAt: Long?,
    val fingerprint: String?,
)

interface SecretRepository {
    fun kind(name: String): CredentialKind?
    fun loadSecret(name: String): StoredSecret?
    fun saveSecret(name: String, secret: StoredSecret, state: CredentialStateUpdate)
    fun updateState(name: String, state: CredentialStateUpdate)
}
