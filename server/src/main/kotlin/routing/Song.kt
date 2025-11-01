package dev.dertyp.routing

import dev.dertyp.core.omitLyrics
import dev.dertyp.core.paging
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.*
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import java.util.*

fun Route.song(service: SongService) {
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
                    body<PaginatedResponse<SongWithoutLyrics>>()
                }
            }
        }) {
            val id = call.parameters["albumId"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byAlbum(page, pageSize, id).omitLyrics())
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
        get("/byPlaylist/{playlistId}", {
            request {
                pathParameter<String>("playlistId") {
                    description = "The playlist id to search all songs for."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs on this playlist."
                    body<PaginatedResponse<SongWithoutLyrics>>()
                }
            }
        }) {
            val id = call.parameters["playlistId"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byPlaylist(page, pageSize, id).omitLyrics())
        }
        m3u(
            path = "/byPlaylist/{playlistId}/m3u",
            pathParams = listOf(Pair("playlistId", "The playlist id to search all songs for.")),
            validate = { map -> map["playlistId"] != null }) { map ->
            map["playlistId"]?.toUUIDOrNull()?.let {
                Pair(
                    "All Songs on $it",
                    service.byPlaylist(0, Int.MAX_VALUE, it).data.map { song ->
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
                    body<PaginatedResponse<SongWithoutLyrics>>()
                }
            }
        }) {
            val id = call.parameters["artistId"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byArtist(page, pageSize, id).omitLyrics())
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
                    body<PaginatedResponse<SongWithoutLyrics>>()
                }
            }
        }) {
            val title = call.parameters["title"]
            if (title == null) return@get call.respond(HttpStatusCode.BadRequest)

            val (page, pageSize) = call.paging()

            call.respond(service.byTitle(page, pageSize, title).omitLyrics())
        }
        get("/search/{query}", {
            request {
                pathParameter<String>("query") {
                    description = "The query."
                }
                queryParameter<Boolean>("explicit") {
                    description = "Include explicit songs."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "All songs including the query."
                    body<PaginatedResponse<SongWithoutLyrics>>()
                }
            }
        }) {
            val query = call.parameters["query"]
            if (query == null) return@get call.respond(HttpStatusCode.BadRequest)

            val explicit = call.parameters["explicit"] == "true"

            val (page, pageSize) = call.paging()

            call.respond(service.rankedSearch(page, pageSize, query, explicit).omitLyrics())
        }
        m3u(
            path = "/search/{query}/m3u",
            pathParams = listOf(
                Pair("query", "The query to search all songs for."),
            ),
            queryParams = listOf(
                Pair("explicit", "Include explicit songs."),
            ),
            validate = { map -> map["query"] != null }) { map ->
            map["query"]?.let {
                Pair(
                    "All Songs for $it",
                    service.rankedSearch(0, Int.MAX_VALUE, it, map["explicit"] == "true").data.map { song ->
                        PlaylistEntry(song.id, song.title, song.duration)
                    }
                )
            }
        }

        get("/list", {
            request {
                queryParameter<Boolean>("explicit") {
                    description = "Include explicit songs."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "List of all Songs"
                    body<PaginatedResponse<SongWithoutLyrics>>()
                }
            }
        }) {
            val explicit = call.parameters["explicit"] == "true"

            val (page, pageSize) = call.paging()
            call.respond(service.allSongs(page, pageSize, explicit).omitLyrics())
        }

        m3u(
            path = "/list/m3u",
            pathParams = listOf(),
            queryParams = listOf(
                Pair("explicit", "Include explicit songs."),
            ),
            validate = { true }) { map ->
            Pair(
                "All Songs",
                service.allSongs(0, Int.MAX_VALUE, map["explicit"] == "true").data.map {
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
            val song = service.createBatch(listOf(call.receive())).values.singleOrNull()
            if (song == null) return@post call.respond(HttpStatusCode.NotFound)

            call.respond(song)
        }
    }
}