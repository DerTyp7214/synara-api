package dev.dertyp.routing

import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.services.IPlaylistService
import dev.dertyp.services.PlaylistService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.rpc.krpc.ktor.server.rpc
import org.koin.ktor.ext.inject

fun Route.playlist() {
    route("/playlist", {
        tags("playlist")
    }) {
        rpc {
            val service by inject<PlaylistService>()

            registerService<IPlaylistService> { service }
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
                    body<Playlist>()
                }
            }
        }) {
            val service by inject<PlaylistService>()

            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val playlist = service.byId(id) ?: return@get call.respond(HttpStatusCode.NotFound)

            call.respond(playlist)
        }
        get("/byName/{name}", {
            request {
                pathParameter<String>("name") {
                    description = "The playlist name."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The playlist with the name."
                    body<Playlist>()
                }
            }
        }) {
            val service by inject<PlaylistService>()

            val name = call.parameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val playlist = service.byName(name) ?: return@get call.respond(HttpStatusCode.NotFound)

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
            val service by inject<PlaylistService>()

            val query = call.parameters["query"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.rankedSearch(page, pageSize, query))
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
            val service by inject<PlaylistService>()

            val (page, pageSize) = call.paging()
            call.respond(service.allPlaylists(page, pageSize))
        }

        m3u("/m3u/{id}") { map ->
            val service by inject<PlaylistService>()

            map["id"]?.toUUIDOrNull()?.let { service.byIdFull(it) }
        }
    }
}