package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.remote.RemoteCredentialProvider
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

class RoutingCredentialProvider(
    private val local: LocalCredentialProvider,
    private val remote: RemoteCredentialProvider,
) : CredentialProvider {
    private val logger = KtorSimpleLogger("RoutingCredentialProvider")

    override val mode: CredentialMode get() = remote.mode

    override fun isAvailable(name: String): Boolean =
        if (remote.isManagedRemotely(name)) true else local.isAvailable(name)

    override fun isManagedRemotely(name: String): Boolean = remote.isManagedRemotely(name)

    override suspend fun resolve(name: String): ResolvedCredential? {
        if (!remote.isManagedRemotely(name)) {
            local.resolve(name)?.let { return it }
            return if (remote.refreshAfterLocalMiss(name)) remote.resolve(name) else null
        }
        val resolved = remote.resolve(name)
        if (resolved == null) logger.warn("Credential $name is managed by the credential server but is unavailable right now")
        return resolved
    }

    override suspend fun writeBack(name: String, expectedFingerprint: String?, files: List<CredentialFile>): Boolean =
        if (remote.isManagedRemotely(name)) remote.writeBack(name, expectedFingerprint, files)
        else local.writeBack(name, expectedFingerprint, files)

    override fun changes(): Flow<Unit> = merge(remote.changes(), local.changes())
}
