package dev.dertyp.example

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.server.webjars.*
import io.ktor.sse.*

fun Application.configureRouting() {
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(text = "500: $cause" , status = HttpStatusCode.InternalServerError)
        }
    }
    install(Webjars) {
        path = "/webjars" //defaults to /webjars
    }
    routing {
        get("/") {
            call.respondText("Hello World!")
        }
        sse("/hello") {
            send(ServerSentEvent("world"))
        }
        // Static plugin. Try to access `/static/index.html`
        staticResources("/static", "static")
        get("/webjars") {
            call.respondText("<script src='/webjars/jquery/jquery.js'></script>", ContentType.Text.Html)
        }
    }
}
