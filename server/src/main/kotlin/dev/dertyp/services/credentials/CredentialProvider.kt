package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.ResolvedCredential
import kotlinx.coroutines.flow.Flow

enum class CredentialMode { LOCAL, REMOTE }

class CredentialUnavailableException(val credentialName: String, message: String) : RuntimeException(message)

interface CredentialProvider {
    val mode: CredentialMode
    fun isAvailable(name: String): Boolean
    fun isManagedRemotely(name: String): Boolean
    suspend fun resolve(name: String): ResolvedCredential?
    suspend fun writeBack(name: String, expectedFingerprint: String?, files: List<CredentialFile>): Boolean
    fun changes(): Flow<Unit>
}
