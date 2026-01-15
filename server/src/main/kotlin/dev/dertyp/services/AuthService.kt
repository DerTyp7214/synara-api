package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dertyp.data.AuthenticationResponse

class AuthService(
    private val userService: UserService,
    private val jwtService: JwtService,
    private val refreshTokenService: RefreshTokenService
) : IAuthService, Service() {
    override suspend fun authenticate(username: String, password: String): AuthenticationResponse {
        val user = userService.findUserByUsername(username)
            ?: throw IllegalArgumentException("Invalid username or password")

        val passwordMatches = BCrypt.verifyer().verify(
            password.toCharArray(),
            user.passwordHash
        )
        if (!passwordMatches.verified) {
            throw IllegalArgumentException("Invalid username or password")
        }

        return jwtService.generateToken(user)
            ?: throw IllegalStateException("Something went wrong inserting the refresh token.")
    }

    override suspend fun refreshToken(refreshToken: String): AuthenticationResponse {
        val dbToken = refreshTokenService.validByTokenHash(refreshToken)
            ?: throw IllegalArgumentException("Invalid refresh token")

        val user = userService.findUserById(dbToken.userId)
            ?: throw IllegalArgumentException("Invalid user")

        return jwtService.generateToken(user)
            ?: throw IllegalStateException("Something went wrong inserting the refresh token.")
    }
}