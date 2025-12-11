package dev.dertyp.routing

import dev.dertyp.core.getUser
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
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class SetLikedBody(val liked: Boolean)

fun Route.song(service: SongService) {
    val logger = KtorSimpleLogger("song")

    route("/song", {
        tags("song")
    }) {
        post("/setLiked/{id}", {
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
            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@post call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@post call.respond(HttpStatusCode.Unauthorized)

            val liked = call.receive<SetLikedBody>().liked

            val song = service.setLiked(id, user.id, liked) ?: return@post call.respond(HttpStatusCode.BadRequest)

            return@post call.respond(song)
        }

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
            val id = call.parameters["id"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val song = service.byId(id, user.id) ?: return@get call.respond(HttpStatusCode.NotFound)

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
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val id = call.parameters["albumId"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val (page, pageSize) = call.paging()

            call.respond(service.byAlbum(page, pageSize, id, user.id).omitLyrics())
        }
        m3u(
            path = "/byAlbum/{albumId}/m3u",
            pathParams = listOf(Pair("albumId", "The album id to search all songs for.")),
            validate = { map -> map["albumId"] != null }) { map ->
            val user = call.getUser() ?: return@m3u null

            map["albumId"]?.toUUIDOrNull()?.let {
                Pair(
                    "All Songs on $it",
                    service.byAlbum(0, Int.MAX_VALUE, it, user.id).data.map { song ->
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
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val id = call.parameters["playlistId"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val (page, pageSize) = call.paging()

            call.respond(service.byPlaylist(page, pageSize, id, user.id).omitLyrics())
        }
        m3u(
            path = "/byPlaylist/{playlistId}/m3u",
            pathParams = listOf(Pair("playlistId", "The playlist id to search all songs for.")),
            validate = { map -> map["playlistId"] != null }) { map ->
            val user = call.getUser() ?: return@m3u null

            map["playlistId"]?.toUUIDOrNull()?.let {
                Pair(
                    "All Songs on $it",
                    service.byPlaylist(0, Int.MAX_VALUE, it, user.id).data.map { song ->
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
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val id = call.parameters["artistId"]?.toUUIDOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val (page, pageSize) = call.paging()

            call.respond(service.byArtist(page, pageSize, id, user.id).omitLyrics())
        }
        m3u(
            path = "/byArtist/{artistId}/m3u",
            pathParams = listOf(Pair("artistId", "The artist id to search all songs for.")),
            validate = { map -> map["artistId"] != null }) { map ->
            val user = call.getUser() ?: return@m3u null

            map["artistId"]?.toUUIDOrNull()?.let {
                Pair(
                    "All Songs by $it",
                    service.byArtist(0, Int.MAX_VALUE, it, user.id).data.map { song ->
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
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val title = call.parameters["title"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val (page, pageSize) = call.paging()

            call.respond(service.byTitle(page, pageSize, title, user.id).omitLyrics())
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
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val query = call.parameters["query"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val explicit = call.parameters["explicit"] == "true"

            val (page, pageSize) = call.paging()

            call.respond(service.rankedSearch(page, pageSize, query, explicit, user.id).omitLyrics())
        }

        get("/searchLiked/{query}", {
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
                    description = "All liked songs including the query."
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val query = call.parameters["query"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val explicit = call.parameters["explicit"] == "true"

            val (page, pageSize) = call.paging()

            call.respond(service.rankedSearch(page, pageSize, query, explicit, user.id, true).omitLyrics())
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
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val explicit = call.parameters["explicit"] == "true"

            val (page, pageSize) = call.paging()
            call.respond(service.allSongs(page, pageSize, explicit, user.id).omitLyrics())
        }

        m3u(
            path = "/list/m3u",
            pathParams = listOf(),
            queryParams = listOf(
                Pair("explicit", "Include explicit songs."),
            ),
            validate = { true }) { map ->
            val user = call.getUser() ?: return@m3u null

            Pair(
                "All Songs",
                service.allSongs(0, Int.MAX_VALUE, map["explicit"] == "true", user.id).data.map {
                    PlaylistEntry(it.id, it.title, it.duration)
                }
            )
        }

        get("/liked", {
            request {
                queryParameter<Boolean>("explicit") {
                    description = "Include explicit songs."
                }

                paging()
            }
            response {
                HttpStatusCode.OK to {
                    description = "List of all liked Songs"
                    body<PaginatedResponse<UserSong>>()
                }
            }
        }) {
            val user = call.getUser() ?: return@get call.respond(HttpStatusCode.Unauthorized)

            val explicit = call.parameters["explicit"] == "true"

            val (page, pageSize) = call.paging()
            call.respond(service.likedSongs(page, pageSize, explicit, user.id).omitLyrics())
        }

        m3u(
            path = "/liked/m3u",
            pathParams = listOf(),
            queryParams = listOf(
                Pair("explicit", "Include explicit songs."),
            ),
            validate = { true }) { map ->
            val user = call.getUser() ?: return@m3u null

            Pair(
                "All liked Songs",
                service.likedSongs(0, Int.MAX_VALUE, map["explicit"] == "true", user.id).data.map {
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
            val song = service.createBatch(listOf(call.receive())).values.singleOrNull() ?: return@post call.respond(
                HttpStatusCode.NotFound
            )

            call.respond(song)
        }
    }
}