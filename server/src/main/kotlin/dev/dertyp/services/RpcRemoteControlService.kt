package dev.dertyp.services

import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.PlaybackCommand
import dev.dertyp.data.RemotePlaybackStatus
import dev.dertyp.data.User
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcRemoteControlService(
    private val user: User,
    private val sessionId: UUID?,
    private val service: RemoteControlService
) : IRemoteControlService {
    private fun session(): UUID = sessionId ?: throw IllegalStateException("Remote control requires a session")

    override suspend fun reportStatus(status: RemotePlaybackStatus) {
        service.reportStatus(session(), status)
    }

    override suspend fun getStatus(sessionId: UUID): RemotePlaybackStatus? = service.getStatus(user.id, sessionId)

    override fun observeStatus(sessionId: UUID): Flow<RemotePlaybackStatus> =
        service.observeStatus(user.id, sessionId)

    override suspend fun sendCommand(sessionId: UUID, command: PlaybackCommand): ClientRequestStatus =
        service.sendCommand(user.id, session(), sessionId, command)
}
