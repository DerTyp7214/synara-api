package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.core.*
import dev.dertyp.data.*
import io.github.smiley4.ktoropenapi.config.descriptors.ValueExampleDescriptor
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class JwtService(
    environment: ApplicationEnvironment,
    private val userService: UserService,
    private val refreshTokenService: RefreshTokenService,
    private val sessionService: SessionService
) : Service() {
    val jwtAudience = environment.config.property("jwt.audience").getString()
    val jwtIssuer = environment.config.property("jwt.issuer").getString()
    val jwtRealm = environment.config.property("jwt.realm").getString()
    val jwtSecret = environment.config.property("jwt.secret").getString()

    fun authenticated(route: Route, routeBuilder: Route.() -> Unit) = route.authenticate("synara-auth") {
        route({
            request {
                authHeader()
            }
        }) { routeBuilder() }
    }

    fun authenticate(application: Application) =
        application.authentication {
            jwt("synara-auth") {
                realm = jwtRealm
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(jwtSecret))
                        .withAudience(jwtAudience)
                        .withIssuer(jwtIssuer)
                        .build()
                )
                authHeader { call ->
                    val token = call.request.cookies["synara-auth"]
                    if (token != null) return@authHeader HttpAuthHeader.Single("Bearer", token)
                    call.request.parseAuthorizationHeader()
                }
                validate { credential ->
                    validateToken(credential.payload)
                }
                challenge { _, _ ->
                    call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
                }
                skipWhen { call ->
                    call.request.path().endsWith("/callback") || call.request.path().contains("/proxy/")
                }
            }
        }

    suspend fun validateToken(payload: com.auth0.jwt.interfaces.Payload): JWTPrincipal? {
        if (payload.audience.contains(jwtAudience)) {
            val sessionId = payload
                .getClaim("ses").asString()
                ?.let { UUID.fromString(it) } ?: return null

            val isActive = sessionService.isSessionActive(sessionId)
            if (!isActive) return null

            return JWTPrincipal(payload)
        }
        return null
    }

    suspend fun validateToken(token: String): JWTPrincipal? {
        return try {
            val verifier = JWT
                .require(Algorithm.HMAC256(jwtSecret))
                .withAudience(jwtAudience)
                .withIssuer(jwtIssuer)
                .build()
            val decoded = verifier.verify(token)
            validateToken(decoded)
        } catch (_: Exception) {
            null
        }
    }

    fun authenticate(route: Route) = route.apply {
        post("/authenticate", {
            request {
                body<AuthenticationRequest>()
            }
            response {
                HttpStatusCode.OK to {
                    body<AuthenticationResponse>()
                }
                HttpStatusCode.Unauthorized to {
                    body<String> {
                        example(ValueExampleDescriptor("message", "Invalid username or password"))
                    }
                }
            }
        }) {
            val authenticationRequest = call.receive<AuthenticationRequest>()

            val user = userService.findUserByUsername(authenticationRequest.username) ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                "Invalid username or password"
            )

            val passwordMatches = BCrypt.verifyer().verify(
                authenticationRequest.password.toCharArray(),
                user.passwordHash
            )
            if (!passwordMatches.verified) return@post call.respond(
                HttpStatusCode.Unauthorized,
                "Invalid username or password"
            )

            val userAgent = call.request.userAgent() ?: "Unknown"
            val ipAddress = call.request.origin.remoteHost

            val sessionId = sessionService.createSession(
                userId = user.id,
                userAgent = userAgent,
                ipAddress = ipAddress
            )

            val token = generateToken(user, sessionId) ?: return@post call.respond(
                HttpStatusCode.InternalServerError,
                "Something went wrong inserting the refresh token."
            )

            call.respond(HttpStatusCode.OK, token)
        }

        post("/refresh-token", {
            request {
                body<RefreshTokenRequest>()
            }
            response {
                HttpStatusCode.OK to {
                    body<AuthenticationResponse>()
                }
            }
        }) {
            val refreshToken = call.receive<RefreshTokenRequest>().refreshToken

            val dbToken = refreshTokenService.validByTokenHash(refreshToken) ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                "Invalid refresh token"
            )

            val user = userService.findUserById(dbToken.userId) ?: return@post call.respond(
                HttpStatusCode.Unauthorized,
                "Invalid user"
            )

            val sessionId = refreshTokenService.getSessionId(refreshToken)

            val newToken = generateToken(user, sessionId) ?: return@post call.respond(
                HttpStatusCode.InternalServerError,
                "Something went wrong inserting the refresh token."
            )

            call.respond(HttpStatusCode.OK, newToken)
        }

        authenticated(this) {
            post("/register", {
                request {
                    body<AuthenticationRequest>()
                }

                response {
                    HttpStatusCode.OK to {
                        body<Map<String, String>> {
                            example(
                                ValueExampleDescriptor(
                                    "UserResponse",
                                    mapOf("userId" to UUID.randomUUID().toString())
                                )
                            )
                        }
                    }
                }
            }) {
                val callingUser = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized, "Invalid user")
                if (!callingUser.isAdmin) return@post call.respond(HttpStatusCode.Forbidden, "Only admins can register new users")

                val authenticationRequest = call.receive<AuthenticationRequest>()

                val user = userService.findUserByUsername(authenticationRequest.username)
                if (user != null) return@post call.respond(HttpStatusCode.Conflict, "User already exists")

                val newUser =
                    userService.createUser(authenticationRequest) ?: return@post call.respond(HttpStatusCode.BadRequest)

                call.respond(HttpStatusCode.OK, mapOf("userId" to newUser.id.toString()))
            }
            get("/userInfo", {
                response {
                    HttpStatusCode.OK to {
                        body<UserInfo> {}
                    }
                }
            }) {
                val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "Invalid user")

                call.respond(HttpStatusCode.OK, UserInfo.fromUser(user))
            }
            get("/sessions", {
                response {
                    HttpStatusCode.OK to {
                        body<List<Session>> {}
                    }
                }
            }) {
                val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized, "Invalid user")
                val sessions = sessionService.getSessions(user.id)
                call.respond(HttpStatusCode.OK, sessions)
            }
            delete("/sessions/{sessionId}", {
                request {
                    pathParameter<String>("sessionId") {
                        description = "The session id to deactivate."
                    }
                }
                response {
                    HttpStatusCode.OK to {
                        description = "Session deactivated."
                    }
                }
            }) {
                val user = call.getUser() ?: return@delete call.respond(HttpStatusCode.Unauthorized, "Invalid user")
                val sessionId = call.parameters["sessionId"]?.toUUIDOrNull() ?: return@delete call.respond(HttpStatusCode.BadRequest)

                sessionService.deactivateSession(sessionId, user.id)
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    suspend fun generateToken(user: User, sessionId: UUID?): AuthenticationResponse? {
        val expiresAt = Instant.now().toEpochMilli().date + 24.hours

        val token = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("usr", user.username)
            .apply {
                if (sessionId != null) {
                    withClaim("ses", sessionId.toString())
                }
            }
            .withExpiresAt(expiresAt)
            .sign(Algorithm.HMAC256(jwtSecret))

        val refreshToken = generateRefreshToken()

        if (refreshTokenService.createToken(user.id, 30.days, refreshToken, sessionId) == null)
            return null

        return AuthenticationResponse(
            token = token,
            refreshToken = refreshToken,
            expiresAt = expiresAt
        )
    }

    fun generateRefreshToken(): String {
        val random = SecureRandom.getInstanceStrong()
        val bytes = ByteArray(192)
        random.nextBytes(bytes)
        return Base64.UrlSafe.encode(bytes).take(255)
    }
}
