package dev.dertyp.services

import dev.dertyp.data.ClientDescription
import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import dev.dertyp.data.OnlineDevice
import dev.dertyp.data.User
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcClientRequestService(
    private val user: User,
    private val sessionId: UUID,
    private val service: ClientRequestService
) : IClientRequestService {
    override fun observeRequests(): Flow<ClientRequest> = service.observeRequests(sessionId)

    override fun connect(description: ClientDescription): Flow<ClientRequest> =
        service.connect(user.id, sessionId, description)

    override suspend fun complete(requestId: UUID, status: ClientRequestStatus) {
        service.complete(sessionId, requestId, status)
    }

    override suspend fun getOnlineDevices(): List<OnlineDevice> = service.onlineDevices(user.id, sessionId)
}
