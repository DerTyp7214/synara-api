package dev.dertyp.credentials.server

import dev.dertyp.credentials.*
import dev.dertyp.credentials.server.broker.CredentialException
import dev.dertyp.credentials.server.broker.CredentialResolver
import dev.dertyp.credentials.server.broker.TidalDeviceLoginManager
import dev.dertyp.credentials.server.db.CredentialStore

class CredentialAdminOperations(
    private val store: CredentialStore,
    private val resolver: CredentialResolver,
    private val tidalLogins: TidalDeviceLoginManager,
) {
    fun upsert(name: String, request: UpsertCredentialRequest): CredentialSummary {
        requireValidName(name)
        val existingKind = store.kind(name)
        val existing = if (existingKind == request.kind) store.loadSecret(name) else null
        val secret = resolver.toStoredSecret(request.kind, request.input, existing)
        val state = resolver.initialState(secret)
        val summary = store.upsertCredential(name, request.kind, request.description, secret, state)
        resolver.invalidate(name)
        return summary
    }

    fun delete(name: String): Boolean {
        val deleted = store.deleteCredential(name)
        resolver.invalidate(name)
        return deleted
    }

    suspend fun startTidalLogin(name: String, start: TidalLoginStart): TidalLoginSession {
        requireValidName(name)
        val kind = store.kind(name)
        if (kind != null && kind != CredentialKind.TIDAL_DEVICE_SESSION) {
            throw CredentialException(CredentialErrorCode.INVALID, "Credential $name is not a Tidal session")
        }
        return tidalLogins.start(name, start)
    }

    private fun requireValidName(name: String) {
        if (!CredentialNames.isValid(name)) {
            throw CredentialException(CredentialErrorCode.INVALID, "Invalid credential name $name")
        }
    }
}
