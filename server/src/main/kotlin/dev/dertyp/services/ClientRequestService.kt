package dev.dertyp.services

import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientDescription
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.OnlineDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

class ClientRequestService : Service() {
    private class Pending(val sessionId: UUID) {
        val result = CompletableDeferred<ClientRequestStatus>()
    }

    class Connection(
        val userId: UUID,
        description: ClientDescription,
        val connectedAt: Long
    ) {
        @Volatile var description: ClientDescription = description
            internal set

        internal val count = AtomicInteger(0)
    }

    private val sessions = ConcurrentHashMap<UUID, MutableSharedFlow<ClientRequest>>()
    private val pending = ConcurrentHashMap<UUID, Pending>()
    private val connections = ConcurrentHashMap<UUID, Connection>()
    private val onDisconnected = CopyOnWriteArrayList<(UUID) -> Unit>()

    private fun flowOf(sessionId: UUID): MutableSharedFlow<ClientRequest> =
        sessions.computeIfAbsent(sessionId) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    fun observeRequests(sessionId: UUID): Flow<ClientRequest> = flowOf(sessionId).asSharedFlow()

    fun connect(userId: UUID, sessionId: UUID, description: ClientDescription): Flow<ClientRequest> = flow {
        register(userId, sessionId, description)
        try {
            emitAll(observeRequests(sessionId))
        } finally {
            unregister(sessionId)
        }
    }

    fun addDisconnectListener(listener: (UUID) -> Unit) {
        onDisconnected.add(listener)
    }

    fun connection(sessionId: UUID): Connection? = connections[sessionId]

    fun has(sessionId: UUID, capability: ClientCapability): Boolean =
        connections[sessionId]?.description?.capabilities?.contains(capability) == true

    fun deviceName(sessionId: UUID): String? = connections[sessionId]?.description?.deviceName

    fun onlineDevices(userId: UUID, currentSessionId: UUID?): List<OnlineDevice> =
        connections.entries
            .filter { it.value.userId == userId }
            .map { (sessionId, connection) ->
                val description = connection.description
                OnlineDevice(
                    sessionId = sessionId,
                    deviceName = description.deviceName,
                    platform = description.platform,
                    deviceId = description.deviceId,
                    capabilities = description.capabilities,
                    isCurrent = sessionId == currentSessionId,
                    connectedAt = connection.connectedAt
                )
            }
            .sortedByDescending { it.connectedAt }

    fun isReachable(sessionId: UUID): Boolean = (sessions[sessionId]?.subscriptionCount?.value ?: 0) > 0

    suspend fun request(
        sessionId: UUID,
        timeout: Duration,
        build: (id: UUID, requestedAt: Long) -> ClientRequest
    ): ClientRequestStatus {
        val flow = flowOf(sessionId)
        if (flow.subscriptionCount.value == 0) return ClientRequestStatus.UNREACHABLE

        val requestId = UUID.randomUUID()
        val entry = Pending(sessionId)
        pending[requestId] = entry
        return try {
            flow.emit(build(requestId, System.currentTimeMillis()))
            withTimeoutOrNull(timeout) { entry.result.await() } ?: ClientRequestStatus.TIMED_OUT
        } finally {
            pending.remove(requestId)
        }
    }

    fun complete(sessionId: UUID, requestId: UUID, status: ClientRequestStatus): Boolean {
        require(status == ClientRequestStatus.COMPLETED || status == ClientRequestStatus.REJECTED) {
            "Only COMPLETED and REJECTED can be reported by a client"
        }
        val entry = pending[requestId] ?: return false
        if (entry.sessionId != sessionId) return false
        return entry.result.complete(status)
    }

    private fun register(userId: UUID, sessionId: UUID, description: ClientDescription) {
        connections.compute(sessionId) { _, existing ->
            val connection = if (existing != null && existing.userId == userId) {
                existing.description = description
                existing
            } else {
                Connection(userId, description, System.currentTimeMillis())
            }
            connection.count.incrementAndGet()
            connection
        }
    }

    private fun unregister(sessionId: UUID) {
        var removed = false
        connections.computeIfPresent(sessionId) { _, connection ->
            if (connection.count.decrementAndGet() > 0) {
                connection
            } else {
                removed = true
                null
            }
        }
        if (removed) onDisconnected.forEach { it(sessionId) }
    }
}
