package dev.dertyp.routing

import dev.dertyp.core.omitLyrics
import dev.dertyp.core.toUUIDOrNull
import dev.dertyp.data.InsertableSong
import dev.dertyp.data.PlaylistEntry
import dev.dertyp.data.Song
import dev.dertyp.data.SongWithoutLyrics
import dev.dertyp.services.SongService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import java.util.*
import kotlin.io.path.Path
import kotlin.math.min

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
                pathParameter<String>("artistId") {
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
        m3u(
            path = "/byArtist/{artistId}/m3u",
            pathParams = listOf(Pair("artistId", "The artist id to search all songs for.")),
            validate = { map -> map["artistId"] != null }) { map ->
            map["artistId"]?.toUUIDOrNull()?.let {
                Pair(
                    "All Songs",
                    service.byArtist(it).map { song ->
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
                pathParameter<String>("title") {
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
        get("/stream/{id}", {
            request {
                pathParameter<String>("id") {
                    description = "The id of the song."
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "Full audio of the song."
                }
                HttpStatusCode.PartialContent to {
                    description = "The audio stream of the song."
                }
            }
        }) {
            val id = call.parameters["id"]?.toUUIDOrNull()
            if (id == null) return@get call.respond(HttpStatusCode.BadRequest)

            val song = service.byId(id)
            if (song == null) return@get call.respond(HttpStatusCode.NotFound)

            val flacFile = Path(song.path).toFile()
            if (!flacFile.exists()) return@get call.respond(HttpStatusCode.NotFound)

            val contentType = ContentType.parse("audio/flac")

            val range = call.request.ranges()?.ranges?.first()
            val fullSize = flacFile.length()

            when (range) {
                is ContentRange.TailFrom,
                is ContentRange.Suffix,
                is ContentRange.Bounded -> {
                    val start = when (range) {
                        is ContentRange.TailFrom -> range.from.coerceIn(0 until fullSize)
                        is ContentRange.Bounded -> range.from.coerceIn(0 until fullSize)
                        is ContentRange.Suffix -> 0
                    }
                    val end = when (range) {
                        is ContentRange.TailFrom -> fullSize
                        is ContentRange.Bounded -> min(range.to, fullSize)
                        is ContentRange.Suffix -> min(range.lastCount, fullSize)
                    }
                    val chunkSize = end - start

                    if (chunkSize <= 0) return@get call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)

                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.ContentRange, "bytes ${start}-${end}/${fullSize}")
                    call.response.header(HttpHeaders.ContentLength, chunkSize.toString())
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Inline.withParameter(ContentDisposition.Parameters.FileName, flacFile.name)
                            .toString()
                    )

                    call.respondBytesWriter(contentType, HttpStatusCode.PartialContent) {
                        flacFile.inputStream().use { inputStream ->
                            inputStream.skip(start)
                            writeFully(inputStream.readNBytes(chunkSize.toInt()))
                        }
                    }

                }

                else -> {
                    call.respondBytesWriter(contentType) {
                        flacFile.inputStream().transferTo(toOutputStream())
                    }
                }
            }
        }

        get("/list", {
            response {
                HttpStatusCode.OK to {
                    description = "List of all Songs"
                    body<List<SongWithoutLyrics>>()
                }
            }
        }) {
            call.respond(service.allSongs().map { it.omitLyrics() })
        }

        m3u(path = "/list/m3u", pathParams = listOf(), validate = { true }) {
            Pair(
                "All Songs",
                service.allSongs().map {
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
            val song = service.getOrCreate(call.receive())
            if (song == null) return@post call.respond(HttpStatusCode.NotFound)

            call.respond(song)
        }
    }
}