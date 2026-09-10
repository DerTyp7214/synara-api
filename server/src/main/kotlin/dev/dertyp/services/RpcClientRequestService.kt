package dev.dertyp.services

import dev.dertyp.data.ClientRequest
import dev.dertyp.data.ClientRequestStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class RpcClientRequestService(
    private val sessionId: UUID,
    private val service: ClientRequestService
) : IClientRequestService {
    override fun observeRequests(): Flow<ClientRequest> = service.observeRequests(sessionId)

    override suspend fun complete(requestId: UUID, status: ClientRequestStatus) {
        service.complete(sessionId, requestId, status)
    }
}
