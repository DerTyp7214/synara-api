package dev.dertyp.routing

import dev.dertyp.core.getUser
import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.data.UserPlaylist
import dev.dertyp.services.IUserPlaylistService
import dev.dertyp.services.UserPlaylistService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.rpc.krpc.ktor.server.rpc
import org.koin.ktor.ext.inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun Route.userPlaylist() {
    route("/userPlaylist", {
        tags("playlist")
    }) {
        rpc {
            val userPlaylistService by inject<UserPlaylistService>()

            registerService<IUserPlaylistService> { userPlaylistService }
        }
        get("/byId/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The playlist id."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The playlist with the id."
                    body<UserPlaylist>()
                }
            }
        }) {
            val service by inject<UserPlaylistService>()

            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val playlist = service.byId(id) ?: return@get call.respond(HttpStatusCode.NotFound)

            call.respond(playlist)
        }
        get("/search/{query}", {
            request {
                pathParameter<String>("query") {
                    description = "The playlist query."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "The playlists with the query containing the query."
                    body<PaginatedResponse<Playlist>>()
                }
            }
        }) {
            val service by inject<UserPlaylistService>()
            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.BadRequest, "No user exists.")

            val query = call.parameters["query"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.rankedSearch(user.id, page, pageSize, query))
        }
        get("/list", {
            request {
                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "Lists all playlists."
                    body<PaginatedResponse<Playlist>>()
                }
            }
        }) {
            val service by inject<UserPlaylistService>()
            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.BadRequest, "No user exists.")

            val (page, pageSize) = call.paging()
            call.respond(service.allPlaylists(user.id, page, pageSize))
        }
        post("/add/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The playlist id."
                }
                body<List<String>> {
                    description = "The list of song ids to add to the playlist."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The number of songs added to the playlist."
                    body<Int>()
                }
            }
        }) {
            val service by inject<UserPlaylistService>()

            val playlistId = call.parameters["id"]?.toUUIDOrNull() ?: return@post call.respond(
                HttpStatusCode.NotFound,
                "No playlist with the id."
            )
            val songIds = call.receive<List<String>>().mapNotNull { it.toUUIDOrNull() }

            if (songIds.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, "No song ids provided.")
            }

            val resultRows =
                service.addToPlaylist(playlistId, songIds.map { Clock.System.now().toEpochMilliseconds() to it })

            call.respond(resultRows.size)
        }
        post("/remove/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The playlist id."
                }
                body<List<String>> {
                    description = "The list of song ids to remove from the playlist."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The number of songs removed from the playlist."
                    body<Int>()
                }
            }
        }) {
            val service by inject<UserPlaylistService>()

            val playListId = call.parameters["id"]?.toUUIDOrNull() ?: return@post call.respond(
                HttpStatusCode.NotFound,
                "No playlist with the id."
            )
            val songIds = call.receive<List<String>>().mapNotNull { it.toUUIDOrNull() }
            if (songIds.isEmpty()) {
                return@post call.respond(HttpStatusCode.BadRequest, "No song ids provided.")
            }

            val amount = service.removeFromPlaylist(playListId, songIds)
            call.respond(amount)
        }
    }
}