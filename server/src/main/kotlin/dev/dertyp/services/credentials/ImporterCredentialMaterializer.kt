package dev.dertyp.services.credentials

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.ResolvedCredential
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64

class ImporterCredentialMaterializer(private val provider: CredentialProvider) {
    private val logger = KtorSimpleLogger("ImporterCredentialMaterializer")
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFiles(name: String?, targets: Map<String, File>, block: suspend () -> T): T {
        if (name == null || !provider.isManagedRemotely(name)) return block()
        return locks.computeIfAbsent(name) { Mutex() }.withLock {
            val resolved = provider.resolve(name) as? ResolvedCredential.Files
                ?: throw CredentialUnavailableException(name, "Credential $name is managed by the credential server but is unavailable right now")
            val snapshot = materialize(resolved, targets)
            try {
                block()
            } finally {
                withContext(NonCancellable) { syncBack(name, resolved, targets, snapshot) }
            }
        }
    }

    private fun materialize(resolved: ResolvedCredential.Files, targets: Map<String, File>): Map<String, ByteArray?> {
        resolved.files.forEach { file ->
            val target = targets[file.role] ?: return@forEach
            target.absoluteFile.parentFile?.mkdirs()
            target.writeBytes(Base64.decode(file.contentBase64))
            restrict(target)
        }
        return targets.mapValues { (_, target) -> read(target) }
    }

    private suspend fun syncBack(name: String, resolved: ResolvedCredential.Files, targets: Map<String, File>, snapshot: Map<String, ByteArray?>) {
        val changed = targets.mapNotNull { (role, target) ->
            val current = read(target) ?: return@mapNotNull null
            if (snapshot[role]?.contentEquals(current) == true) null
            else CredentialFile(role, Base64.encode(current))
        }
        if (changed.isEmpty()) return
        try {
            if (!provider.writeBack(name, resolved.fingerprint, changed)) {
                logger.warn("Updated credential files of $name could not be written back to the credential server")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Write-back of $name failed: ${e.message}")
        }
    }

    private fun read(target: File): ByteArray? = target.takeIf { it.isFile }?.readBytes()

    private fun restrict(target: File) {
        try {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        } catch (_: UnsupportedOperationException) {
        }
    }
}
