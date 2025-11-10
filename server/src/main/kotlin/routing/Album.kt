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

fun Route.album(service: AlbumService) {
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

                queryParameter<Boolean>("singles") {
                    description = "If it should return singles instead of full albums. (default: false)"
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

            val singles = call.parameters["singles"]?.toBoolean() ?: false

            val (page, pageSize) = call.paging()

            call.respond(service.byArtist(page, pageSize, artistId, singles))
        }
        get("/search/{query}", {
            request {
                pathParameter<String>("query") {
                    description = "The query to search for albums."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "The albums matching the query."
                    body<Album>()
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