package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.dertyp.data.User
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthServiceTest {
    private val userService = mockk<UserService>()
    private val jwtService = mockk<JwtService>()
    private val refreshTokenService = mockk<RefreshTokenService>()
    private val service = AuthService(userService, jwtService, refreshTokenService)

    @Test
    fun `validateUser should return user if password matches`() {
        runBlocking {
            val password = "securepassword"
            val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
            val user = User(UUID.randomUUID(), "testuser", passwordHash = hash)

            coEvery { userService.findUserByUsername("testuser") } returns user

            val validated = service.validateUser("testuser", password)
            assertEquals(user.id, validated.id)
        }
    }

    @Test
    fun `validateUser should throw if password incorrect`() {
        runBlocking {
            val hash = BCrypt.withDefaults().hashToString(12, "correct".toCharArray())
            val user = User(UUID.randomUUID(), "testuser", passwordHash = hash)

            coEvery { userService.findUserByUsername("testuser") } returns user

            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { service.validateUser("testuser", "wrong") }
            }
        }
    }
}
