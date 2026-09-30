package dev.dertyp.routing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.ucasoft.ktor.simpleCache.SimpleCache
import com.ucasoft.ktor.simpleMemoryCache.memoryCache
import dev.dertyp.data.User
import dev.dertyp.services.JwtService
import dev.dertyp.services.RemoteMirrorService
import dev.dertyp.services.SessionService
import dev.dertyp.services.UserService
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class CachedAuthenticatedRouteTest {

    private val secret = "mirror-test-secret"
    private val remoteMirrorService = mockk<RemoteMirrorService>()
    private val userService = mockk<UserService>()
    private val sessionService = mockk<SessionService>(relaxed = true)
    private val jwtService = mockk<JwtService>()

    private val admin = User(id = UUID.randomUUID(), username = "admin", passwordHash = "x", isAdmin = true)
    private val member = User(id = UUID.randomUUID(), username = "member", passwordHash = "x", isAdmin = false)

    private fun tokenFor(username: String) = JWT.create()
        .withClaim(JwtService.CLAIM_USERNAME, username)
        .withClaim(JwtService.CLAIM_SESSION, UUID.randomUUID().toString())
        .sign(Algorithm.HMAC256(secret))

    private val adminToken = tokenFor("admin")
    private val memberToken = tokenFor("member")

    private val imagePath = "/admin/mirror/remote-image/00000000-0000-0000-0000-000000000001?size=100&host=remote"

    @AfterEach
    fun tearDown() {
        runCatching { stopKoin() }
    }

    private fun ApplicationTestBuilder.setUpApplication() {
        coEvery { remoteMirrorService.getRemoteImageData(any(), any(), any()) } returns "image".toByteArray()
        coEvery { userService.findUserByUsername("admin") } returns admin
        coEvery { userService.findUserByUsername("member") } returns member
        coEvery { jwtService.validateToken(any<String>()) } returns null
        coEvery { jwtService.validateToken(adminToken) } returns JWTPrincipal(JWT.decode(adminToken))
        coEvery { jwtService.validateToken(memberToken) } returns JWTPrincipal(JWT.decode(memberToken))

        environment { config = MapApplicationConfig() }
        application {
            install(Koin) {
                modules(
                    module {
                        single { remoteMirrorService }
                        single { userService }
                        single { sessionService }
                        single { jwtService }
                    },
                )
            }
            install(SSE)
            install(SimpleCache) {
                memoryCache {
                    invalidateAt = 10.minutes
                }
            }
            install(Authentication) {
                jwt(JwtService.AUTH_PROVIDER) {
                    verifier(JWT.require(Algorithm.HMAC256(secret)).build())
                    authHeader { call ->
                        val token = call.request.cookies[JwtService.AUTH_COOKIE]
                        if (token != null) return@authHeader HttpAuthHeader.Single("Bearer", token)
                        call.request.parseAuthorizationHeader()
                    }
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing { mirrorRouting() }
        }
    }

    private suspend fun ApplicationTestBuilder.fetch(token: String?, asCookie: Boolean = false) =
        client.get(imagePath) {
            if (token != null) {
                if (asCookie) {
                    header(HttpHeaders.Cookie, "${JwtService.AUTH_COOKIE}=$token")
                } else {
                    header(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        }

    @Test
    fun `cached remote image is never served to unauthenticated callers`() = testApplication {
        setUpApplication()

        val first = fetch(adminToken)
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals("image", first.bodyAsText())
        assertEquals(HttpStatusCode.OK, fetch(adminToken, asCookie = true).status)
        coVerify(exactly = 1) { remoteMirrorService.getRemoteImageData(any(), any(), any()) }

        assertEquals(HttpStatusCode.Unauthorized, fetch(null).status)
        assertEquals(HttpStatusCode.Unauthorized, fetch("not-a-token").status)
    }

    @Test
    fun `cached remote image is never served to non admin callers`() = testApplication {
        setUpApplication()

        assertEquals(HttpStatusCode.OK, fetch(adminToken).status)
        assertEquals(HttpStatusCode.Forbidden, fetch(memberToken).status)
        assertEquals(HttpStatusCode.Forbidden, fetch(memberToken, asCookie = true).status)
    }

    @Test
    fun `remote image rejects callers before anything is cached`() = testApplication {
        setUpApplication()

        assertEquals(HttpStatusCode.Unauthorized, fetch(null).status)
        assertEquals(HttpStatusCode.Forbidden, fetch(memberToken).status)
        coVerify(exactly = 0) { remoteMirrorService.getRemoteImageData(any(), any(), any()) }

        val admin = fetch(adminToken)
        assertEquals(HttpStatusCode.OK, admin.status)
        assertEquals("image", admin.bodyAsText())
    }
}
