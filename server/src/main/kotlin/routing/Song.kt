package dev.dertyp.routing

import dev.dertyp.core.omitLyrics
import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.*
import dev.dertyp.services.SongService
import dev.dertyp.stream
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import java.util.*

fun Routing.song(service: SongService) {
    val logger = KtorSimpleLogger("song")

    route("/song", {
        tags("song")
    }) {
        get("/byId/{id}", {
            request {
                pathParameter<String>("id") {
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
                pathParameter<String>("albumId") {
                    description = "The album id to search all songs for."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs on this album."
                    body<PaginatedResponse<Song>>()
                }
            }
        }) {
            val id = call.parameters["albumId"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byAlbum(page, pageSize, id))
        }
        m3u(
            path = "/byAlbum/{albumId}/m3u",
            pathParams = listOf(Pair("albumId", "The album id to search all songs for.")),
            validate = { map -> map["albumId"] != null }) { map ->
            map["albumId"]?.toUUIDOrNull()?.let {
                Pair(
                    "All Songs on $it",
                    service.byAlbum(0, Int.MAX_VALUE, it).data.map { song ->
                        PlaylistEntry(song.id, song.title, song.duration)
                    }
                )
            }
        }
        get("/byArtist/{artistId}", {
            request {
                pathParameter<String>("artistId") {
                    description = "The artist id to search all songs for."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs by this artist."
                    body<PaginatedResponse<Song>>()
                }
            }
        }) {
            val id = call.parameters["artistId"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byArtist(page, pageSize, id))
        }
        m3u(
            path = "/byArtist/{artistId}/m3u",
            pathParams = listOf(Pair("artistId", "The artist id to search all songs for.")),
            validate = { map -> map["artistId"] != null }) { map ->
            map["artistId"]?.toUUIDOrNull()?.let {
                Pair(
                    "All Songs by $it",
                    service.byArtist(0, Int.MAX_VALUE, it).data.map { song ->
                        PlaylistEntry(song.id, song.title, song.duration)
                    }
                )
            }
        }
        get("/byTitle/{title}", {
            request {
                pathParameter<String>("title") {
                    description = "The title to exactly match to."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs with exactly this title."
                    body<PaginatedResponse<Song>>()
                }
            }
        }) {
            val title = call.parameters["title"]
            if (title == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byTitle(page, pageSize, title))
        }
        get("/searchByTitle/{title}", {
            request {
                pathParameter<String>("title") {
                    description = "The title query."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs including the query."
                    body<PaginatedResponse<Song>>()
                }
            }
        }) {
            val title = call.parameters["title"]
            if (title == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.searchByTitle(page, pageSize, title))
        }
        m3u(
            path = "/searchByTitle/{title}/m3u",
            pathParams = listOf(Pair("title", "The query to search all songs for.")),
            validate = { map -> map["title"] != null }) { map ->
            map["title"]?.let {
                Pair(
                    "All Songs for $it",
                    service.searchByTitle(0, Int.MAX_VALUE, it).data.map { song ->
                        PlaylistEntry(song.id, song.title, song.duration)
                    }
                )
            }
        }
        stream(service)

        get("/list", {
            request {
                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "List of all Songs"
                    body<PaginatedResponse<SongWithoutLyrics>>()
                }
            }
        }) {
            val (page, pageSize) = call.paging()
            call.respond(service.allSongs(page, pageSize).omitLyrics())
        }

        m3u(path = "/list/m3u", pathParams = listOf(), validate = { true }) {
            Pair(
                "All Songs",
                service.allSongs(0, Int.MAX_VALUE).data.map {
                    PlaylistEntry(it.id, it.title, it.duration)
                }
            )
        }

        post<InsertableSong>({
            request {
                body<InsertableSong>()
            }
            response {
                HttpStatusCode.OK to {
                    description = "The matched or inserted songId."
                    body<UUID>()
                }
            }
        }) {
            val song = service.createBatch(listOf(call.receive())).singleOrNull()
            if (song == null) return@post call.respond(HttpStatusCode.NotFound)

            call.respond(song)
        }
    }
}