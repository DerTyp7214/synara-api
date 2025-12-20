package dev.dertyp.routing

import dev.dertyp.core.getUser
import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.data.UserPlaylist
import dev.dertyp.services.UserPlaylistService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.userPlaylist() {
    route("/userPlaylist", {
        tags("playlist")
    }) {
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
    }
}