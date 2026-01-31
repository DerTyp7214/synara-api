package dev.dertyp.services

import dev.dertyp.data.Session
import dev.dertyp.data.User
import java.util.*

class RpcSessionService(
    private val user: User,
    private val sessionService: SessionService
) : ISessionService {
    override suspend fun deactivateSession(sessionId: UUID) {
        sessionService.deactivateSession(sessionId, user.id)
    }

    override suspend fun getSessions(): List<Session> {
        return sessionService.getSessions(user.id)
    }
}