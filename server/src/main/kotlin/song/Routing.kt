package dev.dertyp.song

import dev.dertyp.Indexer
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.InsertableSong
import dev.dertyp.data.Song
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*

fun Routing.song(service: SongService) {
    sse("/buildIndex") {
        val indexer = Indexer(service)

        indexer.start { stdout ->
            send(stdout)
        }
    }

    route("/song") {
        get("/byId/{id}", {
            request {
                queryParameter<String>("id") {
                    description = "The song id."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "The song with the id."
                    body<Song>()
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val song = service.byId(id)
            if (song == null) return@get call.respond(HttpStatusCode.NotFound)

            call.respond(song)
        }
        get("/byAlbum/{albumId}", {
            request {
                queryParameter<String>("albumId") {
                    description = "The album id to search all songs for."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs on this album."
                    body<List<Song>>()
                }
            }
        }) {
            val id = call.parameters["albumId"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            call.respond(service.byAlbum(id))
        }
        get("/byArtist/{artistId}", {
            request {
                queryParameter<String>("artistId") {
                    description = "The artist id to search all songs for."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs by this artist."
                    body<List<Song>>()
                }
            }
        }) {
            val id = call.parameters["artistId"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            call.respond(service.byArtist(id))
        }
        get("/byTitle/{title}", {
            request {
                queryParameter<String>("title") {
                    description = "The title to exactly match to."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs with exactly this title."
                    body<List<Song>>()
                }
            }
        }) {
            val title = call.parameters["title"]
            if (title == null) return@get call.respond(HttpStatusCode.BadRequest)

            call.respond(service.byTitle(title))
        }
        get("/searchByTitle/{title}", {
            request {
                queryParameter<String>("title") {
                    description = "The title query."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs including the query."
                    body<List<Song>>()
                }
            }
        }) {
            val title = call.parameters["title"]
            if (title == null) return@get call.respond(HttpStatusCode.BadRequest)

            call.respond(service.searchByTitle(title))
        }

        get("/list", {
            response {
                HttpStatusCode.OK to {
                    description = "List of all Songs"
                    body<List<Song>>()
                }
            }
        }) {
            call.respond(service.allSongs())
        }

        post<InsertableSong>({
            request {
                body<InsertableSong>()
            }
            response {
                HttpStatusCode.OK to {
                    description = "The matched or inserted song."
                    body<Song>()
                }
            }
        }) {
            val song = service.getOrCreate(call.receive())
            if (song == null) return@post call.respond(HttpStatusCode.NotFound)

            call.respond(song)
        }
    }
}