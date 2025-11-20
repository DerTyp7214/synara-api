package dev.dertyp.routing

import com.google.gson.Gson
import com.ucasoft.ktor.simpleCache.cacheOutput
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
import org.jetbrains.exposed.sql.Database
import kotlin.time.Duration

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
            cacheOutput(Duration.INFINITE) {
                route("/imageUrl") {
                    get("/byTrackId/{trackId}", {
                        request {
                            pathParameter<String>("trackId") {
                                description = "The service track ID."
                            }
                        }
                    }) {
                        val service = SyncService.getInstance(call, database)

                        val trackId = call.parameters["trackId"]
                        if (trackId == null) return@get call.respond(HttpStatusCode.BadRequest)

                        val albumId = service.getAlbumIdByTrackId(trackId)
                        if (albumId == null) return@get call.respond(HttpStatusCode.NotFound)

                        val images = service.getImageUrlByAlbumId(albumId)
                        if (images.isEmpty()) return@get call.respond(HttpStatusCode.NotFound)

                        val image = images.maxBy { it.height }

                        call.respond(image)
                    }
                }
            }

            route("/liked") {
                route("/tracks", HttpMethod.Get, {
                    request {
                        queryParameter<Boolean>("all") {
                            description = "Get all songs, not only the latest."
                        }
                    }
                }) {
                    sse {
                        val user = call.getUser()
                        if (user == null) return@sse call.respond(HttpStatusCode.Unauthorized)

                        val service = SyncService.getInstance(call, database)

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