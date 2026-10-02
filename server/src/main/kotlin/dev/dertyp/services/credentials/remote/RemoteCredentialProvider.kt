package dev.dertyp.services.credentials.remote

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.WriteBackRequest
import dev.dertyp.services.Service
import dev.dertyp.services.credentials.CredentialMode
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.credentials.CredentialServerConnection
import dev.dertyp.services.credentials.CredentialServerConnectionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

class RemoteCredentialProvider(
    private val client: CredentialServerClient,
    private val connectionSource: CredentialServerConnectionSource,
) : Service(), CredentialProvider {
    private data class CachedCredential(
        val connection: CredentialServerConnection,
        val credential: ResolvedCredential,
        val validUntil: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedCredential>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    override val mode: CredentialMode
        get() = if (client.connection.value.consumerConfigured) CredentialMode.REMOTE else CredentialMode.LOCAL

    override suspend fun startService() {
        scope.launch {
            var first = true
            connectionSource.changes().collect {
                val current = try {
                    connectionSource.current() ?: CredentialServerConnection.NONE
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Credential server connection could not be read: ${e.message}")
                    return@collect
                }
                val changed = client.updateConnection(current)
                if (changed) cache.clear()
                if (changed || first) connect()
                first = false
            }
        }
    }

    suspend fun connect() {
        if (!client.connection.value.consumerConfigured) return
        try {
            client.exchangeToken()
            logger.info("Connected to the credential server with ${client.grants.value.size} granted credentials")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Credential server token exchange failed: ${e.message}")
        }
    }

    fun grantedNames(): Set<String> =
        if (client.connection.value.consumerConfigured) client.grants.value.keys else emptySet()

    override fun isAvailable(name: String): Boolean = isManagedRemotely(name)

    override fun isManagedRemotely(name: String): Boolean = name in grantedNames()

    override suspend fun resolve(name: String): ResolvedCredential? {
        if (!client.connection.value.consumerConfigured) return null
        cached(name)?.let { return it }
        return locks.computeIfAbsent(name) { Mutex() }.withLock {
            cached(name) ?: try {
                val connection = client.connection.value
                client.fetch(name).also { store(name, connection, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Credential $name could not be fetched from the credential server: ${e.message}")
                null
            }
        }
    }

    override suspend fun writeBack(name: String, expectedFingerprint: String?, files: List<CredentialFile>): Boolean {
        if (!client.connection.value.consumerConfigured) return false
        return try {
            val result = client.writeBack(name, WriteBackRequest(expectedFingerprint, files))
            if (result == null) logger.warn("Write-back of $name was rejected, the stored fingerprint changed in the meantime")
            result != null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Write-back of $name to the credential server failed: ${e.message}")
            false
        } finally {
            cache.remove(name)
        }
    }

    override fun changes(): Flow<Unit> = merge(
        client.connection.drop(1).map { },
        client.grants.drop(1).map { },
    )

    private fun cached(name: String): ResolvedCredential? {
        val entry = cache[name] ?: return null
        if (entry.connection == client.connection.value && System.currentTimeMillis() < entry.validUntil) return entry.credential
        cache.remove(name, entry)
        return null
    }

    private fun store(name: String, connection: CredentialServerConnection, credential: ResolvedCredential) {
        val now = System.currentTimeMillis()
        val expiryBound = credential.expiresAt?.let { it - CredentialServerClient.EXPIRY_MARGIN_MS }
        val validUntil = when (credential) {
            is ResolvedCredential.Files -> return
            is ResolvedCredential.ApiKey, is ResolvedCredential.ApiKeyPair ->
                minOf(now + KEY_CACHE_TTL_MS, expiryBound ?: Long.MAX_VALUE)
            is ResolvedCredential.AccessToken, is ResolvedCredential.DeveloperToken -> expiryBound ?: return
        }
        if (validUntil > now) cache[name] = CachedCredential(connection, credential, validUntil)
    }

    companion object {
        val KEY_CACHE_TTL_MS = 5.minutes.inWholeMilliseconds
    }
}
