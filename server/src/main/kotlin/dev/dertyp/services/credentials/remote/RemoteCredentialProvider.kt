package dev.dertyp.services.credentials.remote

import dev.dertyp.credentials.CredentialFile
import dev.dertyp.credentials.ResolvedCredential
import dev.dertyp.credentials.WriteBackRequest
import dev.dertyp.services.Service
import dev.dertyp.services.credentials.CredentialMode
import dev.dertyp.services.credentials.CredentialProvider
import dev.dertyp.services.credentials.CredentialServerConnection
import dev.dertyp.services.credentials.CredentialServerConnectionSource
import dev.dertyp.services.credentials.admin.CredentialServerAdminClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RemoteCredentialProvider(
    private val client: CredentialServerClient,
    private val connectionSource: CredentialServerConnectionSource,
    private val admin: CredentialServerAdminClient,
) : Service(), CredentialProvider {
    private data class CachedCredential(
        val connection: CredentialServerConnection,
        val credential: ResolvedCredential,
        val validUntil: Long,
    )

    private val cache = ConcurrentHashMap<String, CachedCredential>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val missRefreshLock = Mutex()

    @Volatile
    private var lastMissRefresh: Instant? = null

    override val mode: CredentialMode
        get() = if (client.connection.value.consumerConfigured) CredentialMode.REMOTE else CredentialMode.LOCAL

    override suspend fun startService() {
        scope.launch {
            var first = true
            connectionSource.changes().collect {
                val changed = try {
                    syncConnection()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Credential server connection could not be read: ${e.message}")
                    return@collect
                }
                if (changed || first) connect()
                first = false
            }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            admin.changes().collect {
                try {
                    refreshGrants()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Refreshing credential server grants after an admin change failed: ${e.message}")
                }
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

    suspend fun refreshGrants(): Set<String>? {
        syncConnection()
        if (!client.connection.value.consumerConfigured) return null
        val before = grantedNames()
        client.exchangeToken(force = true)
        val after = grantedNames()
        if (after == before) logger.debug("Credential server grants unchanged, ${after.size} granted credentials")
        else logger.info("Credential server grants changed, ${after.size} granted credentials")
        return after
    }

    suspend fun refreshAfterLocalMiss(name: String): Boolean {
        val due = missRefreshLock.withLock {
            val now = Clock.System.now()
            val last = lastMissRefresh
            (last == null || now - last >= LOCAL_MISS_REFRESH_INTERVAL).also { if (it) lastMissRefresh = now }
        }
        if (!due) return false
        val granted = try {
            refreshGrants()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Refreshing credential server grants for $name failed: ${e.message}")
            null
        }
        return granted != null && name in granted
    }

    private suspend fun syncConnection(): Boolean {
        val current = connectionSource.current() ?: CredentialServerConnection.NONE
        val changed = client.updateConnection(current)
        if (changed) cache.clear()
        return changed
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
        val LOCAL_MISS_REFRESH_INTERVAL = 60.seconds
    }
}
