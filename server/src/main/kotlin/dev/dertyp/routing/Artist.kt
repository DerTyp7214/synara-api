package dev.dertyp.routing

import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.Artist
import dev.dertyp.data.MergeArtists
import dev.dertyp.data.PaginatedResponse
import dev.dertyp.services.ArtistService
import dev.dertyp.services.IArtistService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.rpc.krpc.ktor.server.rpc
import org.koin.ktor.ext.inject

fun Route.artist() {
    val logger = KtorSimpleLogger("artist")

    route("/artist", {
        tags("artist")
    }) {
        rpc {
            val service by inject<ArtistService>()

            registerService<IArtistService> { service }
        }

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
            val service by inject<ArtistService>()

            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val artist = service.byId(id) ?: return@get call.respond(HttpStatusCode.NotFound)

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
            val service by inject<ArtistService>()

            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

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
            val service by inject<ArtistService>()

            val query = call.parameters["query"] ?: return@get call.respond(HttpStatusCode.BadRequest)

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
            val service by inject<ArtistService>()

            val (page, pageSize) = call.paging()
            val artists = service.allArtists(page, pageSize)
            call.respond(artists)
        }

        post("/merge", {
            request {
                body<MergeArtists> {
                    description = "The artist to merge with the existing artist."
                }
            }
            response {
                HttpStatusCode.OK to {
                    body<Artist>()
                }
            }
        }) {
            val service by inject<ArtistService>()

            val mergeArtists = call.receive<MergeArtists>()

            val mergedArtist = service.mergeArtists(mergeArtists) ?: return@post call.respond(HttpStatusCode.BadRequest)

            call.respond(mergedArtist)
        }
    }
}