package dev.dertyp

import dev.dertyp.core.ApplicationScope
import dev.dertyp.services.AuthService
import dev.dertyp.services.IAuthService
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
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.ktor.ext.getKoin
import org.koin.ktor.ext.inject
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalSerializationApi::class, ExperimentalTime::class)
fun Application.configureRouting() {
    install(ContentNegotiation) {
        json(ApplicationScope.json)
        protobuf()
    }
    install(SSE)
    install(Krpc) {
        serialization {
            json(ApplicationScope.json)
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
        route("api.json") {
            openApi()
        }
        route("swagger") {
            swaggerUI("/api.json") {

            }
        }

        rpc("/api/rpc") {
            val service by inject<AuthService>()

            registerService<IAuthService> { service }
        }

        getKoin().get<JwtService>().authenticate(this)
    }
}