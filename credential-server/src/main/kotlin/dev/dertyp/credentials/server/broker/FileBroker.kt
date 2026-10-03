package dev.dertyp.credentials.server.broker

import dev.dertyp.credentials.CredentialErrorCode
import dev.dertyp.credentials.CredentialFileRoles
import dev.dertyp.credentials.CredentialStatus
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.WriteBackRequest

class FileBroker : CredentialBroker<FileSecret> {
    override suspend fun resolve(name: String, secret: FileSecret): BrokerResolution<FileSecret> {
        val state = stateOf(secret)
        val credential = ResolvedCredential.Files(
            name = name,
            files = secret.files,
            fingerprint = state.fingerprint,
            expiresAt = state.expiresAt,
        )
        return BrokerResolution(credential, null, state)
    }

    fun writeBack(secret: FileSecret, request: WriteBackRequest): Pair<FileSecret, CredentialStateUpdate> {
        if (request.expectedFingerprint != Fingerprints.ofFiles(secret.files)) {
            throw CredentialException(CredentialErrorCode.CONFLICT, "The stored files changed since they were fetched")
        }
        request.files.forEach { FileContents.decode(it) }
        val uploaded = request.files.associateBy { it.role }
        val merged = secret.files.map { uploaded[it.role] ?: it } +
                request.files.filter { file -> secret.files.none { it.role == file.role } }
        val updated = FileSecret(merged)
        return updated to stateOf(updated)
    }

    companion object {
        const val MEDIA_USER_TOKEN = "media-user-token"
        private const val EXPIRING_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        fun stateOf(secret: FileSecret): CredentialStateUpdate {
            val now = System.currentTimeMillis()
            val fingerprint = Fingerprints.ofFiles(secret.files)
            val cookies = secret.files.firstOrNull { it.role == CredentialFileRoles.GAMDL_COOKIES }
                ?: return CredentialStateUpdate(CredentialStatus.OK, null, null, fingerprint)
            val expiry = cookieExpiry(FileContents.decodeText(cookies), MEDIA_USER_TOKEN)
                ?: return CredentialStateUpdate(
                    CredentialStatus.ERROR,
                    "The $MEDIA_USER_TOKEN cookie is missing from ${CredentialFileRoles.GAMDL_COOKIES}",
                    null,
                    fingerprint,
                )
            if (expiry == SESSION_COOKIE) return CredentialStateUpdate(CredentialStatus.OK, null, null, fingerprint)
            val status = when {
                expiry <= now -> CredentialStatus.EXPIRED
                expiry - now <= EXPIRING_WINDOW_MS -> CredentialStatus.EXPIRING
                else -> CredentialStatus.OK
            }
            val message = when (status) {
                CredentialStatus.EXPIRED -> "The $MEDIA_USER_TOKEN cookie has expired"
                CredentialStatus.EXPIRING -> "The $MEDIA_USER_TOKEN cookie expires soon"
                else -> null
            }
            return CredentialStateUpdate(status, message, expiry, fingerprint)
        }

        private const val SESSION_COOKIE = 0L

        fun cookieExpiry(netscapeCookies: String, cookieName: String): Long? = netscapeCookies.lineSequence()
            .map { it.trimEnd('\r').trimStart() }
            .map { it.removePrefix("#HttpOnly_") }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .filter { it.size >= 7 && it[5] == cookieName }
            .mapNotNull { it[4].trim().toLongOrNull() }
            .map { if (it <= 0) SESSION_COOKIE else it * 1000 }
            .maxOrNull()
    }
}
