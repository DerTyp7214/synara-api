package dev.dertyp.routing

import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.Album
import dev.dertyp.services.AlbumService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Routing.album(service: AlbumService) {
    route("/album", {
        tags("album")
    }) {
        get("/byId/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The album id."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The album with the id."
                    body<Album>()
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val album = service.byId(id)
            if (album == null) return@get call.respond(HttpStatusCode.NotFound)

            call.respond(album)
        }
        get("/byName/{name}", {
            request {
                pathParameter<String>("name") {
                    description = "The album name."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "The album with the name."
                    body<Album>()
                }
            }
        }) {
            val name = call.parameters["name"]
            if (name == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byName(page, pageSize, name))
        }
        get("/byArtist/{artistId}", {
            request {
                pathParameter<String>("artistId") {
                    description = "The artist id to search for albums."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "The albums by the artist."
                    body<Album>()
                }
            }
        }) {
            val artistId = call.parameters["artistId"]?.toUUIDOrNull()
            if (artistId == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byArtist(page, pageSize, artistId))
        }
        get("/searchByName/{name}", {
            request {
                pathParameter<String>("name") {
                    description = "The name to search for albums."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "The albums matching the name."
                    body<Album>()
                }
            }
        }) {
            val name = call.parameters["name"]
            if (name == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.searchByName(page, pageSize, name))
        }
        get("/list", {
            request {
                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "All albums."
                    body<Album>()
                }
            }
        }) {
            val (page, pageSize) = call.paging()
            call.respond(service.allAlbums(page, pageSize))
        }
    }
}