package dev.dertyp.credentials

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GrantSpec(val name: String, val writeBack: Boolean = false)

@Serializable
data class ClientSummary(
    val id: String,
    val clientId: String,
    val name: String,
    val enabled: Boolean,
    val tokenVersion: Int,
    val createdAt: Long,
    val lastTokenAt: Long?,
    val grants: List<GrantInfo>,
)

@Serializable
data class CreateClientRequest(val name: String, val grants: List<GrantSpec> = emptyList())

@Serializable
data class CreatedClient(val client: ClientSummary, val clientSecret: String)

@Serializable
data class UpdateClientRequest(val name: String? = null, val enabled: Boolean? = null)

@Serializable
data class SetGrantsRequest(val grants: List<GrantSpec>)

@Serializable
enum class CredentialStatus { OK, EXPIRING, EXPIRED, NEEDS_LOGIN, ERROR }

@Serializable
data class CredentialSummary(
    val name: String,
    val kind: CredentialKind,
    val description: String?,
    val status: CredentialStatus,
    val statusMessage: String?,
    val expiresAt: Long?,
    val updatedAt: Long,
    val grantedTo: List<String>,
)

@Serializable
sealed class CredentialInput {
    @Serializable
    @SerialName("oauth_client_credentials")
    data class OAuthClientCredentialsInput(
        val clientId: String,
        val clientSecret: String,
        val tokenUrl: String,
        val authStyle: OAuthAuthStyle,
        val scope: String? = null,
    ) : CredentialInput()

    @Serializable
    @SerialName("apple_developer_key")
    data class AppleDeveloperKeyInput(
        val teamId: String,
        val keyId: String,
        val p8Pem: String,
        val ttlSeconds: Long = 43200,
    ) : CredentialInput()

    @Serializable
    @SerialName("api_key")
    data class ApiKeyInput(val key: String) : CredentialInput()

    @Serializable
    @SerialName("api_key_pair")
    data class ApiKeyPairInput(val key: String, val secret: String) : CredentialInput()

    @Serializable
    @SerialName("tidal_device_session")
    data class TidalSessionInput(
        val format: TidalSessionFormat,
        val clientId: String,
        val clientSecret: String,
        val authFileContent: String? = null,
    ) : CredentialInput()

    @Serializable
    @SerialName("file")
    data class FileInput(val files: List<CredentialFile>) : CredentialInput()
}

@Serializable
data class UpsertCredentialRequest(
    val kind: CredentialKind,
    val description: String? = null,
    val input: CredentialInput,
)

@Serializable
data class CredentialPreset(
    val name: String,
    val kind: CredentialKind,
    val description: String,
    val tokenUrl: String? = null,
    val authStyle: OAuthAuthStyle? = null,
    val format: TidalSessionFormat? = null,
    val fileRoles: List<String> = emptyList(),
)

@Serializable
data class CredentialTestResult(val ok: Boolean, val expiresAt: Long?, val message: String?)

@Serializable
data class TidalLoginStart(
    val format: TidalSessionFormat,
    val clientId: String? = null,
    val clientSecret: String? = null,
)

@Serializable
data class TidalLoginSession(
    val loginId: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val userCode: String,
    val expiresAt: Long,
)

@Serializable
enum class TidalLoginState { PENDING, COMPLETED, EXPIRED, FAILED }

@Serializable
data class TidalLoginEvent(val state: TidalLoginState, val message: String? = null)

object CredentialJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }
}
