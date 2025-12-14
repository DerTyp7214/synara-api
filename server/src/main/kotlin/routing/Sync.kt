package dev.dertyp.routing

import com.google.gson.Gson
import dev.dertyp.core.getUser
import dev.dertyp.services.SongService
import dev.dertyp.services.sync.SyncService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import org.koin.ktor.ext.inject

fun Route.sync() {
    route("/sync/{service}", {
        request {
            pathParameter<String>("service") {
                description = "The service name (like 'tidal')."
            }
        }
        tags("sync")
    }) {
        get("/auth") {
            SyncService.handleAuth(call)
        }

        get("/callback") {
            SyncService.handleCallback(call, call.request.queryParameters["state"])
        }

        route("/get") {
            route("/liked") {
                route("/tracks", HttpMethod.Get, {
                    request {
                        queryParameter<Boolean>("all") {
                            description = "Get all songs, not only the latest."
                        }
                    }
                }) {
                    sse {
                        val songService by inject<SongService>()

                        val user = call.getUser() ?: return@sse call.respond(HttpStatusCode.Unauthorized)

                        val service = SyncService.getInstance(call)

                        val all = call.request.queryParameters["all"]?.toBoolean() ?: false

                        val gson = Gson()
                        service.getLikedSongs { songs ->
                            all || songService.byTidalTrackIds(songs.map { it.id }, user.id).isEmpty()
                        }.collect {
                            send(
                                ServerSentEvent(
                                    data = gson.toJson(it),
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}