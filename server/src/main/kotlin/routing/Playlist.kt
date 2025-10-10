package dev.dertyp.routing

import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.InsertablePlaylist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.data.Playlist
import dev.dertyp.services.PlaylistService
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.put
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.playlist(service: PlaylistService) {
    route("/playlist", {
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
                    body<Playlist>()
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val playlist = service.byId(id)
            if (playlist == null) return@get call.respond(HttpStatusCode.NotFound)

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
            val name = call.parameters["name"]
            if (name == null) return@get call.respond(HttpStatusCode.BadRequest)

            val playlist = service.byName(name)
            if (playlist == null) return@get call.respond(HttpStatusCode.NotFound)

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
            val query = call.parameters["query"]
            if (query == null) return@get call.respond(HttpStatusCode.BadRequest)

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
                    body<List<Playlist>>()
                }
            }
        }) {
            val (page, pageSize) = call.paging()
            call.respond(service.allPlaylists(page, pageSize))
        }

        m3u("/m3u/{id}") { map ->
            map["id"]?.toUUIDOrNull()?.let { service.byIdFull(it) }
        }

        put("/create", {
            request {
                body<InsertablePlaylist>()
            }
            response {
                HttpStatusCode.OK to {
                    body<Playlist>()
                }
            }
        }) {
            val insertablePlaylist = call.receive<InsertablePlaylist>()

            val playlistId = service.createBatch(listOf(insertablePlaylist)).firstOrNull()
            if (playlistId == null) return@put call.respond(HttpStatusCode.BadRequest)

            val playlist = service.byId(playlistId)
            if (playlist == null) return@put call.respond(HttpStatusCode.NotFound)

            call.respond(playlist)
        }

        delete("/delete/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The playlist id."
                }
            }
            response {
                HttpStatusCode.NoContent to {
                    description = "The was successfully deleted!."
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@delete call.respond(HttpStatusCode.BadRequest)

            val success = service.delete(id)
            if (!success) return@delete call.respond(HttpStatusCode.NotFound)

            call.respond(HttpStatusCode.NoContent)
        }
    }
}