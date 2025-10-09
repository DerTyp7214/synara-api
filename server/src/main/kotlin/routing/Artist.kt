package dev.dertyp.routing

import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.Artist
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
            }
            response {
                HttpStatusCode.OK to {
                    body<List<Artist>>()
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val artists = service.byGroup(id)
            call.respond(artists)
        }
        get("/searchByName/{name}", {
            request {
                pathParameter<String>("name") {
                    description = "The name of the artist."
                }
            }
            response {
                HttpStatusCode.OK to {
                    body<List<Artist>>()
                }
            }
        }) {
            val name = call.parameters["name"]
            if (name == null) return@get call.respond(HttpStatusCode.BadRequest)

            val artists = service.searchByName(name)
            call.respond(artists)
        }

        get("/list", {
            response {
                HttpStatusCode.OK to {
                    body<List<Artist>>()
                }
            }
        }) {
            val artists = service.allArtists()
            call.respond(artists)
        }
    }
}