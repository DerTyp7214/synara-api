package dev.dertyp.services

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.core.authHeader
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.data.AuthenticationResponse
import io.github.smiley4.ktoropenapi.config.descriptors.ValueExampleDescriptor
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*
import kotlin.time.Duration.Companion.hours

class JwtService(environment: ApplicationEnvironment, private val userService: UserService) {
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
                challenge { defaultScheme, realm ->
                    call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
                }
            }
        }

    fun authenticate(route: Route) = route.apply {
        post("/authenticate", {
            request {
                body<AuthenticationRequest>()
            }
        }) {
            val authenticationRequest = call.receive<AuthenticationRequest>()

            val user = userService.findUserByUsername(authenticationRequest.username)
            if (user == null) return@post call.respond(HttpStatusCode.Unauthorized, "Invalid username or password")

            val passwordMatches = BCrypt.verifyer().verify(
                authenticationRequest.password.toCharArray(),
                user.passwordHash
            )
            if (!passwordMatches.verified) return@post call.respond(
                HttpStatusCode.Unauthorized,
                "Invalid username or password"
            )

            call.respond(HttpStatusCode.OK, generateToken(authenticationRequest.username))
        }

        authenticated(this) {
            post("/register", {
                request {
                    body<AuthenticationRequest>()
                }

                response {
                    HttpStatusCode.OK to {
                        body<Map<String, String>> {
                            example(ValueExampleDescriptor("UserResponse", mapOf("userId" to UUID.randomUUID().toString())))
                        }
                    }
                }
            }) {
                val authenticationRequest = call.receive<AuthenticationRequest>()

                val user = userService.findUserByUsername(authenticationRequest.username)
                if (user != null) return@post call.respond(HttpStatusCode.Conflict, "User already exists")

                val newUser = userService.createUser(authenticationRequest)
                if (newUser == null) return@post call.respond(HttpStatusCode.BadRequest)

                call.respond(HttpStatusCode.OK, mapOf("userId" to newUser.id.toString()))
            }
        }
    }

    fun generateToken(username: String): AuthenticationResponse {
        val expiresAt = Date(System.currentTimeMillis() + 1.hours.inWholeMilliseconds)

        val token = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("username", username)
            .withExpiresAt(expiresAt)
            .sign(Algorithm.HMAC256(jwtSecret))

        return AuthenticationResponse(
            token = token,
            expiresAt = expiresAt
        )
    }
}