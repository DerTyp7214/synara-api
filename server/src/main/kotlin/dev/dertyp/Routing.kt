package dev.dertyp

import dev.dertyp.core.ApplicationScope
import dev.dertyp.routing.mirrorRouting
import dev.dertyp.routing.registerAuthenticatedServices
import dev.dertyp.routing.registerPublicServices
import dev.dertyp.services.JwtService
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktoropenapi.route
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.protobuf.protobuf
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.http.content.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.sse.SSE
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.cbor.cbor
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.koin
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
fun Application.configureRouting() {
    install(ContentNegotiation) {
        json(ApplicationScope.json)
        protobuf()
    }
    install(SSE)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    install(Krpc) {
        serialization {
            cbor(ApplicationScope.cbor)
        }
    }
    install(OpenApi)
    install(Compression)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        staticResources("/static", "static")

        route("api.json") {
            openApi()
        }
        route("swagger") {
            swaggerUI("/api.json") {

            }
        }

        val koin = koin()
        val jwtService by inject<JwtService>()

        rpc("/rpc") {
            registerPublicServices(koin)
        }

        rpc("/rpc/auth") {
            registerPublicServices(koin)
        }

        jwtService.authenticated(this) {
            rpc("/rpc/services") {
                registerAuthenticatedServices(koin)
            }
        }

        jwtService.authenticate(this)

        mirrorRouting()
    }
}
