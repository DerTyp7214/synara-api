package dev.dertyp.routing

import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.PlaybackState
import dev.dertyp.services.PlaybackService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.playback() {
    route("/playback", {
        tags("playback")
    }) {
        get("/{sessionId}", {
            request {
                pathParameter<String>("sessionId") {
                    description = "The session id."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The playback state."
                    body<PlaybackState>()
                }
                HttpStatusCode.NotFound to {
                    description = "Session not found or no state available."
                }
            }
        }) {
            val service by inject<PlaybackService>()
            val sessionId = call.parameters["sessionId"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val state = service.getPlaybackState(sessionId) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(state)
        }

        post("/{sessionId}", {
            request {
                pathParameter<String>("sessionId") {
                    description = "The session id."
                }
                body<PlaybackState> {
                    description = "The playback state to set."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "State updated."
                }
            }
        }) {
            val service by inject<PlaybackService>()
            val sessionId = call.parameters["sessionId"]?.toUUIDOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)
            val state = call.receive<PlaybackState>()

            service.setPlaybackState(sessionId, state)
            call.respond(HttpStatusCode.OK)
        }
    }
}
