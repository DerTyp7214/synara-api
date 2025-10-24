package dev.dertyp

import dev.dertyp.services.JwtService
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.route
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.serialization.kotlinx.protobuf.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
fun Application.configureRouting(jwtService: JwtService) {
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
        route("api.json") {
            openApi()
        }
        route("swagger") {
            swaggerUI("/api.json") {

            }
        }

        jwtService.authenticate(this)
    }
}