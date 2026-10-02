package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.WriteBackRequest

class TidalSessionBroker(private val authApi: TidalAuthApi) : CredentialBroker<TidalSessionSecret> {
    override suspend fun resolve(name: String, secret: TidalSessionSecret): BrokerResolution<TidalSessionSecret> {
        val refreshToken = secret.refreshToken
            ?: throw CredentialException(CredentialErrorCode.NEEDS_LOGIN, "No Tidal session stored, a login is required")
        val grant = authApi.refresh(secret.clientId, secret.clientSecret, refreshToken)
        val updated = secret.copy(
            accessToken = grant.accessToken,
            refreshToken = grant.refreshToken ?: refreshToken,
            expiresAt = grant.expiresAt ?: secret.expiresAt,
            userId = grant.userId ?: secret.userId,
            countryCode = grant.countryCode ?: secret.countryCode,
        )
        return BrokerResolution(render(name, updated), updated, stateOf(updated))
    }

    fun writeBack(secret: TidalSessionSecret, request: WriteBackRequest): Pair<TidalSessionSecret, CredentialStateUpdate> {
        val role = TidalAuthFormats.role(secret.format)
        val file = request.files.firstOrNull { it.role == role }
            ?: throw CredentialException(CredentialErrorCode.INVALID, "Write-back is missing the $role file")
        val current = fingerprint(secret)
        if (request.expectedFingerprint != current) {
            throw CredentialException(CredentialErrorCode.CONFLICT, "The Tidal session changed since it was fetched")
        }
        val data = TidalAuthFormats.parse(secret.format, FileContents.decodeText(file))
        val updated = TidalAuthFormats.adopt(secret, data)
        return updated to stateOf(updated)
    }

    companion object {
        fun fingerprint(secret: TidalSessionSecret): String? = secret.refreshToken?.let { Fingerprints.sha256Hex(it) }

        fun stateOf(secret: TidalSessionSecret): CredentialStateUpdate = if (secret.refreshToken == null) {
            CredentialStateUpdate(CredentialStatus.NEEDS_LOGIN, "No Tidal session stored, a login is required", secret.expiresAt, null)
        } else {
            CredentialStateUpdate(CredentialStatus.OK, null, secret.expiresAt, fingerprint(secret))
        }

        fun render(name: String, secret: TidalSessionSecret): ResolvedCredential.Files = ResolvedCredential.Files(
            name = name,
            files = listOf(renderFile(secret)),
            fingerprint = fingerprint(secret),
            expiresAt = secret.expiresAt,
        )

        private fun renderFile(secret: TidalSessionSecret): CredentialFile =
            FileContents.encode(TidalAuthFormats.role(secret.format), TidalAuthFormats.render(secret))
    }
}
