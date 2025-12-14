package dev.dertyp.routing

import com.google.gson.Gson
import dev.dertyp.core.getUser
import dev.dertyp.core.omitLyrics
import dev.dertyp.data.UserSong
import dev.dertyp.services.SongService
import dev.dertyp.services.sync.SyncService
import dev.dertyp.services.sync.TidalSyncService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
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
                post("/byTidalIds", {
                    request {
                        body<List<String>> {
                            description = "Tidal song ids"
                        }
                    }

                    response {
                        HttpStatusCode.OK to {
                            description = "Songs that match"
                            body<List<UserSong>>()
                        }
                    }
                }) {
                    val songService by inject<SongService>()

                    val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)

                    val service = SyncService.getInstance(call)
                    if (service !is TidalSyncService) return@post call.respond(
                        HttpStatusCode.MethodNotAllowed,
                        "Only Tidal is supported."
                    )

                    val ids = call.receive<List<String>>()

                    call.respond(songService.byTidalTrackIds(ids, user.id).map { it.omitLyrics() })
                }
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
                        if (service !is TidalSyncService) return@sse call.respond(
                            HttpStatusCode.MethodNotAllowed,
                            "Only Tidal is supported."
                        )

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