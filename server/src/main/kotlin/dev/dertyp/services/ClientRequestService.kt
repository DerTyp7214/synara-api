package dev.dertyp.services

import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

class ClientRequestService : Service() {
    private class Pending(val sessionId: UUID) {
        val result = CompletableDeferred<ClientRequestStatus>()
    }

    private val sessions = ConcurrentHashMap<UUID, MutableSharedFlow<ClientRequest>>()
    private val pending = ConcurrentHashMap<UUID, Pending>()

    private fun flowOf(sessionId: UUID): MutableSharedFlow<ClientRequest> =
        sessions.computeIfAbsent(sessionId) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    fun observeRequests(sessionId: UUID): Flow<ClientRequest> = flowOf(sessionId).asSharedFlow()

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
}
