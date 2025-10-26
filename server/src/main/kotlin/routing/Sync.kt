package dev.dertyp.routing

import com.google.gson.Gson
import dev.dertyp.services.SongService
import dev.dertyp.services.sync.SyncService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import org.jetbrains.exposed.sql.Database

fun Route.sync(database: Database, songService: SongService) {
    route("/sync/{service}", {
        request {
            pathParameter<String>("service") {
                description = "The service name (like 'tidal')."
            }
        }
        tags("sync")
    }) {
        get("/auth") {
            SyncService.handleAuth(call, database)
        }

        get("/callback") {
            SyncService.handleCallback(call, database, call.request.queryParameters["state"])
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
                        val service = SyncService.getInstance(call, database)

                        val all = call.request.queryParameters["all"]?.toBoolean() ?: false

                        val gson = Gson()
                        service.getLikedSongs { songs ->
                            all || songService.byTidalTrackIds(songs.map { it.id }).isEmpty()
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