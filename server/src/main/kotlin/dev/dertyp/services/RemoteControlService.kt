package dev.dertyp.services

import dev.dertyp.core.UnauthorizedException
import dev.dertyp.data.ClientCapability
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RemoteControlService(
    private val clientRequestService: ClientRequestService
) : Service() {
    private val statuses = ConcurrentHashMap<UUID, MutableSharedFlow<RemotePlaybackStatus>>()

    init {
        clientRequestService.addDisconnectListener { statuses.remove(it) }
    }

    private fun flowOf(sessionId: UUID): MutableSharedFlow<RemotePlaybackStatus> =
        statuses.computeIfAbsent(sessionId) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    private fun target(userId: UUID, sessionId: UUID): ClientRequestService.Connection {
        val connection = clientRequestService.connection(sessionId)
            ?: throw IllegalArgumentException("Device is not online")
        if (connection.userId != userId) throw UnauthorizedException("Session does not belong to the current user")
        return connection
    }

    suspend fun reportStatus(sessionId: UUID, status: RemotePlaybackStatus) {
        require(clientRequestService.has(sessionId, ClientCapability.REMOTE_CONTROL)) {
            "Session is not remote controllable"
        }
        flowOf(sessionId).emit(status.copy(reportedAt = System.currentTimeMillis()))
    }

    fun getStatus(userId: UUID, sessionId: UUID): RemotePlaybackStatus? {
        target(userId, sessionId)
        return flowOf(sessionId).replayCache.firstOrNull()
    }

    fun observeStatus(userId: UUID, sessionId: UUID): Flow<RemotePlaybackStatus> {
        target(userId, sessionId)
        return flowOf(sessionId).asSharedFlow()
    }

    suspend fun sendCommand(
        userId: UUID,
        requesterSessionId: UUID,
        targetSessionId: UUID,
        command: PlaybackCommand,
        timeout: Duration = 10.seconds
    ): ClientRequestStatus {
        target(userId, targetSessionId)
        require(clientRequestService.has(targetSessionId, ClientCapability.REMOTE_CONTROL)) {
            "Device does not accept remote control"
        }
        if (command is PlaybackCommand.SetVolume) {
            require(clientRequestService.has(targetSessionId, ClientCapability.REMOTE_VOLUME)) {
                "Device does not accept remote volume changes"
            }
            require(command.volume in 0f..1f) { "Volume must be between 0 and 1" }
        }
        val requestedByDeviceName = clientRequestService.deviceName(requesterSessionId)
        return clientRequestService.request(targetSessionId, timeout) { id, at ->
            ClientRequest.ControlPlayback(id, at, requesterSessionId, requestedByDeviceName, command)
        }
    }
}
