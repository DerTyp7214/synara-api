package dev.dertyp.routing

import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.Artist
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.services.ArtistService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*

fun Routing.artist(service: ArtistService) {
    val logger = KtorSimpleLogger("artist")

    route("/artist", {
        tags("artist")
    }) {
        get("/byId/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The id of the artist."
                }
            }
            response {
                HttpStatusCode.OK to {
                    body<Artist>()
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val artist = service.byId(id)
            if (artist == null) return@get call.respond(HttpStatusCode.NotFound)

            call.respond(artist)
        }
        get("/byGroupId/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The group id of the artist."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    body<PaginatedResponse<Artist>>()
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            val artists = service.byGroup(page, pageSize, id)
            call.respond(artists)
        }
        get("/search/{query}", {
            request {
                pathParameter<String>("query") {
                    description = "The query of the artist."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    body<List<Artist>>()
                }
            }
        }) {
            val query = call.parameters["query"]
            if (query == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            val artists = service.rankedSearch(page, pageSize, query)
            call.respond(artists)
        }

        get("/list", {
            request {
                paging()
            }
            response {
                HttpStatusCode.OK to {
                    body<List<Artist>>()
                }
            }
        }) {
            val (page, pageSize) = call.paging()
            val artists = service.allArtists(page, pageSize)
            call.respond(artists)
        }
    }
}