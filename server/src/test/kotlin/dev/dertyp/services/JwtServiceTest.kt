package dev.dertyp.services

import dev.dertyp.data.User
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.ApplicationConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class JwtServiceTest {
    private val environment = mockk<ApplicationEnvironment>()
    private val userService = mockk<UserService>()
    private val refreshTokenService = mockk<RefreshTokenService>()
    private val sessionService = mockk<SessionService>()
    
    private lateinit var jwtService: JwtService

    @BeforeEach
    fun setup() {
        val config = mockk<ApplicationConfig>()
        every { environment.config } returns config
        every { config.property("jwt.audience").getString() } returns "test-audience"
        every { config.property("jwt.issuer").getString() } returns "test-issuer"
        every { config.property("jwt.realm").getString() } returns "test-realm"
        every { config.property("jwt.secret").getString() } returns "test-secret"
        
        jwtService = JwtService(environment, userService, refreshTokenService, sessionService)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `generateToken and validateToken should work for active session`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "")
        val sessionId = UUID.randomUUID()
        
        coEvery { refreshTokenService.createToken(any(), any(), any(), any()) } returns mockk()
        
        val authResponse = jwtService.generateToken(user, sessionId)
        assertNotNull(authResponse)
        val token = authResponse!!.token
        
        coEvery { sessionService.isSessionActive(sessionId) } returns true
        
        val principal = jwtService.validateToken(token)
        assertNotNull(principal)
        assertEquals("testuser", principal!!.payload.getClaim("usr").asString())
    }

    @Test
    fun `validateToken should return null for inactive session`() = runBlocking {
        val user = User(id = UUID.randomUUID(), username = "testuser", passwordHash = "")
        val sessionId = UUID.randomUUID()
        
        coEvery { refreshTokenService.createToken(any(), any(), any(), any()) } returns mockk()
        
        val authResponse = jwtService.generateToken(user, sessionId)
        val token = authResponse!!.token
        
        coEvery { sessionService.isSessionActive(sessionId) } returns false
        
        val principal = jwtService.validateToken(token)
        assertNull(principal)
    }
}
