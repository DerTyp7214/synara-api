package dev.dertyp.testing

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.services.credentials.CredentialMode
import dev.dertyp.services.credentials.CredentialProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

class FakeCredentialProvider(vararg initial: ResolvedCredential) : CredentialProvider {
    data class WriteBack(val name: String, val expectedFingerprint: String?, val files: List<CredentialFile>)

    private val credentials = ConcurrentHashMap<String, ResolvedCredential>()
    private val remote = ConcurrentHashMap.newKeySet<String>()
    private val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    val resolved = mutableListOf<String>()
    val writeBacks = mutableListOf<WriteBack>()

    init {
        initial.forEach { put(it) }
    }

    fun put(credential: ResolvedCredential, managedRemotely: Boolean = false) {
        credentials[credential.name] = credential
        if (managedRemotely) remote.add(credential.name) else remote.remove(credential.name)
        changeFlow.tryEmit(Unit)
    }

    fun markRemote(name: String) {
        remote.add(name)
        changeFlow.tryEmit(Unit)
    }

    fun remove(name: String) {
        credentials.remove(name)
        changeFlow.tryEmit(Unit)
    }

    override val mode: CredentialMode
        get() = if (remote.isEmpty()) CredentialMode.LOCAL else CredentialMode.REMOTE

    override fun isAvailable(name: String): Boolean = credentials.containsKey(name)

    override fun isManagedRemotely(name: String): Boolean = name in remote

    override suspend fun resolve(name: String): ResolvedCredential? {
        synchronized(resolved) { resolved.add(name) }
        return credentials[name]
    }

    override suspend fun writeBack(name: String, expectedFingerprint: String?, files: List<CredentialFile>): Boolean {
        synchronized(writeBacks) { writeBacks.add(WriteBack(name, expectedFingerprint, files)) }
        return true
    }

    override fun changes(): Flow<Unit> = changeFlow
}
