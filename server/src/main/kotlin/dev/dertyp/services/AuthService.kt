package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.data.User

class AuthService(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService
) : Service() {

    suspend fun validateUser(username: String, password: String): User {
        val user = userService.findUserByUsername(username)
            ?: throw IllegalArgumentException("Invalid username or password")

        val passwordMatches = BCrypt.verifyer().verify(
            password.toCharArray(),
            user.passwordHash
        )
        if (!passwordMatches.verified) {
            throw IllegalArgumentException("Invalid username or password")
        }
        return user
    }

    suspend fun refreshToken(refreshToken: String): AuthenticationResponse {
        val dbToken = refreshTokenService.validByTokenHash(refreshToken)
            ?: throw IllegalArgumentException("Invalid refresh token")

        val user = userService.findUserById(dbToken.userId)
            ?: throw IllegalArgumentException("Invalid user")

        val sessionId = refreshTokenService.getSessionId(refreshToken)

        return jwtService.generateToken(user, sessionId)
            ?: throw IllegalStateException("Something went wrong inserting the refresh token.")
    }
}