package dev.dertyp.services

import dev.dertyp.data.User
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.MapApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.days

class JwtServiceTest {
    private lateinit var jwtService: JwtService
    private lateinit var environment: ApplicationEnvironment
    private lateinit var userService: UserService
    private lateinit var refreshTokenService: RefreshTokenService
    private lateinit var sessionService: SessionService

    private val jwtSecret = "my-super-secret-at-least-32-chars-long"
    private val jwtAudience = "test-audience"
    private val jwtIssuer = "test-issuer"

    @BeforeEach
    fun setup() {
        environment = mockk()
        val config = MapApplicationConfig(
            "jwt.audience" to jwtAudience,
            "jwt.issuer" to jwtIssuer,
            "jwt.realm" to "test-realm",
            "jwt.secret" to jwtSecret
        )
        every { environment.config } returns config

        userService = mockk()
        refreshTokenService = mockk()
        sessionService = mockk()

        jwtService = JwtService(environment, userService, refreshTokenService, sessionService)
    }

    @Test
    fun `generateToken should return valid response and create refresh token`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "hash", isAdmin = false)
        val sessionId = UUID.randomUUID()

        coEvery { refreshTokenService.createToken(user.id, 30.days, any(), sessionId) } returns mockk()

        val response = jwtService.generateToken(user, sessionId)

        assertNotNull(response)
        assertNotNull(response?.token)
        assertNotNull(response?.refreshToken)
        assertTrue(response!!.refreshToken.length > 10)
    }

    @Test
    fun `validateToken should return principal for valid token`() = runBlocking {
        val userId = UUID.randomUUID()
        val user = User(id = userId, username = "testuser", passwordHash = "hash", isAdmin = false)
        val sessionId = UUID.randomUUID()

        coEvery { refreshTokenService.createToken(userId, any(), any(), sessionId) } returns mockk()
        coEvery { sessionService.isSessionActive(sessionId) } returns true

        val response = jwtService.generateToken(user, sessionId)
        val principal = jwtService.validateToken(response!!.token)

        assertNotNull(principal)
        assertEquals("testuser", principal?.payload?.getClaim("usr")?.asString())
        assertEquals(sessionId.toString(), principal?.payload?.getClaim("ses")?.asString())
    }

    @Test
    fun `validateToken should return null if session is inactive`() = runBlocking {
        val userId = UUID.randomUUID()
        val user = User(id = userId, username = "testuser", passwordHash = "hash", isAdmin = false)
        val sessionId = UUID.randomUUID()

        coEvery { refreshTokenService.createToken(userId, any(), any(), sessionId) } returns mockk()
        coEvery { sessionService.isSessionActive(sessionId) } returns false

        val response = jwtService.generateToken(user, sessionId)
        val principal = jwtService.validateToken(response!!.token)

        assertNull(principal)
    }

    @Test
    fun `validateToken should return null for invalid token`() = runBlocking {
        val principal = jwtService.validateToken("invalid.token.here")
        assertNull(principal)
    }

    @Test
    fun `generateRefreshToken should return long safe string`() {
        val token = jwtService.generateRefreshToken()
        assertTrue(token.length >= 128)
        
        assertFalse(token.contains("+"))
        assertFalse(token.contains("/"))
        assertFalse(token.contains("="))
    }
}
