package dev.dertyp

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.dertyp.data.AuthenticationRequest
import dev.dertyp.data.AuthenticationResponse
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.serialization.kotlinx.protobuf.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
fun Application.configureRouting() {
    install(ContentNegotiation) {
        json()
        protobuf()
    }
    install(SSE)
    install(OpenApi)
    install(Compression)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        val clientId = environment.config.property("client.id").getString()
        val clientSecret = environment.config.property("client.secret").getString()

        val jwtAudience = environment.config.property("jwt.audience").getString()
        val jwtIssuer = environment.config.property("jwt.issuer").getString()
        val jwtSecret = environment.config.property("jwt.secret").getString()

        route("api.json") {
            openApi()
        }
        route("swagger") {
            swaggerUI("/api.json") {

            }
        }

        post("/authenticate", {
            request {
                body<AuthenticationRequest>()
            }
        }) {
            val authenticationRequest = call.receive<AuthenticationRequest>()

            if (authenticationRequest.username != clientId || authenticationRequest.password != clientSecret) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            }

            val expiresAt = Date(System.currentTimeMillis() + 1.hours.inWholeMilliseconds)

            val token = JWT.create()
                .withAudience(jwtAudience)
                .withIssuer(jwtIssuer)
                .withClaim("username", authenticationRequest.username)
                .withExpiresAt(expiresAt)
                .sign(Algorithm.HMAC256(jwtSecret))

            call.respond(HttpStatusCode.OK, AuthenticationResponse(
                token = token,
                expiresAt = expiresAt
            ))
        }
    }
}
