package dev.dertyp

import dev.dertyp.repos.FakeSongRepository
import dev.dertyp.song.song
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*

fun Application.configureRouting() {
    install(ContentNegotiation) {
        json()
    }
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
    routing {
        openAPI(path = "openapi")
        staticResources("/static", "static")

        song(FakeSongRepository())
    }
}
