package dev.dertyp.services

import dev.dertyp.core.getUser
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.data.User
import dev.dertyp.utils.LogMode
import dev.dertyp.utils.LogParam
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.*
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.*

class RpcAuthService(
    private val call: ApplicationCall,
    private val authService: AuthService,
    private val sessionService: SessionService,
    private val jwtService: JwtService,
    private val userService: UserService
) : IAuthService {
    override suspend fun authenticate(
        username: String,
        @LogParam(mode = LogMode.MASK) password: String
    ): AuthenticationResponse {
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

    override suspend fun createDeviceSession(userAgent: String): AuthenticationResponse {
        val user = call.getUser()
            ?: resolveUserFromRawToken()
            ?: throw IllegalStateException("Not authenticated.")

        val sessionId = sessionService.createSession(
            userId = user.id,
            userAgent = userAgent,
            ipAddress = call.request.origin.remoteHost
        )

        return jwtService.generateToken(user, sessionId)
            ?: throw IllegalStateException("Something went wrong inserting the refresh token.")
    }

    // IAuthService is registered on the public routes, so the JWT principal is not populated by
    // the auth plugin and the token has to be resolved from the request directly.
    private suspend fun resolveUserFromRawToken(): User? {
        val rawToken = call.request.cookies["synara-auth"]
            ?: (call.request.parseAuthorizationHeader() as? HttpAuthHeader.Single)
                ?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }
                ?.blob
            ?: return null
        val principal = jwtService.validateToken(rawToken) ?: return null
        val username = principal.payload.getClaim("usr").asString() ?: return null
        return userService.findUserByUsername(username)
    }
}