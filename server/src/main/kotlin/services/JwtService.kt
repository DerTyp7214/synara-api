package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.core.authHeader
import dev.dertyp.core.date
import dev.dertyp.core.getUser
import dev.dertyp.core.plus
import dev.dertyp.data.*
import io.github.smiley4.ktoropenapi.config.descriptors.ValueExampleDescriptor
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class JwtService(
    environment: ApplicationEnvironment,
    private val userService: UserService,
    private val refreshTokenService: RefreshTokenService
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
                validate { credential ->
                    if (credential.payload.audience.contains(jwtAudience)) JWTPrincipal(credential.payload) else null
                }
                challenge { _, _ ->
                    call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
                }
                skipWhen { call ->
                    call.request.path().endsWith("/callback")
                }
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

            val token = generateToken(user) ?: return@post call.respond(
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

            val newToken = generateToken(user) ?: return@post call.respond(
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
        }
    }

    suspend fun generateToken(user: User): AuthenticationResponse? {
        val expiresAt = Instant.now().toEpochMilli().date + 24.hours

        val token = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("username", user.username)
            .withExpiresAt(expiresAt)
            .sign(Algorithm.HMAC256(jwtSecret))

        val refreshToken = generateRefreshToken()

        if (refreshTokenService.createToken(user.id, 30.days, refreshToken) == null)
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