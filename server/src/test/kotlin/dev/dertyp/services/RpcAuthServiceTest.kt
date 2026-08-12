package dev.dertyp.services

import com.auth0.jwt.interfaces.Payload
import dev.dertyp.core.getUser
import dev.dertyp.data.AuthenticationResponse
import dev.dertyp.data.User
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.plugins.origin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Date
import java.util.UUID

class RpcAuthServiceTest {
    private val authService = mockk<AuthService>()
    private val sessionService = mockk<SessionService>()
    private val jwtService = mockk<JwtService>()
    private val userService = mockk<UserService>()
    private val call = mockk<ApplicationCall>(relaxed = true)
    private val service = RpcAuthService(call, authService, sessionService, jwtService, userService)

    private val user = User(UUID.randomUUID(), "testuser", passwordHash = "hash")

    @BeforeEach
    fun setUp() {
        mockkStatic("dev.dertyp.core.CallKt")
        mockkStatic("io.ktor.server.plugins.OriginConnectionPointKt")
        coEvery { call.getUser() } returns null
        every { call.request.origin.remoteHost } returns "127.0.0.1"
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic("dev.dertyp.core.CallKt")
        unmockkStatic("io.ktor.server.plugins.OriginConnectionPointKt")
    }

    @Test
    fun `createDeviceSession creates session for user resolved from token`() {
        runBlocking {
            val sessionId = UUID.randomUUID()
            val response = AuthenticationResponse("token", "refreshToken", Date())

            every { call.request.cookies["synara-auth"] } returns "raw-jwt"

            val payload = mockk<Payload> {
                every { getClaim("usr").asString() } returns "testuser"
            }
            coEvery { jwtService.validateToken("raw-jwt") } returns JWTPrincipal(payload)
            coEvery { userService.findUserByUsername("testuser") } returns user
            coEvery { sessionService.createSession(user.id, "AppleTV/1.0", any()) } returns sessionId
            coEvery { jwtService.generateToken(user, sessionId) } returns response

            val result = service.createDeviceSession("AppleTV/1.0")

            assertEquals(response, result)
            coVerify { sessionService.createSession(user.id, "AppleTV/1.0", any()) }
        }
    }

    @Test
    fun `createDeviceSession throws when unauthenticated`() {
        every { call.request.cookies["synara-auth"] } returns null
        every { call.request.headers[HttpHeaders.Authorization] } returns null

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.createDeviceSession("AppleTV/1.0") }
        }
    }

    @Test
    fun `createDeviceSession throws when token is invalid`() {
        every { call.request.cookies["synara-auth"] } returns "expired-jwt"
        coEvery { jwtService.validateToken("expired-jwt") } returns null

        assertThrows(IllegalStateException::class.java) {
            runBlocking { service.createDeviceSession("AppleTV/1.0") }
        }
    }
}
