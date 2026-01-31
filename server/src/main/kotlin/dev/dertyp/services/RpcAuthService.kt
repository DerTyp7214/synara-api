package dev.dertyp.services

import dev.dertyp.data.AuthenticationResponse
import io.ktor.server.application.*
import io.ktor.server.plugins.*

class RpcAuthService(
    private val call: ApplicationCall,
    private val authService: AuthService,
    private val sessionService: SessionService,
    private val jwtService: JwtService
) : IAuthService {
    override suspend fun authenticate(username: String, password: String): AuthenticationResponse {
        val user = authService.validateUser(username, password)

        val sessionId = sessionService.createSession(
            userId = user.id,
            userAgent = call.request.headers["User-Agent"] ?: "Unknown",
            ipAddress = call.request.origin.remoteHost
        )

        return jwtService.generateToken(user, sessionId)
            ?: throw IllegalStateException("Something went wrong inserting the refresh token.")
    }

    override suspend fun refreshToken(refreshToken: String): AuthenticationResponse {
        return authService.refreshToken(refreshToken)
    }
}